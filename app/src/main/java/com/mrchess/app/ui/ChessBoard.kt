package com.mrchess.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrchess.core.review.MoveQuality
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import com.mrchess.core.rules.Sq
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val CheckTint = Color(0xAAE04040)
private val ArrowColor = Color(0xCC1E88E5)
private val AltArrowColor = Color(0x99FB8C00)

/**
 * Square board drawn with Unicode glyphs (no image assets needed). Tapping squares calls [onTap].
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
    theme: BoardTheme = LocalBoardTheme.current,
) {
    val Light = theme.light
    val Dark = theme.dark
    val LastMoveTint = theme.lastMove
    val SelectedTint = theme.selected
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f)) {
        val cell = maxWidth / 8
        val glyphSize = with(LocalDensity.current) { (cell * 0.82f).toSp() }
        val checkSq = if (position.inCheck()) position.kingSquare(position.sideToMove) else null
        Column(Modifier.fillMaxSize()) {
            for (row in 0 until 8) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (col in 0 until 8) {
                        val file = if (flipped) 7 - col else col
                        val rank = if (flipped) row else 7 - row
                        val sq = Sq.of(file, rank)
                        val base = if ((file + rank) % 2 == 0) Dark else Light
                        Box(
                            Modifier.weight(1f).fillMaxSize().background(base)
                                .then(if (onTap != null) Modifier.clickable { onTap(sq) } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (lastMove != null && (sq == lastMove.from || sq == lastMove.to))
                                Box(Modifier.fillMaxSize().background(LastMoveTint))
                            if (sq == selected) Box(Modifier.fillMaxSize().background(SelectedTint))
                            if (sq == checkSq) Box(Modifier.fillMaxSize().background(CheckTint, CircleShape))
                            position[sq]?.let { p ->
                                val fill = if (p.side == Side.WHITE) Color.White else Color(0xFF202020)
                                val outline = if (p.side == Side.WHITE) Color(0xFF202020) else Color(0xFF9E9E9E)
                                Text(
                                    p.glyph.toString() + "\uFE0E", // text (not emoji) presentation
                                    style = TextStyle(fontSize = glyphSize, color = outline, drawStyle = Stroke(width = 4f)),
                                )
                                Text(p.glyph.toString() + "\uFE0E", style = TextStyle(fontSize = glyphSize, color = fill))
                            }
                            if (sq in targets) {
                                val dot = if (position[sq] != null) cell * 0.9f else cell * 0.3f
                                Box(Modifier.size(dot).background(Color(0x55000000), CircleShape))
                            }
                            if (badge != null && badge.first == sq) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(1.dp).size(cell * 0.36f)
                                        .background(Color(badge.second.color), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(badge.second.symbol, color = Color.White, fontSize = with(LocalDensity.current) { (cell * 0.2f).toSp() }, fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                            // Coordinates on the edge squares.
                            if (col == 0) Text("${'1' + rank}", Modifier.align(Alignment.TopStart).padding(2.dp), fontSize = 9.sp, color = if (base == Light) Dark else Light)
                            if (row == 7) Text("${'a' + file}", Modifier.align(Alignment.BottomEnd).padding(2.dp), fontSize = 9.sp, color = if (base == Light) Dark else Light)
                        }
                    }
                }
            }
        }
        if (arrow != null || altArrow != null) {
            Canvas(Modifier.fillMaxSize()) {
                altArrow?.let { drawArrow(it, flipped, AltArrowColor) }
                arrow?.let { drawArrow(it, flipped, ArrowColor) }
            }
        }
    }
}

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
