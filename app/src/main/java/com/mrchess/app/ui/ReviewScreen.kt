package com.mrchess.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mrchess.core.review.MoveClass
import com.mrchess.core.rules.Side

@Composable
fun ReviewScreen(vm: MainViewModel, s: ReviewState) {
    val game = s.game
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (game == null) {
            Text("Your games", style = MaterialTheme.typography.titleMedium)
            if (s.saved.isEmpty()) Text("Finished games against the engine appear here for review.", style = MaterialTheme.typography.bodySmall)
            s.saved.forEach { g ->
                Row(Modifier.fillMaxWidth().clickable { vm.reviewSaved(g) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.title, fontWeight = FontWeight.SemiBold)
                        Text("${g.date}  ${g.result}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.deleteSaved(g) }) { Text("Delete") }
                }
                HorizontalDivider()
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Game review", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::closeReview) { Text("Back") }
        }
        s.progress?.let { (done, total) ->
            Text("Analysing position $done of $total…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { done.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val review = s.review ?: return@Column

        val ply = s.selectedPly
        val pos = if (ply == null) game.current else game.positions[ply]
        val rm = ply?.let { review.moves.getOrNull(it) }
        // Selected move: show the position *before* it, the move played (orange) and the engine's choice (blue).
        ChessBoard(
            position = pos,
            lastMove = if (ply == null) game.moves.lastOrNull() else game.moves.getOrNull(ply - 1),
            arrow = rm?.best?.move,
            altArrow = ply?.let { game.moves[it] },
            flipped = game.tags["Black"] == "You",
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (side in Side.entries) {
                Column {
                    Text("${side.label}: ${"%.1f".format(if (side == Side.WHITE) review.accuracyWhite else review.accuracyBlack)}% accuracy", fontWeight = FontWeight.Bold)
                    Text(
                        "${review.count(side, MoveClass.INACCURACY)} inaccuracies · ${review.count(side, MoveClass.MISTAKE)} mistakes · ${review.count(side, MoveClass.BLUNDER)} blunders",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        rm?.let { m ->
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${moveNumber(m.ply, game.positions.first().sideToMove, game.positions.first().fullmoveNumber)} ${m.san}  ${m.classification.symbol}  ${m.classification.label}", style = MaterialTheme.typography.titleSmall)
                    Text("Eval ${m.evalBefore.display()} → ${m.evalAfter.display()}  (win% −${"%.1f".format(m.winPercentLoss)})", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(m.comment)
                    m.best?.let { b ->
                        Spacer(Modifier.height(6.dp))
                        Text("Better: ${b.san}", fontWeight = FontWeight.SemiBold)
                        b.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Text("Line: ${b.lineText(pos, 3)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { vm.startAnalysis(game.copy().undo(game.ply - m.ply)) }) { Text("Analyze here") }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        review.moves.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { m ->
                    Row(
                        Modifier.weight(1f).clickable { vm.selectReviewPly(m.ply) }
                            .background(if (m.ply == ply) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(6.dp),
                    ) {
                        Text(moveNumber(m.ply, game.positions.first().sideToMove, game.positions.first().fullmoveNumber), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(4.dp))
                        Text("${m.san}${m.classification.symbol}", color = classColor(m.classification), fontWeight = FontWeight.Medium)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun moveNumber(ply: Int, startSide: Side, startNo: Int): String {
    val offset = if (startSide == Side.WHITE) ply else ply + 1
    val no = startNo + offset / 2
    return if (offset % 2 == 0) "$no." else "$no…"
}

@Composable
private fun classColor(c: MoveClass): Color = when (c) {
    MoveClass.BLUNDER -> Color(0xFFD32F2F)
    MoveClass.MISTAKE -> Color(0xFFF57C00)
    MoveClass.INACCURACY -> Color(0xFFFBC02D)
    MoveClass.BEST, MoveClass.EXCELLENT -> Color(0xFF388E3C)
    else -> MaterialTheme.colorScheme.onSurface
}
