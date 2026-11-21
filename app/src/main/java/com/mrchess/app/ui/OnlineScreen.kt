package com.mrchess.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrchess.core.online.RatingFilter
import com.mrchess.core.online.TimeControl
import com.mrchess.core.rules.Side
import kotlinx.coroutines.delay

/** "Play Bot | Play Online" at the top of the Play tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayModeToggle(online: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.height(34.dp).width(200.dp)) {
        SegmentedButton(selected = !online, onClick = { onChange(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Play Bot", maxLines = 1) }
        SegmentedButton(selected = online, onClick = { onChange(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Play Online", maxLines = 1) }
    }
}

@Composable
fun OnlineScreen(st: OnlineUiState, c: OnlineController, username: String, onPlayBot: () -> Unit, onSignIn: (() -> Unit)?) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            PlayModeToggle(online = true, onChange = { if (!it) onPlayBot() })
        }
        when (st.phase) {
            OnlinePhase.LOBBY, OnlinePhase.CONNECTING, OnlinePhase.SEARCHING -> Lobby(st, c, username, onSignIn)
            OnlinePhase.PLAYING, OnlinePhase.FINISHED -> LiveGame(st, c)
        }
    }
    st.pendingPromotion?.let { PromotionDialog(st.timeline.position.sideToMove, c::choosePromotion) }
    st.message?.let { m ->
        AlertDialog(onDismissRequest = c::clearMessage, confirmButton = { TextButton(onClick = c::clearMessage) { Text("OK") } }, text = { Text(m) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Lobby(st: OnlineUiState, c: OnlineController, username: String, onSignIn: (() -> Unit)?) {
    val searching = st.phase != OnlinePhase.LOBBY
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Play Online", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("@$username · Online rating ${st.rating}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!c.available) {
            Spacer(Modifier.height(12.dp))
            Text("Online play needs the cloud build (Firebase). This build is offline only.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Spacer(Modifier.height(16.dp))
        Text("Time control", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeControl.entries.forEach { tc -> FilterChip(st.timeControl == tc, { c.setTimeControl(tc) }, { Text(tc.label) }, enabled = !searching) }
        }
        Spacer(Modifier.height(12.dp))
        Text("Opponent rating", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RatingFilter.entries.forEach { f -> FilterChip(st.filter == f, { c.setFilter(f) }, { Text(f.label) }, enabled = !searching) }
        }
        val range = st.filter.range(st.rating)
        Text(
            if (st.filter.delta == null) "Anyone who is searching with the same time control." else "Opponents rated ${range.first}–${range.last}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (!searching) {
            Button(
                onClick = c::findMatch, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81B64C), contentColor = Color.White),
            ) { Text("Find Match", fontWeight = FontWeight.Bold) }
            if (onSignIn != null) TextButton(onClick = onSignIn) { Text("Sign in to keep your rating across phones") }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    val r = st.searchRange
                    Text(
                        when {
                            st.phase == OnlinePhase.CONNECTING -> "Connecting…"
                            r == null || st.filter.delta == null -> "Searching for opponent (${st.timeControl.label})…"
                            else -> "Searching for opponent (${r.first}–${r.last})…"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = c::cancelSearch) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun LiveGame(st: OnlineUiState, c: OnlineController) {
    val g = st.game
    val opp = st.opponentSide
    Column(Modifier.fillMaxSize()) {
        if (g == null) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Joining the game…")
            }
            return@Column
        }
        PlayerBar("@${g.nameOf(opp)}", g.ratingOf(opp), opp, st)
        OnlineMoveStrip(st)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val status = when {
                g.finished -> null
                g.moves.isEmpty() && st.mySide == Side.WHITE -> "You play White. Make the first move."
                g.moves.isEmpty() -> "You play Black. Waiting for White's first move…"
                st.myTurn -> "Your move"
                st.sending -> "Sending…"
                else -> "Opponent is thinking…"
            }
            status?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        }
        ChessBoard(
            position = st.timeline.position,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            flipped = st.mySide == Side.BLACK,
            lastMove = st.timeline.currentNode?.move,
            selected = st.selected,
            targets = st.targets,
            onTap = c::onSquareTap,
        )
        PlayerBar("@you", g.ratingOf(st.mySide), st.mySide, st)
        if (st.phase == OnlinePhase.FINISHED) FinishedBar(st, c) else GameButtons(st, c)
    }
}

@Composable
private fun PlayerBar(name: String, rating: Int, side: Side, st: OnlineUiState) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = if (side == Side.WHITE) Color(0xFFF2F2F2) else Color(0xFF2B2B2B), shape = RoundedCornerShape(4.dp), modifier = Modifier.size(14.dp)) {}
        Spacer(Modifier.width(8.dp))
        Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text("  ($rating)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Clock(st, side)
    }
}

/** A clock with its own ticker, so only this text recomposes ten times a second. */
@Composable
private fun Clock(st: OnlineUiState, side: Side) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val running = st.game?.let { !it.finished && it.sideToMove == side && st.turnStartedAt != null } == true
    LaunchedEffect(running, st.turnStartedAt) {
        now = SystemClock.elapsedRealtime()
        while (running) { delay(100); now = SystemClock.elapsedRealtime() }
    }
    val ms = st.clockMs(side, now)
    val low = ms < 20_000
    Surface(
        color = when { running && low -> Color(0xFFCA3431); running -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceVariant },
        contentColor = if (running) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(formatClock(ms), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp).padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1)
    }
}

private fun formatClock(ms: Long): String {
    val totalTenths = ms / 100
    val sec = totalTenths / 10
    return if (sec < 20) "%d:%02d.%d".format(sec / 60, sec % 60, totalTenths % 10) else "%d:%02d".format(sec / 60, sec % 60)
}

@Composable
private fun OnlineMoveStrip(st: OnlineUiState) {
    val t = st.timeline
    val list = rememberLazyListState()
    LaunchedEffect(t.nodes.size) { if (t.nodes.isNotEmpty()) list.animateScrollToItem(t.nodes.lastIndex) }
    LazyRow(
        state = list, modifier = Modifier.fillMaxWidth().height(32.dp),
        contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(t.nodes, key = { i, n -> "$i:${n.uci}" }) { i, _ ->
            Text(t.moveLabel(i), style = MaterialTheme.typography.labelLarge, fontWeight = if (i == t.nodes.lastIndex) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun GameButtons(st: OnlineUiState, c: OnlineController) {
    var confirmResign by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            st.drawOfferedToMe -> {
                Button(onClick = c::acceptDraw, modifier = Modifier.weight(1f)) { Text("Accept draw") }
                OutlinedButton(onClick = c::declineDraw, modifier = Modifier.weight(1f)) { Text("Decline") }
            }
            st.canAbort -> OutlinedButton(onClick = c::abort, modifier = Modifier.weight(1f)) { Text("Abort") }
            else -> {
                OutlinedButton(onClick = { confirmResign = true }, modifier = Modifier.weight(1f)) { Text("Resign") }
                OutlinedButton(onClick = c::offerDraw, enabled = !st.drawOfferedByMe, modifier = Modifier.weight(1f)) {
                    Text(if (st.drawOfferedByMe) "Draw offered" else "Offer draw")
                }
            }
        }
    }
    if (confirmResign) AlertDialog(
        onDismissRequest = { confirmResign = false },
        title = { Text("Resign this game?") },
        confirmButton = { Button(onClick = { confirmResign = false; c.resign() }) { Text("Resign") } },
        dismissButton = { TextButton(onClick = { confirmResign = false }) { Text("Cancel") } },
    )
}

@Composable
private fun FinishedBar(st: OnlineUiState, c: OnlineController) {
    val g = st.game ?: return
    val result = g.result ?: "*"
    val won = (result == "1-0" && st.mySide == Side.WHITE) || (result == "0-1" && st.mySide == Side.BLACK)
    val title = when {
        result == "*" -> "Game aborted"
        result == "1/2-1/2" -> "Draw"
        won -> "You won"
        else -> "You lost"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            g.termination?.let { Text("  · $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                ?: Spacer(Modifier.weight(1f))
            st.ratingChange?.let { d ->
                Text("${st.rating} (${if (d >= 0) "+" else "−"}${kotlin.math.abs(d)})", fontWeight = FontWeight.Bold, color = if (d >= 0) Color(0xFF81B64C) else Color(0xFFCA3431))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = c::review, enabled = st.timeline.nodes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Game Review") }
            OutlinedButton(onClick = { c.leaveGame(); c.findMatch() }, modifier = Modifier.weight(1f)) { Text("New match") }
            TextButton(onClick = c::leaveGame) { Text("Lobby") }
        }
    }
}
