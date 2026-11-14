package com.mrchess.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mrchess.core.engine.EngineStrength
import com.mrchess.core.explain.Explanation
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Pgn
import com.mrchess.core.rules.PieceType
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlin.math.roundToInt

@Composable
fun PlayScreen(vm: MainViewModel, s: BoardState) {
    var showNewGame by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.mode == Mode.PLAY) "vs ${eloLabel(s.elo)}" else "Analysis board",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            if (s.engineThinking) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp)); Text("Engine thinking…", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        val hint = s.hint?.takeIf { s.hintPosFen == s.position.toFen() }
        EvalBar(hint?.scoreWhite?.winningChances ?: 0.0, s.flipped, Modifier.fillMaxWidth().height(10.dp))
        Spacer(Modifier.height(4.dp))
        ChessBoard(
            position = s.position,
            flipped = s.flipped,
            lastMove = s.lastMove,
            selected = s.selected,
            targets = s.targets,
            arrow = hint?.move,
            onTap = vm::onSquareTap,
        )
        s.status?.let { Text(it, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::requestHint, enabled = !s.isOver && !s.engineThinking && s.userToMove) { Text("Best move + why") }
            OutlinedButton(onClick = vm::undo) { Text("Undo") }
            OutlinedButton(onClick = vm::flip) { Text("Flip") }
            OutlinedButton(onClick = { showNewGame = true }) { Text("New game") }
            if (s.mode == Mode.PLAY && !s.isOver) OutlinedButton(onClick = vm::resign) { Text("Resign") }
            if (s.game.ply > 0) OutlinedButton(onClick = vm::reviewCurrentGame) { Text("Review") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = s.mode == Mode.ANALYSIS, onClick = vm::toggleMode, label = { Text("Analysis mode") })
            Spacer(Modifier.width(12.dp))
            Text("Live coach", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            Switch(checked = s.autoAnalyze, onCheckedChange = { vm.toggleAutoAnalyze() })
        }

        when {
            s.hintLoading -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text("Stockfish is analysing at full strength…")
            }
            s.hint != null && s.hintPosFen == s.position.toFen() ->
                HintCard(s.hint, s.position, onPlay = { vm.playUci(it.uci) })
        }

        if (s.game.ply > 0) {
            Spacer(Modifier.height(8.dp))
            Text(Pgn.movetext(s.game), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showNewGame) NewGameDialog(s.elo, onDismiss = { showNewGame = false }) { side, elo ->
        showNewGame = false
        vm.newGame(side, elo)
    }
    s.pendingPromotion?.let { PromotionDialog(s.position.sideToMove, vm::choosePromotion) }
    s.error?.let { err ->
        AlertDialog(onDismissRequest = vm::clearError, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }, text = { Text(err) })
    }
}

fun eloLabel(elo: Int) = when {
    elo >= 3200 -> "Stockfish full strength (3200+)"
    elo < 950 -> "Beginner bot (~800)"
    elo < EngineStrength.MIN_ELO -> "Casual bot (~1100)"
    else -> "Stockfish $elo"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HintCard(e: Explanation, pos: Position, onPlay: (Move) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(e.scoreWhite.display(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                e.tags.forEach { t ->
                    AssistChip(onClick = {}, label = { Text(t.label) })
                }
            }
            Spacer(Modifier.height(4.dp))
            e.reasons.forEach { r -> Text("• $r", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            Spacer(Modifier.height(6.dp))
            Text("Line: ${e.lineText(pos, 3)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            e.alternative?.let { a ->
                Text(
                    "2nd best: ${a.san} (${a.score.let { if (pos.sideToMove == Side.WHITE) it else it.negate() }.display()}), " +
                        if (a.deltaCp > 0) "${"%.2f".format(a.deltaCp / 100.0)} worse" else "equal",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(onClick = { onPlay(e.move) }) { Text("Play ${e.san}") }
        }
    }
}

@Composable
fun NewGameDialog(currentElo: Int, onDismiss: () -> Unit, onStart: (Side, Int) -> Unit) {
    var elo by remember { mutableIntStateOf(currentElo) }
    var side by remember { mutableStateOf(Side.WHITE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New game") },
        text = {
            Column {
                Text("Opponent: ${eloLabel(elo)}")
                Slider(
                    value = elo.toFloat(), onValueChange = { elo = (it / 50).roundToInt() * 50 },
                    valueRange = 800f..3200f, steps = (3200 - 800) / 50 - 1,
                )
                Text("Slide fully right for unlimited Stockfish (3200+).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(side == Side.WHITE, { side = Side.WHITE }, { Text("Play White") })
                    FilterChip(side == Side.BLACK, { side = Side.BLACK }, { Text("Play Black") })
                }
            }
        },
        confirmButton = { Button(onClick = { onStart(side, elo) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PromotionDialog(side: Side, onChoose: (PieceType?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text("Promote to") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT).forEach { t ->
                    OutlinedButton(onClick = { onChoose(t) }) {
                        Text(com.mrchess.core.rules.Piece(t, side).glyph.toString() + "︎", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        },
        confirmButton = {},
    )
}
