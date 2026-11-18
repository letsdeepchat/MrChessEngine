package com.mrchess.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.mrchess.core.review.MoveQuality
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Piece
import com.mrchess.core.rules.PieceType
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import com.mrchess.core.rules.Sq
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private val CheckTint = Color(0xAAE04040)
private val ArrowColor = Color(0xCC1E88E5)
private val AltArrowColor = Color(0x99FB8C00)
private val TargetDot = Color(0x55000000)
private val HoverRing = Color(0xCCFFFFFF)
private val WhiteFill = Color.White
private val WhiteOutline = Color(0xFF202020)
private val BlackFill = Color(0xFF202020)
private val BlackOutline = Color(0xFF9E9E9E)
private const val SLIDE_MS = 160

/** A piece sliding from one square to another (the move just played), plus what it captured. */
private class Slide(
    val piece: Piece, val from: Int, val to: Int,
    val rook: Piece?, val rookFrom: Int, val rookTo: Int,
    val captured: Piece?, val capturedSq: Int,
)

/** Non-snapshot memory of the board between compositions. */
private class BoardMemory {
    var shown: Position? = null
    /** Square a piece was just dropped on by dragging: that move lands without sliding. */
    var dropped: Int = Sq.NONE
}

private class Drag(val from: Int, val piece: Piece, val at: Offset)

/** The board's inputs as the gesture handler should see them right now. */
private class BoardInput(val position: Position, val flipped: Boolean, val selected: Int?, val onTap: ((Int) -> Unit)?)

/** If [after] is [before] with [m] played, the pieces that should slide; otherwise null (jumps, navigation). */
private fun slideFor(before: Position, after: Position, m: Move): Slide? {
    val piece = before[m.from] ?: return null
    val played = runCatching { before.play(m, validate = false) }.getOrNull() ?: return null
    if (played.toFen().substringBefore(' ') != after.toFen().substringBefore(' ')) return null
    val castle = before.isCastle(m)
    val rank = Sq.rank(m.from)
    val kingSide = Sq.file(m.to) == 6
    val ep = before.isEnPassant(m)
    return Slide(
        piece = after[m.to] ?: piece, from = m.from, to = m.to,
        rook = if (castle) Piece(PieceType.ROOK, piece.side) else null,
        rookFrom = if (castle) Sq.of(if (kingSide) 7 else 0, rank) else Sq.NONE,
        rookTo = if (castle) Sq.of(if (kingSide) 5 else 3, rank) else Sq.NONE,
        captured = before.capturedPiece(m),
        capturedSq = if (ep) Sq.of(Sq.file(m.to), rank) else m.to,
    )
}

/**
 * The chess board, drawn as a single canvas: squares, highlights, pieces (Unicode glyphs measured
 * once per size), coordinates and arrows. Tap a piece then a square, or drag the piece. The move
 * just played slides into place. Turning pieces (Two Player) and sliding only redraw the canvas,
 * they never recompose, so the board stays smooth on slow phones.
 */
@Composable
fun ChessBoard(
    position: Position,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    lastMove: Move? = null,
    selected: Int? = null,
    targets: Set<Int> = emptySet(),
    arrow: Move? = null,
    altArrow: Move? = null,
    /** Move-quality badge drawn at the top-right corner of a square (usually the last move's destination). */
    badge: Pair<Int, MoveQuality>? = null,
    onTap: ((Int) -> Unit)? = null,
    /**
     * Two Player across a table: rotation of White's / Black's piece icons in degrees (the board
     * grid itself never turns). Changes animate, so the pieces visibly turn to face the next player.
     */
    whiteRotation: Float = 0f,
    blackRotation: Float = 0f,
    theme: BoardTheme = LocalBoardTheme.current,
) {
    val whiteRot = animateFloatAsState(whiteRotation, tween(350, delayMillis = 120), label = "whiteRot")
    val blackRot = animateFloatAsState(blackRotation, tween(350, delayMillis = 120), label = "blackRot")
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val memory = remember { BoardMemory() }

    // Slide the move just played (computed during composition so the very first frame is right).
    val slide = remember(position) {
        val before = memory.shown
        memory.shown = position
        val dropped = memory.dropped
        memory.dropped = Sq.NONE
        if (before == null || lastMove == null || dropped == lastMove.to) null else slideFor(before, position, lastMove)
    }
    val progress = remember(position) { Animatable(if (slide != null) 0f else 1f) }
    LaunchedEffect(progress) { if (progress.value < 1f) progress.animateTo(1f, tween(SLIDE_MS, easing = FastOutSlowInEasing)) }

    var drag by remember { mutableStateOf<Drag?>(null) }
    val input = rememberUpdatedState(BoardInput(position, flipped, selected, onTap))

    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f)) {
        val cellPx = with(density) { maxWidth.toPx() } / 8f
        // Glyph layouts depend only on the square size: measure the 6 shapes once, not every frame.
        val glyphs: Map<PieceType, TextLayoutResult> = remember(cellPx, measurer) {
            val style = TextStyle(fontSize = with(density) { (cellPx * 0.82f).toSp() })
            PieceType.entries.associateWith { t -> measurer.measure(Piece(t, Side.BLACK).glyph + "\uFE0E", style) }
        }
        val coords: List<TextLayoutResult> = remember(measurer) {
            val style = TextStyle(fontSize = 9.sp)
            (0..7).map { measurer.measure("${'1' + it}", style) } + (0..7).map { measurer.measure("${'a' + it}", style) }
        }
        val badgeText: TextLayoutResult? = remember(badge?.second, cellPx, measurer) {
            badge?.let { b ->
                measurer.measure(b.second.symbol, TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = with(density) { (cellPx * 0.2f).toSp() }))
            }
        }
        val outlineStroke = remember { Stroke(width = 4f) }

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val now = input.value
                    val tap = now.onTap ?: return@awaitEachGesture
                    val cell = size.width / 8f
                    fun squareAt(o: Offset): Int? {
                        val col = floor(o.x / cell).toInt()
                        val row = floor(o.y / cell).toInt()
                        if (col !in 0..7 || row !in 0..7) return null
                        return if (now.flipped) Sq.of(7 - col, row) else Sq.of(col, 7 - row)
                    }
                    val start = squareAt(down.position) ?: return@awaitEachGesture
                    memory.dropped = Sq.NONE
                    val pos = now.position
                    val own = pos[start]?.takeIf { it.side == pos.sideToMove }
                    // Select on touch-down so the highlight appears at once.
                    var selectedOnDown = false
                    if (own != null && now.selected != start) { tap(start); selectedOnDown = true }
                    var dragging = false
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (dragging) {
                                val to = squareAt(ch.position)
                                drag = null
                                if (to != null && to != start) { memory.dropped = to; tap(to) }
                            } else if (!selectedOnDown) tap(start)
                            ch.consume()
                            break
                        }
                        if (!dragging && own != null && (ch.position - down.position).getDistance() > slop) dragging = true
                        if (dragging && own != null) { drag = Drag(start, own, ch.position); ch.consume() }
                    }
                    if (dragging) drag = null
                }
            },
        ) {
            val cell = size.width / 8f
            fun topLeftOf(sq: Int): Offset {
                val col = if (flipped) 7 - Sq.file(sq) else Sq.file(sq)
                val row = if (flipped) Sq.rank(sq) else 7 - Sq.rank(sq)
                return Offset(col * cell, row * cell)
            }
            fun centerOf(sq: Int) = topLeftOf(sq) + Offset(cell / 2, cell / 2)
            val cellSize = Size(cell, cell)
            val p = progress.value
            val sliding = slide != null && p < 1f
            val dragging = drag

            // Squares and highlights.
            for (sq in 0 until 64) {
                val tl = topLeftOf(sq)
                val dark = (Sq.file(sq) + Sq.rank(sq)) % 2 == 0
                drawRect(if (dark) theme.dark else theme.light, tl, cellSize)
                if (lastMove != null && (sq == lastMove.from || sq == lastMove.to)) drawRect(theme.lastMove, tl, cellSize)
                if (sq == selected) drawRect(theme.selected, tl, cellSize)
            }
            if (position.inCheck()) {
                val k = position.kingSquare(position.sideToMove)
                if (k != Sq.NONE) drawCircle(CheckTint, cell / 2, centerOf(k))
            }
            // Coordinates on the edge squares.
            for (i in 0..7) {
                val rankSq = if (flipped) Sq.of(7, i) else Sq.of(0, i)
                val rankLabel = coords[i]
                val rtl = topLeftOf(rankSq)
                val rankDark = (Sq.file(rankSq) + Sq.rank(rankSq)) % 2 == 0
                drawText(rankLabel, if (rankDark) theme.light else theme.dark, rtl + Offset(2f * density.density, 1f * density.density))
                val fileSq = if (flipped) Sq.of(i, 7) else Sq.of(i, 0)
                val fileLabel = coords[8 + i]
                val ftl = topLeftOf(fileSq)
                val fileDark = (Sq.file(fileSq) + Sq.rank(fileSq)) % 2 == 0
                drawText(
                    fileLabel, if (fileDark) theme.light else theme.dark,
                    ftl + Offset(cell - fileLabel.size.width - 2f * density.density, cell - fileLabel.size.height - 1f * density.density),
                )
            }

            fun drawPiece(piece: Piece, center: Offset, alpha: Float = 1f) {
                val layout = glyphs[piece.type] ?: return
                val tl = center - Offset(layout.size.width / 2f, layout.size.height / 2f)
                val rot = if (piece.side == Side.WHITE) whiteRot.value else blackRot.value
                val (fill, outline) = if (piece.side == Side.WHITE) WhiteFill to WhiteOutline else BlackFill to BlackOutline
                rotate(rot, center) {
                    drawText(layout, outline, tl, alpha = alpha, drawStyle = outlineStroke)
                    drawText(layout, fill, tl, alpha = alpha, drawStyle = Fill)
                }
            }

            // Pieces (the sliding and the dragged ones are drawn on top afterwards).
            for (sq in 0 until 64) {
                val piece = position[sq] ?: continue
                if (sliding && (sq == slide!!.to || sq == slide.rookTo)) continue
                if (dragging != null && sq == dragging.from) continue
                drawPiece(piece, centerOf(sq))
            }
            // Move targets: small dot on empty squares, a shaded disc on captures.
            for (sq in targets) {
                val r = if (position[sq] != null) cell * 0.45f else cell * 0.15f
                drawCircle(TargetDot, r, centerOf(sq))
            }
            if (sliding) {
                val s = slide!!
                s.captured?.let { drawPiece(it, centerOf(s.capturedSq), alpha = 1f - p) }
                if (s.rook != null) drawPiece(s.rook, lerp(centerOf(s.rookFrom), centerOf(s.rookTo), p))
                drawPiece(s.piece, lerp(centerOf(s.from), centerOf(s.to), p))
            }
            if (badge != null && badgeText != null) {
                val tl = topLeftOf(badge.first)
                val r = cell * 0.18f
                val c = Offset(tl.x + cell - r - 1f, tl.y + r + 1f)
                drawCircle(Color(badge.second.color), r, c)
                drawText(badgeText, topLeft = c - Offset(badgeText.size.width / 2f, badgeText.size.height / 2f))
            }
            altArrow?.let { drawArrow(it, flipped, AltArrowColor) }
            arrow?.let { drawArrow(it, flipped, ArrowColor) }
            if (dragging != null) {
                val col = floor(dragging.at.x / cell).toInt()
                val row = floor(dragging.at.y / cell).toInt()
                if (col in 0..7 && row in 0..7) drawRect(HoverRing, Offset(col * cell, row * cell), cellSize, style = Stroke(width = cell * 0.06f))
                // Lift the piece above the finger so it stays visible.
                drawPiece(dragging.piece, dragging.at - Offset(0f, cell * 0.35f))
            }
        }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun DrawScope.drawArrow(m: Move, flipped: Boolean, color: Color) {
    val c = size.width / 8
    fun center(sq: Int): Offset {
        val col = if (flipped) 7 - Sq.file(sq) else Sq.file(sq)
        val row = if (flipped) Sq.rank(sq) else 7 - Sq.rank(sq)
        return Offset(col * c + c / 2, row * c + c / 2)
    }
    val a = center(m.from)
    val b = center(m.to)
    val ang = atan2(b.y - a.y, b.x - a.x)
    val head = c * 0.38f
    val tip = Offset(b.x - cos(ang) * head * 0.3f, b.y - sin(ang) * head * 0.3f)
    val shaftEnd = Offset(tip.x - cos(ang) * head * 0.8f, tip.y - sin(ang) * head * 0.8f)
    drawLine(color, a, shaftEnd, strokeWidth = c * 0.16f, cap = StrokeCap.Round)
    val p = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - cos(ang - 0.5f) * head, tip.y - sin(ang - 0.5f) * head)
        lineTo(tip.x - cos(ang + 0.5f) * head, tip.y - sin(ang + 0.5f) * head)
        close()
    }
    drawPath(p, color)
}

/** Horizontal evaluation bar: White's share grows from the left (from the right when flipped). [whiteChances] in [-1, 1]. */
@Composable
fun EvalBar(whiteChances: Double, flipped: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val whiteFrac = ((whiteChances + 1) / 2).toFloat().coerceIn(0.02f, 0.98f)
        drawRect(Color(0xFF303030))
        val w = size.width * whiteFrac
        val left = if (flipped) size.width - w else 0f
        drawRect(Color(0xFFF0F0F0), topLeft = Offset(left, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
    }
}
