package com.mrchess.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mrchess.app.audio.ChessVoiceNarrator
import com.mrchess.core.engine.EngineStrength
import com.mrchess.core.explain.Explanation
import com.mrchess.core.review.GamePhase
import com.mrchess.core.review.MoveQuality
import com.mrchess.core.rules.Move
import com.mrchess.core.rules.Piece
import com.mrchess.core.rules.PieceType
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlin.math.abs
import kotlin.math.roundToInt

private val CardBg = Color(0xF01E1B2E) // #1E1B2E at 94 %
private val CardBorder = Color(0xFF3D3654)
private val CardText = Color(0xFFECE9F5)
private val CardMuted = Color(0xFFB4ADC9)

private val HeaderH = 48.dp
private val StripH = 40.dp
private val ActionH = 48.dp
private val ModeH = 44.dp
private val GameBarH = 52.dp

/** ⋮ menu entries (customisation only: themes, sounds, voice). */
data class HeaderAction(val label: String, val onClick: () -> Unit)

/** A one-tap icon + label button in the bar under the board. */
data class BarAction(val icon: ImageVector, val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** Colour choice in the New Game dialogs. [id] is persisted. */
enum class SideChoice(val id: String, val label: String) {
    WHITE("white", "Play White"), BLACK("black", "Play Black"), RANDOM("random", "Random");

    fun resolve(): Side = when (this) {
        WHITE -> Side.WHITE
        BLACK -> Side.BLACK
        RANDOM -> if (kotlin.random.Random.nextBoolean()) Side.WHITE else Side.BLACK
    }

    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: WHITE }
}

/** The Stockfish New Game defaults the player saved ([saved] = false: nothing saved yet). */
data class BotDefaults(val elo: Int = 1500, val color: SideChoice = SideChoice.WHITE, val saved: Boolean = false)

/** Everything a board screen needs from outside its own session. */
data class WorkspaceHooks(
    /** Opens this game in the Review tab ([fromMove1]: step through from move 1). */
    val onReview: (fromMove1: Boolean) -> Unit = {},
    /** Import: start a new import. Review: back to the saved games. */
    val onClose: () -> Unit = {},
    /** "+ New Game" from Import/Review: go to Play and open its New Game dialog. */
    val onNewGameElsewhere: () -> Unit = {},
    val botDefaults: BotDefaults = BotDefaults(),
    val onBotGameStarted: (elo: Int, color: SideChoice, saveAsDefault: Boolean) -> Unit = { _, _, _ -> },
    /** Open the New Game dialog now (requested from another tab). */
    val openNewGame: Boolean = false,
    val onNewGameOpened: () -> Unit = {},
    val onShare: (String) -> Unit = {},
)

/**
 * The one board screen used by Play, Two Player, Import and Review. The vertical layout is
 * rigid: header, move strip, a flexible slack zone, the board, then two fixed bars. Pop-ups float
 * in an overlay layer, so the board never moves or resizes.
 */
@Composable
fun UnifiedChessWorkspace(
    st: WorkspaceState,
    c: WorkspaceController,
    narrator: ChessVoiceNarrator,
    speaking: Boolean,
    menu: List<HeaderAction>,
    hooks: WorkspaceHooks = WorkspaceHooks(),
) {
    var showNewGame by remember { mutableStateOf(false) }
    var showNewGame2p by remember { mutableStateOf(false) }
    var confirmResign by remember { mutableStateOf(false) }
    LaunchedEffect(hooks.openNewGame) {
        if (hooks.openNewGame) {
            if (st.mode == SessionMode.PLAY_AI) showNewGame = true else if (st.mode == SessionMode.TWO_PLAYER) showNewGame2p = true
            hooks.onNewGameOpened()
        }
    }
    val onReview = { hooks.onReview(false) }
    val onClose = hooks.onClose
    val newGame: () -> Unit = {
        when (st.mode) {
            SessionMode.PLAY_AI -> showNewGame = true
            SessionMode.TWO_PLAYER -> showNewGame2p = true
            else -> hooks.onNewGameElsewhere()
        }
        Unit
    }
    val hasMoves = st.timeline.nodes.isNotEmpty()
    val actions = when (st.mode) {
        SessionMode.PLAY_AI, SessionMode.TWO_PLAYER -> listOf(
            BarAction(Icons.Filled.Add, "New Game") { newGame() },
            if (st.gameOver) BarAction(Icons.Filled.RestartAlt, "Restart") { c.restart() }
            else BarAction(Icons.Filled.Flag, "Resign", enabled = hasMoves) { confirmResign = true },
            BarAction(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = hasMoves) { c.undo() },
            BarAction(Icons.Filled.QueryStats, "Review", enabled = hasMoves, onClick = onReview),
        )
        SessionMode.IMPORT_ANALYSIS -> listOf(
            BarAction(Icons.Filled.Add, "New import", onClick = onClose),
            BarAction(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = hasMoves) { c.undo() },
            BarAction(Icons.Filled.QueryStats, "Review", enabled = hasMoves, onClick = onReview),
            BarAction(Icons.Filled.Assessment, "Summary", onClick = c::openSummary),
        )
        SessionMode.POST_GAME_REVIEW -> listOf(
            BarAction(Icons.AutoMirrored.Filled.ArrowBack, "Games", onClick = onClose),
            BarAction(Icons.Filled.Assessment, "Summary", onClick = c::openSummary),
            BarAction(Icons.Filled.Lightbulb, "Best move", enabled = !st.gameOver, onClick = c::requestHint),
            BarAction(Icons.Filled.SwapVert, "Flip", onClick = c::flip),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boardSize = maxWidth
        val slack = maxHeight - HeaderH - StripH - boardSize - ActionH - GameBarH - ModeH
        Column(Modifier.fillMaxSize()) {
            Header(st, c, menu)
            MoveStrip(st, c)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val hint = st.currentHint
                EvalBar(
                    (if (st.inspecting) st.timeline.nodes[st.insightIndex!!].annotation?.evalBeforeWhite else hint?.scoreWhite
                        ?: st.timeline.currentNode?.evalScore)?.winningChances ?: 0.0,
                    st.flipped, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp),
                )
                Row(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        st.engineThinking -> { Spinner(); Text(" Engine thinking…", style = MaterialTheme.typography.labelMedium) }
                        st.hintLoading -> { Spinner(); Text(" Coach is analysing…", style = MaterialTheme.typography.labelMedium) }
                        st.status != null && !st.tabletop -> Text(st.status, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
                if (st.tabletop) TabletopTurn(st, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                val hasCard = hint != null || st.inspecting
                if (!st.cardOpen && hasCard) {
                    Surface(
                        onClick = c::showCard, shape = RoundedCornerShape(50), color = CardBg, border = BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 10.dp),
                    ) { Text("Show coach", color = CardText, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
                }
            }
            val badgeNode = st.timeline.currentNode
            val inspectNode = if (st.inspecting) st.timeline.nodes[st.insightIndex!!] else null
            ChessBoard(
                position = st.position,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                flipped = st.flipped,
                lastMove = badgeNode?.move,
                selected = st.selected,
                targets = st.targets,
                arrow = inspectNode?.annotation?.bestMoveUci?.let { runCatching { Move.fromUci(it) }.getOrNull() }
                    ?: st.currentHint?.takeIf { st.cardOpen || st.liveCoach }?.move,
                altArrow = inspectNode?.move,
                badge = badgeNode?.let { n -> n.moveQualityBadge?.let { q -> n.move.to to q } },
                onTap = c::onSquareTap,
                whiteRotation = st.whitePieceRotation,
                blackRotation = st.blackPieceRotation,
            )
            ActionBar(st, c)
            GameActionBar(actions)
            ModeBar(st, c)
        }

        // ---- overlay layer (never affects layout) ----
        if (st.cardOpen) {
            val maxCard = maxOf(slack - 12.dp, 220.dp)
            Box(Modifier.fillMaxWidth().padding(top = HeaderH + StripH + 6.dp, start = 10.dp, end = 10.dp)) {
                CoachCard(st, c, narrator, speaking, Modifier.heightIn(max = maxCard))
            }
        }
    }

    if (st.showSummary) SummaryDialog(
        st, c,
        onShare = { hooks.onShare(shareText(st)) },
        onNewGame = { c.closeSummary(); newGame() },
        onStartReview = {
            if (st.mode == SessionMode.PLAY_AI || st.mode == SessionMode.TWO_PLAYER) { c.closeSummary(); hooks.onReview(true) }
            else c.startReview()
        },
    )
    if (confirmResign) AlertDialog(
        onDismissRequest = { confirmResign = false },
        title = { Text(if (st.mode == SessionMode.PLAY_AI) "Resign this game?" else "${st.position.sideToMove.label} resigns?") },
        confirmButton = { Button(onClick = { confirmResign = false; c.resign() }) { Text("Resign") } },
        dismissButton = { TextButton(onClick = { confirmResign = false }) { Text("Cancel") } },
    )
    if (showNewGame) {
        val d = hooks.botDefaults
        NewGameDialog(
            initialElo = if (d.saved) d.elo else st.elo,
            initialColor = if (d.saved) d.color else if (st.userSide == Side.BLACK) SideChoice.BLACK else SideChoice.WHITE,
            onDismiss = { showNewGame = false },
        ) { choice, elo, save ->
            showNewGame = false
            c.newGame(choice.resolve(), elo)
            hooks.onBotGameStarted(elo, choice, save)
        }
    }
    if (showNewGame2p) TwoPlayerNewGameDialog(
        current = if (st.player1Side == Side.BLACK) SideChoice.BLACK else SideChoice.WHITE,
        onDismiss = { showNewGame2p = false },
    ) { choice -> showNewGame2p = false; c.newTwoPlayerGame(choice.resolve()) }
    st.pendingPromotion?.let { PromotionDialog(st.position.sideToMove, c::choosePromotion) }
    st.error?.let { err -> AlertDialog(onDismissRequest = c::clearError, confirmButton = { TextButton(onClick = c::clearError) { Text("OK") } }, text = { Text(err) }) }
}

/** Turn label at the board's top edge; upside down on Player 2's turn so the opposite player can read it. */
@Composable
private fun TabletopTurn(st: WorkspaceState, modifier: Modifier) {
    val black = st.position.sideToMove == Side.BLACK
    val player = if (st.topPlayerToMove) "Player 2" else "Player 1"
    val text = when {
        st.gameOver -> st.status ?: "Game over"
        else -> "$player (${st.position.sideToMove.label}) to move"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = if (black) Color(0xFF202020) else Color(0xFFF2F2F2),
        border = BorderStroke(1.dp, Color(0xFF9E9E9E)),
        modifier = modifier.graphicsLayer { rotationZ = if (st.topPlayerToMove && !st.gameOver) 180f else 0f },
    ) {
        Text(text, color = if (black) Color.White else Color(0xFF202020), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}

@Composable
private fun Spinner() = CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)

// ---------------------------------------------------------------------------- header & strip

@Composable
private fun Header(st: WorkspaceState, c: WorkspaceController, menu: List<HeaderAction>) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(HeaderH).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(st.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (st.mode == SessionMode.TWO_PLAYER) {
            // Mode selector pill: shows the current seating mode, opens the list of all three.
            var pick by remember { mutableStateOf(false) }
            Box {
                Surface(
                    onClick = { pick = true }, shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(end = 6.dp).height(32.dp),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ScreenRotation, null, Modifier.size(16.dp))
                        Text(" " + st.twoPlayerView.short, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp))
                    }
                }
                DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                    TwoPlayerView.entries.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v.label, fontWeight = if (v == st.twoPlayerView) FontWeight.Bold else FontWeight.Normal) },
                            leadingIcon = { if (v == st.twoPlayerView) Icon(Icons.Filled.Check, null) },
                            onClick = { pick = false; c.setTwoPlayerView(v) },
                        )
                    }
                }
            }
        } else {
            IconButton(onClick = c::rotateButton) { Icon(Icons.Filled.ScreenRotation, "Rotate board") }
        }
        val sum = st.summary
        FilledTonalButton(onClick = c::openSummary, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(32.dp)) {
            if (st.annotating) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(6.dp)) }
            val errors = Side.entries.sumOf { s -> listOf(MoveQuality.MISTAKE, MoveQuality.BLUNDER).sumOf { sum.count(s, it) } }
            Text(if (errors > 0) "Summary · $errors" else "Summary", style = MaterialTheme.typography.labelMedium)
        }
        if (menu.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { menuOpen = false; a.onClick() }) }
                }
            }
        } else Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun MoveStrip(st: WorkspaceState, c: WorkspaceController) {
    val t = st.timeline
    val list = rememberLazyListState()
    LaunchedEffect(t.currentPly, t.nodes.size) {
        if (t.nodes.isNotEmpty()) list.animateScrollToItem((t.currentPly - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().height(StripH),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.nodes.isEmpty()) item { Text("No moves yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(t.nodes) { i, node ->
            val active = i == t.currentPly - 1
            Surface(
                onClick = { c.goTo(i + 1) },
                shape = RoundedCornerShape(8.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.height(30.dp),
            ) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.moveLabel(i), style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                    node.moveQualityBadge?.takeIf { it != MoveQuality.GOOD && it != MoveQuality.FORCED }?.let { q ->
                        Text(" ${q.symbol}", color = Color(q.color), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- bars

@Composable
private fun ActionBar(st: WorkspaceState, c: WorkspaceController) {
    val t = st.timeline
    Row(Modifier.fillMaxWidth().height(ActionH).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = c::requestHint, enabled = !st.gameOver, contentPadding = PaddingValues(horizontal = 10.dp),
            modifier = Modifier.weight(1f).height(40.dp),
        ) {
            Icon(Icons.Filled.Lightbulb, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
            Text("Best move + why", maxLines = 1, fontSize = 13.sp)
        }
        NavButton(Icons.Filled.FirstPage, "Start", t.canBack, c::toStart)
        NavButton(Icons.Filled.ChevronLeft, "Back", t.canBack, c::back)
        NavButton(Icons.Filled.ChevronRight, "Next", t.canForward, c::forward)
        NavButton(Icons.Filled.LastPage, "Latest", t.canForward, c::toEnd)
        NavButton(Icons.Filled.SwapVert, "Flip board", true, c::flip)
    }
}

@Composable
private fun GameActionBar(actions: List<BarAction>) {
    Row(
        Modifier.fillMaxWidth().height(GameBarH).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { a ->
            Column(
                Modifier.weight(1f).padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = a.enabled, onClick = a.onClick)
                    .alpha(if (a.enabled) 1f else 0.38f).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(a.icon, null, Modifier.size(22.dp))
                Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp).alpha(if (enabled) 1f else 0.38f)) {
        Icon(icon, label)
    }
}

@Composable
private fun ModeBar(st: WorkspaceState, c: WorkspaceController) {
    Row(Modifier.fillMaxWidth().height(ModeH).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        when (st.mode) {
            SessionMode.PLAY_AI -> FilterChip(selected = st.analysisMode, onClick = c::toggleAnalysisMode, label = { Text("Analysis mode") })
            SessionMode.TWO_PLAYER -> Text(
                "${st.position.sideToMove.label} to move", style = MaterialTheme.typography.labelLarge,
            )
            else -> Text("Analysis board", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.weight(1f))
        Text("Live coach", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Switch(checked = st.liveCoach, onCheckedChange = { c.toggleLiveCoach() })
    }
}

// ---------------------------------------------------------------------------- coach card

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoachCard(st: WorkspaceState, c: WorkspaceController, narrator: ChessVoiceNarrator, speaking: Boolean, modifier: Modifier) {
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    val inspectIdx = st.insightIndex?.takeIf { st.inspecting }
    val node = inspectIdx?.let { st.timeline.nodes[it] }
    val hint = st.currentHint
    Surface(
        color = CardBg, contentColor = CardText, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardBorder),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .alpha((1f - (abs(dx) / 600f) - (dy / 500f)).coerceIn(0.3f, 1f))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (abs(dx) > 160f) c.dismissCard(); dx = 0f },
                    onDragCancel = { dx = 0f },
                ) { _, d -> dx += d }
            },
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 10.dp)) {
            // Header doubles as a swipe-down handle.
            Row(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (dy > 120f) c.dismissCard(); dy = 0f },
                        onDragCancel = { dy = 0f },
                    ) { _, d -> dy = (dy + d).coerceAtLeast(0f) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val title = when {
                    node != null -> "${node.san} — ${node.moveQualityBadge?.label ?: "analysing…"}"
                    hint != null -> hint.headline
                    st.hintLoading -> "Analysing…"
                    else -> "Coach"
                }
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2)
                val evalText = node?.annotation?.evalBeforeWhite?.display() ?: hint?.scoreWhite?.display()
                evalText?.let {
                    Surface(color = Color(0xFF3D3654), shape = RoundedCornerShape(6.dp)) {
                        Text(it, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge, color = CardText)
                    }
                }
                val script = when {
                    node?.annotation != null -> ChessVoiceNarrator.script(node.annotation!!, node.san)
                    hint != null -> ChessVoiceNarrator.script(hint, st.position)
                    else -> emptyList()
                }
                IconButton(onClick = { narrator.toggle(script) }, enabled = script.isNotEmpty()) {
                    Icon(if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp, if (speaking) "Stop reading" else "Read aloud", tint = CardText)
                }
                IconButton(onClick = { narrator.stop(); c.dismissCard() }) { Icon(Icons.Filled.Close, "Hide coach", tint = CardText) }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(end = 8.dp)) {
                when {
                    node != null -> InsightBody(node.annotation, node.san)
                    hint != null -> HintBody(hint, st.position, onPlay = { c.playUci(it.uci) }, canPlay = st.canMovePieces)
                    st.hintLoading -> Text("Stockfish is analysing at full strength…", color = CardMuted)
                    else -> Text("Tap “Best move + why” for the engine’s move and the reasons behind it.", color = CardMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HintBody(e: Explanation, pos: Position, onPlay: (Move) -> Unit, canPlay: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        e.tags.forEach { t -> TagChip(t.label) }
    }
    Spacer(Modifier.height(6.dp))
    e.reasons.filterNot { it.startsWith("Expected continuation") || it.contains("about as good") }.take(3).forEach {
        Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
    }
    Spacer(Modifier.height(6.dp))
    if (e.line.size > 1) Text("Expected continuation: ${e.lineText(pos, 3)}", style = MaterialTheme.typography.bodySmall, color = CardMuted)
    e.alternative?.let { a ->
        val s = if (pos.sideToMove == Side.WHITE) a.score else a.score.negate()
        Text(
            "2nd best: ${a.san} (${s.display()}), " + if (a.deltaCp > 0) "${"%.2f".format(a.deltaCp / 100.0)} worse" else "equal",
            style = MaterialTheme.typography.bodySmall, color = CardMuted,
        )
    }
    if (canPlay) TextButton(onClick = { onPlay(e.move) }, contentPadding = PaddingValues(0.dp)) { Text("Play ${e.san}", color = Color(0xFF9FC5FF)) }
}

@Composable
private fun InsightBody(a: com.mrchess.core.review.MoveAnnotation?, san: String) {
    if (a == null) { Text("Classifying $san…", color = CardMuted); return }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Color(a.quality.color), shape = RoundedCornerShape(6.dp)) {
            Text("${a.quality.symbol} ${a.quality.label}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("${a.evalBeforeWhite.display()} → ${a.evalAfterWhite.display()}", color = CardMuted, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(6.dp))
    Text(a.rationale, style = MaterialTheme.typography.bodyMedium)
    a.bestExplanation?.let { b ->
        Spacer(Modifier.height(6.dp))
        Text("Blue arrow: ${b.san} (best). Orange arrow: $san (played).", style = MaterialTheme.typography.bodySmall, color = CardMuted)
        if (b.line.size > 1) Text("Best line: ${b.line.take(4).joinToString(" ")}", style = MaterialTheme.typography.bodySmall, color = CardMuted)
    }
}

@Composable
private fun TagChip(text: String) {
    Surface(color = Color(0xFF2E2A42), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, CardBorder)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = CardText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

// ---------------------------------------------------------------------------- summary

@Composable
private fun SummaryDialog(st: WorkspaceState, c: WorkspaceController, onShare: () -> Unit, onNewGame: () -> Unit, onStartReview: () -> Unit) {
    val sum = st.summary
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    Dialog(onDismissRequest = c::closeSummary, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = CardBg, contentColor = CardText, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CardBorder), shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = maxH),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Game Review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = c::closeSummary) { Icon(Icons.Filled.Close, "Close", tint = CardText) }
                }
                if (sum.total == 0) { Text("Play some moves first.", color = CardMuted); return@Column }
                if (sum.annotated < sum.total) Text("Analysed ${sum.annotated} of ${sum.total} moves…", color = CardMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row {
                    Spacer(Modifier.weight(1.4f))
                    for (side in Side.entries) Text(st.timeline.tags[side.label] ?: side.label, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Accuracy", Modifier.weight(1.4f), color = CardMuted)
                    for (side in Side.entries) RatingPill(sum.accuracy(side)?.let { "%.1f".format(it) } ?: "–", side, Modifier.weight(1f))
                }
                for (q in MoveQuality.SUMMARY) {
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
                            QualityDot(q, 18.dp)
                            Text("  ${q.label}")
                        }
                        for (side in Side.entries) {
                            val n = sum.count(side, q)
                            val idx = sum.firstIndex[side]?.get(q)
                            Text(
                                n.toString(),
                                Modifier.weight(1f).then(if (n > 0 && idx != null) Modifier.clickable { c.inspect(idx) } else Modifier),
                                color = if (n > 0) Color(q.color) else CardMuted,
                                fontWeight = if (n > 0) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = CardBorder)
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Game Rating", Modifier.weight(1.4f), fontWeight = FontWeight.SemiBold)
                    for (side in Side.entries) RatingPill(sum.gameRating(side, ratingBase(st.timeline, side))?.toString() ?: "–", side, Modifier.weight(1f))
                }
                for (p in GamePhase.entries) {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.label, Modifier.weight(1.4f), color = CardMuted)
                        for (side in Side.entries) {
                            val ph = sum.phase(side, p)
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                if (ph == null) Text("—", color = CardMuted)
                                else { Text("%.1f%% ".format(ph.accuracy)); QualityDot(ph.badge, 16.dp) }
                            }
                        }
                    }
                }
                for ((label, badge) in listOf<Pair<String, (Side) -> MoveQuality?>>("Tactics" to sum::tacticsBadge, "Strategy" to sum::strategyBadge)) {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1.4f), color = CardMuted)
                        for (side in Side.entries) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                val q = badge(side)
                                if (q == null) Text("—", color = CardMuted) else { QualityDot(q, 16.dp); Text(" ${q.label}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Tap a count to jump to that move.", style = MaterialTheme.typography.bodySmall, color = CardMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, CardBorder)) {
                        Icon(Icons.Filled.Share, null, Modifier.size(16.dp), tint = CardText); Spacer(Modifier.width(6.dp)); Text("Share", color = CardText)
                    }
                    OutlinedButton(onClick = onNewGame, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, CardBorder)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp), tint = CardText); Spacer(Modifier.width(6.dp)); Text("New Game", color = CardText)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onStartReview, modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81B64C), contentColor = Color.White),
                ) { Text("Start Review", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun QualityDot(q: MoveQuality, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).background(Color(q.color), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
        Text(q.symbol, color = Color.White, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** White or dark rounded badge, like Chess.com's accuracy / rating chips. */
@Composable
private fun RatingPill(text: String, side: Side, modifier: Modifier) {
    Box(modifier) {
        Surface(
            color = if (side == Side.WHITE) Color(0xFFF2F2F2) else Color(0xFF2B2B2B),
            contentColor = if (side == Side.WHITE) Color(0xFF202020) else Color.White,
            shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, CardBorder),
        ) { Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) }
    }
}

/** A side's known rating: an Elo tag, or the engine strength in a "Stockfish 2400" player name. */
private fun knownElo(t: com.mrchess.core.rules.GameTimeline, side: Side): Int? {
    t.tags["${side.label}Elo"]?.toIntOrNull()?.let { return it }
    val name = t.tags[side.label] ?: return null
    if (!name.startsWith("Stockfish")) return null
    return Regex("\\d{3,4}").find(name)?.value?.toIntOrNull() ?: 3200
}

/** Base for the game rating: the opponent's rating when known, else this side's own, else 1500. */
private fun ratingBase(t: com.mrchess.core.rules.GameTimeline, side: Side): Int = knownElo(t, side.opposite) ?: knownElo(t, side) ?: 1500

/** Plain-text review for the Android share sheet. */
private fun shareText(st: WorkspaceState): String {
    val sum = st.summary
    val t = st.timeline
    val w = t.tags["White"] ?: "White"
    val b = t.tags["Black"] ?: "Black"
    return buildString {
        appendLine("Mr. Chess Trainer game review")
        appendLine("$w vs $b · ${t.toGame().result.pgn}")
        appendLine("Accuracy: $w ${sum.accuracyWhite?.let { "%.1f%%".format(it) } ?: "–"} · $b ${sum.accuracyBlack?.let { "%.1f%%".format(it) } ?: "–"}")
        appendLine("Game rating: $w ${sum.gameRating(Side.WHITE, ratingBase(t, Side.WHITE)) ?: "–"} · $b ${sum.gameRating(Side.BLACK, ratingBase(t, Side.BLACK)) ?: "–"}")
        for (q in MoveQuality.SUMMARY) {
            val nw = sum.count(Side.WHITE, q); val nb = sum.count(Side.BLACK, q)
            if (nw + nb > 0) appendLine("${q.symbol} ${q.label}: $nw / $nb")
        }
        appendLine()
        append(t.toGame().toPgn())
    }
}

// ---------------------------------------------------------------------------- dialogs

@Composable
fun NewGameDialog(initialElo: Int, initialColor: SideChoice, onDismiss: () -> Unit, onStart: (SideChoice, Int, Boolean) -> Unit) {
    var elo by remember { mutableIntStateOf(initialElo) }
    var color by remember { mutableStateOf(initialColor) }
    var saveDefault by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New game") },
        text = {
            Column {
                Text("Opponent: ${WorkspaceController.eloLabel(elo)}")
                Slider(value = elo.toFloat(), onValueChange = { elo = (it / 50).roundToInt() * 50 }, valueRange = 800f..3200f, steps = (3200 - 800) / 50 - 1)
                Text("Slide fully right for unlimited Stockfish (3200+).", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().clickable { saveDefault = !saveDefault }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveDefault, onCheckedChange = { saveDefault = it })
                    Text("Set as default Elo level and colour", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SideChoice.entries.forEach { ch -> FilterChip(color == ch, { color = ch }, { Text(ch.label) }) }
                }
            }
        },
        confirmButton = { Button(onClick = { onStart(color, elo, saveDefault) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** 2 Player: which colour Player 1 (bottom edge) plays. */
@Composable
fun TwoPlayerNewGameDialog(current: SideChoice, onDismiss: () -> Unit, onStart: (SideChoice) -> Unit) {
    var color by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New 2 Player game") },
        text = {
            Column {
                Text("Player 1 sits at the bottom edge. Player 2 sits opposite.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                SideChoice.entries.forEach { ch ->
                    Row(Modifier.fillMaxWidth().clickable { color = ch }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = color == ch, onClick = { color = ch })
                        Column {
                            Text(ch.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                when (ch) {
                                    SideChoice.WHITE -> "Player 1 = White (bottom), Player 2 = Black (top)"
                                    SideChoice.BLACK -> "Player 1 = Black (bottom), Player 2 = White (top)"
                                    SideChoice.RANDOM -> "50/50 coin toss"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onStart(color) }) { Text("Start") } },
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
                        Text(Piece(t, side).glyph.toString() + "︎", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Suppress("unused")
private fun strengthLabel(elo: Int) = EngineStrength.fromElo(elo).label
