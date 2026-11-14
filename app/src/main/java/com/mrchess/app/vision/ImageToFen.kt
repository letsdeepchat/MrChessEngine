package com.mrchess.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mrchess.core.rules.Side
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Classes in the order the model was trained with (see ml_training/train_piece_classifier.py). */
val TILE_CLASSES = listOf("empty", "P", "N", "B", "R", "Q", "K", "p", "n", "b", "r", "q", "k")
const val TILE_SIZE = 64
const val BOARD_SIZE = TILE_SIZE * 8

/**
 * Result of recognising a board image. [labels] is row-major as it appears in the image
 * (index 0 = top-left square of the picture).
 */
data class Recognition(
    val labels: List<String>,
    val confidences: List<Float>,
    val boardBitmap: Bitmap,
    val likelyBlackAtBottom: Boolean,
) {
    val meanConfidence: Float get() = confidences.average().toFloat()

    fun lowConfidenceSquares(blackAtBottom: Boolean): List<String> =
        confidences.withIndex().filter { it.value < 0.6f }.map { squareName(it.index, blackAtBottom) }

    fun toFen(sideToMove: Side, blackAtBottom: Boolean): String {
        val rows = StringBuilder()
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val idx = imageIndex(file, rank, blackAtBottom)
                val l = labels[idx]
                if (l == "empty") empty++ else {
                    if (empty > 0) { rows.append(empty); empty = 0 }
                    rows.append(l)
                }
            }
            if (empty > 0) rows.append(empty)
            if (rank > 0) rows.append('/')
        }
        // Castling rights are inferred from piece placement; Position.fromFen drops impossible ones.
        return "$rows ${if (sideToMove == Side.WHITE) "w" else "b"} KQkq - 0 1"
    }

    companion object {
        /** Image index of a board square given the orientation of the picture. */
        fun imageIndex(file: Int, rank: Int, blackAtBottom: Boolean): Int {
            val row = if (blackAtBottom) rank else 7 - rank
            val col = if (blackAtBottom) 7 - file else file
            return row * 8 + col
        }

        fun squareName(imageIdx: Int, blackAtBottom: Boolean): String {
            val row = imageIdx / 8
            val col = imageIdx % 8
            val rank = if (blackAtBottom) row else 7 - row
            val file = if (blackAtBottom) 7 - col else col
            return "${'a' + file}${'1' + rank}"
        }
    }
}

/**
 * On-device screenshot → FEN: OpenCV finds and rectifies the board, a 13-class INT8 TFLite
 * CNN classifies each of the 64 tiles. Runs only when the user picks or shares an image.
 */
class ImageToFen(private val context: Context) : Closeable {

    private val openCvReady: Boolean by lazy { OpenCVLoader.initLocal() }

    private val interpreter: Interpreter by lazy {
        Interpreter(loadModel("piece_classifier_int8.tflite"), Interpreter.Options().setNumThreads(2))
    }

    fun recognize(uri: Uri): Recognition {
        check(openCvReady) { "OpenCV failed to load" }
        val bmp = decode(uri)
        val board = BoardDetector.detect(bmp)
        val (labels, conf) = classify(board)
        return Recognition(labels, conf, board, guessBlackAtBottom(labels))
    }

    private fun decode(uri: Uri): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, real) }
            ?: throw IllegalArgumentException("Unsupported image")
    }

    private fun classify(board: Bitmap): Pair<List<String>, List<Float>> {
        val input = interpreter.getInputTensor(0)
        val q = input.quantizationParams()
        val isInt8 = input.dataType() == org.tensorflow.lite.DataType.INT8
        interpreter.resizeInput(0, intArrayOf(64, TILE_SIZE, TILE_SIZE, 3))
        interpreter.allocateTensors()
        val bytesPer = if (isInt8) 1 else 4
        val buf = ByteBuffer.allocateDirect(64 * TILE_SIZE * TILE_SIZE * 3 * bytesPer).order(ByteOrder.nativeOrder())
        val px = IntArray(TILE_SIZE * TILE_SIZE)
        for (i in 0 until 64) {
            val tile = Bitmap.createBitmap(board, (i % 8) * TILE_SIZE, (i / 8) * TILE_SIZE, TILE_SIZE, TILE_SIZE)
            tile.getPixels(px, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
            for (c in px) {
                for (shift in intArrayOf(16, 8, 0)) {
                    val v = ((c shr shift) and 0xFF) / 255f
                    if (isInt8) buf.put(((v / q.scale) + q.zeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                    else buf.putFloat(v)
                }
            }
        }
        buf.rewind()
        val out = interpreter.getOutputTensor(0)
        val oq = out.quantizationParams()
        val n = TILE_CLASSES.size
        val logits = Array(64) { FloatArray(n) }
        if (out.dataType() == org.tensorflow.lite.DataType.INT8) {
            val raw = Array(64) { ByteArray(n) }
            interpreter.run(buf, raw)
            for (i in 0 until 64) for (k in 0 until n) logits[i][k] = (raw[i][k] - oq.zeroPoint) * oq.scale
        } else interpreter.run(buf, logits)

        val labels = ArrayList<String>(64)
        val conf = ArrayList<Float>(64)
        for (row in logits) {
            // Model ends in softmax; renormalise defensively in case it outputs logits.
            val sum = row.sum()
            val probs = if (abs(sum - 1f) < 0.05f) row else {
                val m = row.max(); val e = row.map { exp((it - m).toDouble()).toFloat() }; val s = e.sum(); e.map { it / s }.toFloatArray()
            }
            val k = probs.indices.maxBy { probs[it] }
            labels += TILE_CLASSES[k]; conf += probs[k]
        }
        return labels to conf
    }

    /** White pawns/king in the top half of the picture suggest the board is shown from Black's side. */
    private fun guessBlackAtBottom(labels: List<String>): Boolean {
        var score = 0
        labels.forEachIndexed { i, l ->
            val top = i < 32
            when (l) {
                "P", "K" -> score += if (top) 1 else -1
                "p", "k" -> score += if (top) -1 else 1
            }
        }
        return score > 0
    }

    private fun loadModel(name: String): MappedByteBuffer {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { s ->
            return s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    override fun close() {
        runCatching { interpreter.close() }
    }
}

/** Finds the chessboard in an image and warps it to a BOARD_SIZE × BOARD_SIZE square. */
object BoardDetector {
    fun detect(src: Bitmap): Bitmap {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val quad = findBoardQuad(gray)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val warped = Mat()
        if (quad != null) {
            val dst = MatOfPoint2f(Point(0.0, 0.0), Point(BOARD_SIZE.toDouble(), 0.0), Point(BOARD_SIZE.toDouble(), BOARD_SIZE.toDouble()), Point(0.0, BOARD_SIZE.toDouble()))
            val m = Imgproc.getPerspectiveTransform(MatOfPoint2f(*quad), dst)
            Imgproc.warpPerspective(rgb, warped, m, Size(BOARD_SIZE.toDouble(), BOARD_SIZE.toDouble()))
        } else {
            // Fallback: assume the user cropped to the board; take the centred square.
            val side = minOf(rgb.cols(), rgb.rows())
            val roi = rgb.submat((rgb.rows() - side) / 2, (rgb.rows() + side) / 2, (rgb.cols() - side) / 2, (rgb.cols() + side) / 2)
            Imgproc.resize(roi, warped, Size(BOARD_SIZE.toDouble(), BOARD_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        }
        val out = Bitmap.createBitmap(BOARD_SIZE, BOARD_SIZE, Bitmap.Config.ARGB_8888)
        val outRgba = Mat()
        Imgproc.cvtColor(warped, outRgba, Imgproc.COLOR_RGB2RGBA)
        Utils.matToBitmap(outRgba, out)
        listOf(rgba, gray, rgb, warped, outRgba).forEach { it.release() }
        return out
    }

    /** Largest convex, roughly square quadrilateral covering ≥ 15% of the image; corners TL, TR, BR, BL. */
    fun findBoardQuad(gray: Mat): Array<Point>? {
        val blur = Mat(); val edges = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(blur, edges, 40.0, 120.0)
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val minArea = gray.rows() * gray.cols() * 0.15
        var best: Array<Point>? = null
        var bestArea = 0.0
        for (c in contours) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            val pts = approx.toArray()
            if (pts.size != 4) continue
            if (!Imgproc.isContourConvex(MatOfPoint(*pts))) continue
            val area = abs(Imgproc.contourArea(approx))
            if (area < minArea || area <= bestArea) continue
            val r = Imgproc.boundingRect(MatOfPoint(*pts))
            val aspect = r.width.toDouble() / r.height
            if (aspect !in 0.8..1.25) continue
            best = order(pts); bestArea = area
        }
        blur.release(); edges.release()
        return best
    }

    private fun order(p: Array<Point>): Array<Point> {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.minBy { it.y - it.x }
        val bl = p.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }
}
