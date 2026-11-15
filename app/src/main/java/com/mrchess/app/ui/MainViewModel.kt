package com.mrchess.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mrchess.app.MrChessApp
import com.mrchess.app.data.GameStore
import com.mrchess.app.data.SavedGame
import com.mrchess.app.vision.ImageToFen
import com.mrchess.app.vision.Recognition
import com.mrchess.core.rules.GameTimeline
import com.mrchess.core.rules.Pgn
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { PLAY, TWO_PLAYER, IMPORT, REVIEW }

data class ImportState(
    val text: String = "",
    val recognizing: Boolean = false,
    val recognition: Recognition? = null,
    val sideToMove: Side = Side.WHITE,
    val blackAtBottom: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val mrApp get() = getApplication<MrChessApp>()
    private val store = GameStore(app)
    private var visionInstance: ImageToFen? = null
    private val vision: ImageToFen get() = visionInstance ?: ImageToFen(ctx).also { visionInstance = it }

    val narrator get() = mrApp.narrator
    val sounds get() = mrApp.sounds

    private val _screen = MutableStateFlow(Screen.PLAY)
    val screen: StateFlow<Screen> = _screen
    private val _import = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _import
    private val _saved = MutableStateFlow<List<SavedGame>>(emptyList())
    val saved: StateFlow<List<SavedGame>> = _saved
    private val _soundOn = MutableStateFlow(true)
    val soundOn: StateFlow<Boolean> = _soundOn

    private fun controller(mode: SessionMode) =
        WorkspaceController(ctx, viewModelScope, mode, sounds, store, onSaved = ::refreshSaved)

    val play = controller(SessionMode.PLAY_AI)
    val twoPlayer = controller(SessionMode.TWO_PLAYER).also { it.newTwoPlayerGame() }
    private val _importWs = MutableStateFlow<WorkspaceController?>(null)
    val importWorkspace: StateFlow<WorkspaceController?> = _importWs
    private val _reviewWs = MutableStateFlow<WorkspaceController?>(null)
    val reviewWorkspace: StateFlow<WorkspaceController?> = _reviewWs

    init {
        play.newGame(Side.WHITE, 1500)
        activate()
        refreshSaved()
    }

    fun go(s: Screen) {
        _screen.value = s
        narrator.stop()
        activate()
        if (s == Screen.REVIEW) refreshSaved()
    }

    /** Only the visible session may use the engine. */
    private fun activate() {
        val s = _screen.value
        play.setActive(s == Screen.PLAY)
        twoPlayer.setActive(s == Screen.TWO_PLAYER)
        _importWs.value?.setActive(s == Screen.IMPORT)
        _reviewWs.value?.setActive(s == Screen.REVIEW)
    }

    fun toggleSound() {
        _soundOn.update { !it }
        sounds.enabled = _soundOn.value
    }

    // ------------------------------------------------------------------ import

    fun setImportText(t: String) = _import.update { it.copy(text = t, error = null) }

    private fun openImported(t: GameTimeline, title: String) {
        _importWs.value?.setActive(false)
        val c = controller(SessionMode.IMPORT_ANALYSIS)
        c.load(t, title)
        _importWs.value = c
        _screen.value = Screen.IMPORT
        activate()
    }

    fun closeImported() {
        _importWs.value?.setActive(false)
        _importWs.value = null
        narrator.stop()
    }

    /** Accepts a FEN, a PGN, or a bare SAN move list. */
    fun importText() {
        val t = _import.value.text.trim()
        if (t.isEmpty()) return
        val looksFen = t.count { it == '/' } == 7 && !t.contains('[') && t.lines().size == 1
        try {
            if (looksFen) {
                val problems = Position.validate(t)
                if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
                openImported(GameTimeline(Position.fromFen(t).toFen()), "Imported position")
            } else {
                val g = Pgn.parse(t)
                openImported(GameTimeline.fromGame(g), "${g.tags["White"] ?: "White"} vs ${g.tags["Black"] ?: "Black"}")
            }
        } catch (e: Exception) {
            _import.update { it.copy(error = e.message ?: "Could not read FEN/PGN") }
        }
    }

    /** Opens a pasted PGN in the review workspace (full annotation + summary). */
    fun reviewPgn(text: String) {
        runCatching { Pgn.parse(text) }
            .onSuccess { g -> openReview(GameTimeline.fromGame(g, atEnd = false), "${g.tags["White"] ?: "White"} vs ${g.tags["Black"] ?: "Black"}") }
            .onFailure { e -> _import.update { it.copy(error = e.message) } }
    }

    fun importImage(uri: Uri) {
        _screen.value = Screen.IMPORT
        closeImported()
        _import.update { it.copy(recognizing = true, recognition = null, error = null) }
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) { vision.recognize(uri) }
                _import.update { it.copy(recognizing = false, recognition = r, blackAtBottom = r.likelyBlackAtBottom) }
            } catch (e: Exception) {
                _import.update { it.copy(recognizing = false, error = "Could not recognise a board: ${e.message}") }
            }
        }
    }

    fun setImportSide(side: Side) = _import.update { it.copy(sideToMove = side) }
    fun setBlackAtBottom(v: Boolean) = _import.update { it.copy(blackAtBottom = v) }

    fun recognizedFen(): String? {
        val s = _import.value
        return s.recognition?.toFen(s.sideToMove, s.blackAtBottom)
    }

    fun analyzeRecognized() {
        val fen = recognizedFen() ?: return
        val problems = Position.validate(fen)
        if (problems.isNotEmpty()) { _import.update { it.copy(error = problems.joinToString("; ")) }; return }
        openImported(GameTimeline(Position.fromFen(fen).toFen()), "Screenshot position")
    }

    fun playFromHere(fen: String, side: Side) {
        val problems = Position.validate(fen)
        if (problems.isNotEmpty()) { _import.update { it.copy(error = problems.joinToString("; ")) }; return }
        val engine = WorkspaceController.engineLabel(play.state.value.elo)
        val t = GameTimeline(Position.fromFen(fen).toFen(), tags = mapOf("White" to if (side == Side.WHITE) "You" else engine, "Black" to if (side == Side.BLACK) "You" else engine))
        play.newGame(side, play.state.value.elo)
        play.load(t, WorkspaceController.defaultTitle(SessionMode.PLAY_AI, play.state.value.elo))
        go(Screen.PLAY)
    }

    fun editRecognizedAsText() {
        val fen = recognizedFen() ?: return
        _import.update { it.copy(text = fen, recognition = null) }
    }

    // ------------------------------------------------------------------ review

    fun refreshSaved() {
        viewModelScope.launch(Dispatchers.IO) { _saved.value = store.list() }
    }

    fun deleteSaved(g: SavedGame) {
        viewModelScope.launch(Dispatchers.IO) { store.delete(g.file); _saved.value = store.list() }
    }

    fun reviewSaved(g: SavedGame) {
        viewModelScope.launch {
            val game = withContext(Dispatchers.IO) { runCatching { store.load(g.file) }.getOrNull() } ?: return@launch
            openReview(GameTimeline.fromGame(game, atEnd = false), g.title)
        }
    }

    private fun openReview(t: GameTimeline, title: String) {
        _reviewWs.value?.setActive(false)
        val c = controller(SessionMode.POST_GAME_REVIEW)
        c.load(t, title)
        _reviewWs.value = c
        _screen.value = Screen.REVIEW
        activate()
    }

    fun closeReview() {
        _reviewWs.value?.setActive(false)
        _reviewWs.value = null
        narrator.stop()
        refreshSaved()
    }

    /** Opens the current Play / Two Player game in Review. */
    fun reviewFrom(c: WorkspaceController) {
        val st = c.state.value
        if (st.timeline.nodes.isEmpty()) return
        openReview(st.timeline.toStart(), st.title)
    }

    override fun onCleared() {
        visionInstance?.close()
    }
}
