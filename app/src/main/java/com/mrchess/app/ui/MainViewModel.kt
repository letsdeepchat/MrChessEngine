package com.mrchess.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mrchess.app.MrChessApp
import android.app.Activity
import com.mrchess.app.audio.SoundPack
import com.mrchess.app.data.Connectivity
import com.mrchess.app.data.GameRecordEntity
import com.mrchess.app.vision.ImageToFen
import com.mrchess.app.vision.Recognition
import com.mrchess.core.rules.GameTimeline
import com.mrchess.core.rules.Pgn
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side
import com.mrchess.core.online.OnlinePlay
import com.mrchess.core.puzzle.PuzzleRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { PLAY, TWO_PLAYER, IMPORT, REVIEW, PUZZLES }

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
    private val history get() = mrApp.history
    private var visionInstance: ImageToFen? = null
    private val vision: ImageToFen get() = visionInstance ?: ImageToFen(ctx).also { visionInstance = it }

    val narrator get() = mrApp.narrator
    val sounds get() = mrApp.sounds

    private val _screen = MutableStateFlow(Screen.PLAY)
    val screen: StateFlow<Screen> = _screen
    private val _import = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _import
    val saved: StateFlow<List<GameRecordEntity>> =
        mrApp.history.games.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val soundPack: StateFlow<SoundPack> = mrApp.settings.soundPack.stateIn(viewModelScope, SharingStarted.Eagerly, SoundPack.DEFAULT)
    val boardTheme: StateFlow<BoardTheme> = mrApp.settings.boardTheme.stateIn(viewModelScope, SharingStarted.Eagerly, BoardTheme.DEFAULT)
    val cloudAvailable: Boolean get() = history.cloud.available
    val cloudAccount: StateFlow<String?> get() = history.cloud.account
    val cloudName: StateFlow<String?> get() = history.cloud.profileName
    private val _showThemeSheet = MutableStateFlow(false)
    val showThemeSheet: StateFlow<Boolean> = _showThemeSheet
    private val _playOnline = MutableStateFlow(false)
    /** Play tab mode: false = Play Bot (Stockfish), true = Play Online. */
    val playOnline: StateFlow<Boolean> = _playOnline
    fun setPlayOnline(on: Boolean) {
        _playOnline.value = on
        activate()
    }

    private val _showSoundSheet = MutableStateFlow(false)
    val showSoundSheet: StateFlow<Boolean> = _showSoundSheet
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Read synchronously from SharedPreferences, so the very first frame shows the saved bot. */
    val botDefaults: StateFlow<BotDefaults> = mrApp.settings.botDefaults
    private val _pendingNewGame = MutableStateFlow(false)
    /** "+ New Game" was tapped in Import/Review: the Play tab should open its New Game dialog. */
    val pendingNewGame: StateFlow<Boolean> = _pendingNewGame

    /** First-launch gate: null while settings load, true = show the welcome / sign-in screen. */
    val showWelcome: StateFlow<Boolean?> = combine(mrApp.settings.guestMode, history.cloud.account) { guest, account -> !guest && account == null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _showAuth = MutableStateFlow(false)
    /** The sign-in screen opened later from ⋮ or the Review tab. */
    val showAuth: StateFlow<Boolean> = _showAuth
    private val _authBusy = MutableStateFlow(false)
    val authBusy: StateFlow<Boolean> = _authBusy

    private fun controller(mode: SessionMode) =
        WorkspaceController(ctx, viewModelScope, mode, sounds, history)

    val puzzles by lazy { PuzzleController(viewModelScope, mrApp.settings, sounds, onHistoryChanged = { pushProfileSoon() }) }

    // ------------------------------------------------------------------ profile & online

    val online: OnlinePlay = createOnlinePlay()
    val cloudPhoto: StateFlow<String?> get() = history.cloud.photoUrl

    /** The @username: the one the player chose, else a handle derived from the account name / e-mail. */
    val username: StateFlow<String> = combine(mrApp.settings.username, history.cloud.profileName, history.cloud.account) { u, n, e ->
        u ?: Profile.defaultHandle(n, e)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, mrApp.settings.username.value ?: Profile.defaultHandle(null, null))

    val profileStats: StateFlow<ProfileStats> = combine(mrApp.settings.puzzleHistory, mrApp.settings.onlineElo, saved) { ph, elo, games ->
        ProfileStats(puzzleRating = ph.elo, puzzleProvisional = ph.attempts < PuzzleRating.PROVISIONAL, onlineRating = elo,
            gamesPlayed = games.size, averageAccuracy = Profile.averageAccuracy(games))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileStats())

    private val _showProfile = MutableStateFlow(false)
    /** The profile card opened from ⋮ → Profile / Account. */
    val showProfile: StateFlow<Boolean> = _showProfile
    fun openProfile() { _showProfile.value = true }
    fun closeProfile() { _showProfile.value = false }

    /** Saves a new @username; returns an error message, or null when it was saved. */
    fun setUsername(raw: String): String? {
        val name = Profile.sanitize(raw) ?: return "Use 3–20 letters, digits or underscores."
        mrApp.settings.setUsername(name)
        pushProfileSoon()
        return null
    }

    val onlineController by lazy { OnlineController(ctx, viewModelScope, online, mrApp.settings, sounds, history, username, onReview = ::reviewOnline, onRatingChanged = ::pushProfileSoon) }

    private var profileJob: kotlinx.coroutines.Job? = null
    /** Backs up username, puzzle history and online rating to users/{uid} (signed-in accounts only). */
    fun pushProfileSoon() {
        if (!online.available || history.cloud.account.value == null || !Connectivity.isOnline(ctx)) return
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1_500)
            online.saveProfile(Profile.toCloud(username.value, mrApp.settings.puzzleHistory.value, mrApp.settings.onlineElo.value, mrApp.settings.onlineGames))
        }
    }

    /** After sign-in: merge the cloud profile into this phone's, then back up the result. */
    private suspend fun pullProfile() {
        if (!online.available || !Connectivity.isOnline(ctx)) return
        val remote = online.loadProfile().getOrNull() ?: return
        val st = mrApp.settings
        (remote["username"] as? String)?.let { if (st.username.value == null) st.setUsername(it) }
        st.savePuzzleHistory(Profile.mergePuzzles(st.puzzleHistory.value, remote))
        val games = (remote["onlineGames"] as? Number)?.toInt() ?: 0
        if (games > st.onlineGames) (remote["onlineElo"] as? Number)?.toInt()?.let { st.setOnlineElo(it, games) }
        pushProfileSoon()
    }

    private fun createOnlinePlay(): OnlinePlay = runCatching {
        if (!history.cloud.available) return OnlinePlay.Disabled
        Class.forName("com.mrchess.cloudsync.FirebaseOnlinePlay").getConstructor(android.content.Context::class.java).newInstance(ctx) as OnlinePlay
    }.getOrDefault(OnlinePlay.Disabled)

    private fun reviewOnline(t: GameTimeline, title: String) {
        if (t.nodes.isEmpty()) return
        openReview(t.toStart(), title)
    }
    val play = controller(SessionMode.PLAY_AI)
    val twoPlayer = controller(SessionMode.TWO_PLAYER).also { it.newTwoPlayerGame() }
    private val _importWs = MutableStateFlow<WorkspaceController?>(null)
    val importWorkspace: StateFlow<WorkspaceController?> = _importWs
    private val _reviewWs = MutableStateFlow<WorkspaceController?>(null)
    val reviewWorkspace: StateFlow<WorkspaceController?> = _reviewWs

    init {
        // Cold start: the saved default bot (SharedPreferences, read synchronously) is set up
        // before the first frame, so the header, the engine strength and the New Game slider all
        // start at the saved Elo instead of flashing 1500.
        val d = mrApp.settings.readBotDefaults()
        play.newGame(if (d.saved) d.color.resolve() else Side.WHITE, d.elo)
        activate()
        viewModelScope.launch {
            // Defaults saved by 1.6 or earlier live in DataStore: copy them over once.
            val migrated = mrApp.settings.migrateBotDefaults()
            if (migrated != null && play.state.value.timeline.nodes.isEmpty()) play.newGame(migrated.color.resolve(), migrated.elo)
        }
        viewModelScope.launch {
            history.cloud.account.collect { acc -> if (acc != null) pullProfile() }
        }
        viewModelScope.launch {
            history.migrateLegacyPgnFiles()
            history.requestSync() // background, and skipped while offline
        }
        viewModelScope.launch { soundPack.collect { sounds.pack = it } }
    }

    fun go(s: Screen) {
        val was = _screen.value
        _screen.value = s
        narrator.stop()
        activate()
        if (s == Screen.PUZZLES && was != Screen.PUZZLES) puzzles.onOpened()
    }

    /** Only the visible session may use the engine. */
    private fun activate() {
        val s = _screen.value
        play.setActive(s == Screen.PLAY && !playOnline.value)
        twoPlayer.setActive(s == Screen.TWO_PLAYER)
        _importWs.value?.setActive(s == Screen.IMPORT)
        _reviewWs.value?.setActive(s == Screen.REVIEW)
    }

    fun openSoundSheet() { _showSoundSheet.value = true }
    fun closeSoundSheet() { _showSoundSheet.value = false }
    fun setSoundPack(p: SoundPack) {
        sounds.preview(p)
        viewModelScope.launch { mrApp.settings.setSoundPack(p) }
    }

    fun openThemeSheet() { _showThemeSheet.value = true }
    fun closeThemeSheet() { _showThemeSheet.value = false }
    fun setBoardTheme(t: BoardTheme) {
        viewModelScope.launch { mrApp.settings.setBoardTheme(t) }
        _showThemeSheet.value = false
    }

    fun downloadNaturalVoice() = narrator.installVoiceData(ctx)

    fun clearMessage() { _message.value = null }

    // ------------------------------------------------------------------ cloud backup

    fun signIn(activity: Activity) = authenticate { history.cloud.signIn(activity) }

    fun signInWithEmail(email: String, password: String, create: Boolean) =
        authenticate { history.cloud.signInWithEmail(email.trim(), password, create) }

    fun resetPassword(email: String) {
        if (!Connectivity.isOnline(ctx)) { _message.value = Connectivity.OFFLINE_MESSAGE; return }
        viewModelScope.launch {
            history.cloud.sendPasswordReset(email.trim())
                .onSuccess { _message.value = "Password reset e-mail sent to ${email.trim()}." }
                .onFailure { e -> _message.value = e.message ?: "Could not send the reset e-mail" }
        }
    }

    private fun authenticate(block: suspend () -> Result<String>) {
        if (_authBusy.value) return
        if (!Connectivity.isOnline(ctx)) { _message.value = Connectivity.OFFLINE_MESSAGE; return }
        viewModelScope.launch {
            _authBusy.value = true
            try {
                block()
                    .onSuccess { email ->
                        _showAuth.value = false
                        _message.value = "Signed in as $email. Merging your games with the cloud…"
                        // Sync in the background; the sign-in screen closes at once.
                        viewModelScope.launch {
                            history.sync()
                                .onSuccess { _message.value = "Signed in as $email. Your game history is backed up and synced." }
                                .onFailure { e -> _message.value = "Signed in as $email, but backup failed: ${e.message}" }
                        }
                    }
                    .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _message.value = e.message ?: "Sign-in failed" }
            } finally {
                _authBusy.value = false
            }
        }
    }

    fun continueAsGuest() {
        _showAuth.value = false
        viewModelScope.launch { mrApp.settings.setGuestMode(true) }
    }

    fun openAuth() { _showAuth.value = true }
    fun closeAuth() { _showAuth.value = false }

    // ------------------------------------------------------------------ new game plumbing

    fun botGameStarted(elo: Int, color: SideChoice, save: Boolean) {
        if (save) mrApp.settings.setBotDefaults(elo, color) // commit(): survives an immediate force-close
    }

    fun newGameFromElsewhere() {
        _pendingNewGame.value = true
        go(Screen.PLAY)
    }

    fun newGameOpened() { _pendingNewGame.value = false }

    fun signOut() {
        _showProfile.value = false
        viewModelScope.launch { history.cloud.signOut() }
    }

    fun syncNow() {
        viewModelScope.launch {
            history.sync()
                .onSuccess { if (history.cloud.account.value != null) _message.value = "Games backed up and synced." }
                .onFailure { e -> _message.value = "Sync failed: ${e.message}" }
        }
    }

    fun clearHistory(alsoCloud: Boolean) {
        viewModelScope.launch { history.clear(alsoCloud) }
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

    fun deleteSaved(g: GameRecordEntity) {
        viewModelScope.launch { history.delete(g.id) }
    }

    fun reviewSaved(g: GameRecordEntity) {
        viewModelScope.launch {
            val t = history.load(g.id) ?: run { _message.value = "Could not open that game"; return@launch }
            openReview(t, g.title, g.id)
        }
    }

    private fun openReview(t: GameTimeline, title: String, recordId: String? = null) {
        _reviewWs.value?.setActive(false)
        val c = controller(SessionMode.POST_GAME_REVIEW)
        c.load(t, title, recordId)
        _reviewWs.value = c
        _screen.value = Screen.REVIEW
        activate()
    }

    fun closeReview() {
        _reviewWs.value?.setActive(false)
        _reviewWs.value = null
        narrator.stop()
    }

    /** Opens the current Play / Two Player game in Review. */
    fun reviewFrom(c: WorkspaceController, fromMove1: Boolean = false) {
        val st = c.state.value
        if (st.timeline.nodes.isEmpty()) return
        openReview(st.timeline.toStart(), st.title)
        if (fromMove1) _reviewWs.value?.startReview()
    }

    override fun onCleared() {
        visionInstance?.close()
    }
}
