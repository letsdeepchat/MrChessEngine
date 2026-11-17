package com.mrchess.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mrchess.core.puzzle.PuzzleRating
import com.mrchess.core.puzzle.PuzzleSession
import com.mrchess.core.rules.Side
import kotlin.math.roundToInt

@Composable
fun PuzzleScreen(st: PuzzleUiState, c: PuzzleController, menu: List<HeaderAction>) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ---- header: Elo estimate
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("GM Puzzles", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                Text(
                    "Your Elo ${st.stats.rating.roundToInt()}" + if (st.stats.played < PuzzleRating.PROVISIONAL) "?" else "",
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Column {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    (menu + HeaderAction("Reset puzzle rating") { confirmReset = true }).forEach { a ->
                        DropdownMenuItem(text = { Text(a.label) }, onClick = { menuOpen = false; a.onClick() })
                    }
                }
            }
        }
        val stats = st.stats
        Text(
            if (stats.played == 0) "Every puzzle here is rated 2500+ (grandmaster level). Your estimate starts at 1500 and moves with each first attempt."
            else "${PuzzleRating.title(stats.rating)} · solved ${stats.solved} of ${stats.played}" +
                if (stats.played < PuzzleRating.PROVISIONAL) " · provisional until ${PuzzleRating.PROVISIONAL} puzzles" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(6.dp))

        val ses = st.session
        if (ses == null) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@Column
        }
        // ---- status line
        val yourSide = ses.puzzle.solverSide
        val status = st.message ?: when (ses.state) {
            PuzzleSession.State.SETUP -> "Puzzle rated ${ses.puzzle.rating}: watch the opponent's move…"
            else -> "Puzzle rated ${ses.puzzle.rating} · ${yourSide.label} to move. Find the best move" +
                if (ses.puzzle.solverMoves > 1) " (${ses.puzzle.solverMoves} moves)." else "."
        }
        Surface(
            color = when (st.good) { true -> Color(0xFF2E7D32); false -> Color(0xFFC62828); null -> MaterialTheme.colorScheme.surfaceVariant },
            contentColor = if (st.good == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 44.dp),
        ) {
            Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
        }
        Spacer(Modifier.height(6.dp))

        // ---- board
        ChessBoard(
            position = ses.position,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            flipped = yourSide == Side.BLACK,
            lastMove = ses.lastMove,
            selected = st.selected ?: st.hintSquare,
            targets = st.targets,
            onTap = c::onSquareTap,
        )

        // ---- actions
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val playing = ses.state == PuzzleSession.State.YOUR_TURN || ses.state == PuzzleSession.State.OPPONENT_TURN
            OutlinedButton(onClick = c::hint, enabled = ses.state == PuzzleSession.State.YOUR_TURN && st.hintSquare == null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Lightbulb, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Hint", maxLines = 1)
            }
            OutlinedButton(onClick = c::showSolution, enabled = playing || ses.state == PuzzleSession.State.FAILED && ses.ply < ses.puzzle.moves.size, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Visibility, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Solution", maxLines = 1)
            }
            OutlinedButton(onClick = c::retry, enabled = ses.state != PuzzleSession.State.SETUP, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Replay, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Retry", maxLines = 1)
            }
        }
        val done = ses.finished
        if (done) {
            FilledTonalButton(onClick = c::next, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text("Next puzzle"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
            }
            Text(
                "Themes: " + ses.puzzle.themes.joinToString(", ") { theme -> theme.replace(Regex("([a-z])([A-Z0-9])"), "$1 $2").lowercase() },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            TextButton(onClick = c::next, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Skip to another puzzle") }
        }
        Text(
            "Puzzle ${ses.puzzle.id} from the Lichess puzzle database (CC0). Only your first try at each puzzle changes your Elo; hints and solutions count as a miss.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset puzzle rating?") },
        text = { Text("Your Elo estimate goes back to 1500 and all puzzles become new again.") },
        confirmButton = { Button(onClick = { confirmReset = false; c.resetRating() }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}
