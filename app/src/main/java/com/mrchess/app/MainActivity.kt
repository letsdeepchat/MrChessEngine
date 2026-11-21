package com.mrchess.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import com.mrchess.app.ui.BoardThemeSheet
import com.mrchess.app.ui.CloudUi
import com.mrchess.app.ui.OnlineScreen
import com.mrchess.app.ui.PlayModeToggle
import com.mrchess.app.ui.ProfileCard
import com.mrchess.app.ui.ProfileDialog
import com.mrchess.app.ui.HeaderAction
import com.mrchess.app.ui.LocalBoardTheme
import com.mrchess.app.ui.ImportScreen
import com.mrchess.app.ui.MainViewModel
import com.mrchess.app.ui.PuzzleScreen
import com.mrchess.app.ui.ReviewList
import com.mrchess.app.ui.WelcomeScreen
import com.mrchess.app.ui.WorkspaceHooks
import com.mrchess.app.ui.SoundPackSheet
import com.mrchess.app.ui.Screen
import com.mrchess.app.ui.UnifiedChessWorkspace
import com.mrchess.app.ui.WorkspaceController

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
              val boardTheme by vm.boardTheme.collectAsStateWithLifecycle()
              CompositionLocalProvider(LocalBoardTheme provides boardTheme) {
                val screen by vm.screen.collectAsStateWithLifecycle()
                val importWs by vm.importWorkspace.collectAsStateWithLifecycle()
                val reviewWs by vm.reviewWorkspace.collectAsStateWithLifecycle()
                val importState by vm.importState.collectAsStateWithLifecycle()
                val saved by vm.saved.collectAsStateWithLifecycle()
                val soundPack by vm.soundPack.collectAsStateWithLifecycle()
                val soundSheet by vm.showSoundSheet.collectAsStateWithLifecycle()
                val theme by vm.boardTheme.collectAsStateWithLifecycle()
                val themeSheet by vm.showThemeSheet.collectAsStateWithLifecycle()
                val naturalVoice by vm.narrator.hasNaturalVoice.collectAsStateWithLifecycle()
                val account by vm.cloudAccount.collectAsStateWithLifecycle()
                val accountName by vm.cloudName.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                val showWelcome by vm.showWelcome.collectAsStateWithLifecycle()
                val showAuth by vm.showAuth.collectAsStateWithLifecycle()
                val authBusy by vm.authBusy.collectAsStateWithLifecycle()
                val botDefaults by vm.botDefaults.collectAsStateWithLifecycle()
                val pendingNewGame by vm.pendingNewGame.collectAsStateWithLifecycle()
                val playOnline by vm.playOnline.collectAsStateWithLifecycle()
                val username by vm.username.collectAsStateWithLifecycle()
                val profileStats by vm.profileStats.collectAsStateWithLifecycle()
                val photo by vm.cloudPhoto.collectAsStateWithLifecycle()
                val showProfile by vm.showProfile.collectAsStateWithLifecycle()
                val cloudUi = CloudUi(vm.cloudAvailable, account, vm::openAuth, vm::signOut, vm::syncNow, accountName)
                val profileCard: @Composable () -> Unit = {
                    ProfileCard(username, accountName, account, photo, profileStats, cloudUi, vm::setUsername)
                }
                // ⋮ menu: customisation and account (game actions are one tap away under the board).
                val common = buildList {
                    add(HeaderAction("Board theme: ${theme.label}…") { vm.openThemeSheet() })
                    add(HeaderAction("Board sounds: ${soundPack.label}…") { vm.openSoundSheet() })
                    if (!naturalVoice) add(HeaderAction("Download natural voice") { vm.downloadNaturalVoice() })
                    add(HeaderAction("Profile / Account…") { vm.openProfile() })
                    if (vm.cloudAvailable && account == null) add(HeaderAction("Sign in / Sign up to back up games…") { vm.openAuth() })
                }
                fun hooks(c: WorkspaceController) = WorkspaceHooks(
                    onReview = { fromMove1 -> vm.reviewFrom(c, fromMove1) },
                    onClose = { if (c === vm.importWorkspace.value) vm.closeImported() else vm.closeReview() },
                    onNewGameElsewhere = vm::newGameFromElsewhere,
                    botDefaults = botDefaults,
                    onBotGameStarted = vm::botGameStarted,
                    openNewGame = pendingNewGame && c === vm.play,
                    onNewGameOpened = vm::newGameOpened,
                    onShare = ::shareText,
                    topSlot = if (c === vm.play) ({ PlayModeToggle(online = false, onChange = vm::setPlayOnline) }) else null,
                )
                if (showWelcome == null) return@CompositionLocalProvider // settings still loading: avoid a flash of the wrong screen
                if (showWelcome == true || showAuth) {
                    WelcomeScreen(
                        busy = authBusy,
                        cloudAvailable = vm.cloudAvailable,
                        dismissible = showWelcome != true,
                        onGoogle = { vm.signIn(this@MainActivity) },
                        onEmail = vm::signInWithEmail,
                        onResetPassword = vm::resetPassword,
                        onGuest = { if (showWelcome == true) vm.continueAsGuest() else vm.closeAuth() },
                    )
                    message?.let { m ->
                        AlertDialog(onDismissRequest = vm::clearMessage, confirmButton = { TextButton(onClick = vm::clearMessage) { Text("OK") } }, text = { Text(m) })
                    }
                    return@CompositionLocalProvider
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar(Modifier.height(64.dp)) {
                            NavigationBarItem(screen == Screen.PLAY, { vm.go(Screen.PLAY) }, { Icon(Icons.Filled.SportsEsports, null) }, label = { NavLabel("Play") })
                            NavigationBarItem(screen == Screen.TWO_PLAYER, { vm.go(Screen.TWO_PLAYER) }, { Icon(Icons.Filled.People, null) }, label = { NavLabel("2 Player") })
                            NavigationBarItem(screen == Screen.IMPORT, { vm.go(Screen.IMPORT) }, { Icon(Icons.Filled.FileOpen, null) }, label = { NavLabel("Import") })
                            NavigationBarItem(screen == Screen.REVIEW, { vm.go(Screen.REVIEW) }, { Icon(Icons.Filled.Analytics, null) }, label = { NavLabel("Review") })
                            NavigationBarItem(screen == Screen.PUZZLES, { vm.go(Screen.PUZZLES) }, { Icon(Icons.Filled.Extension, null) }, label = { NavLabel("Puzzles") })
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (screen) {
                            Screen.PLAY -> if (playOnline) {
                                val os by vm.onlineController.state.collectAsStateWithLifecycle()
                                OnlineScreen(
                                    os, vm.onlineController, username,
                                    onPlayBot = { vm.setPlayOnline(false) },
                                    onSignIn = if (vm.cloudAvailable && account == null) vm::openAuth else null,
                                )
                            } else Workspace(vm.play, common, hooks(vm.play))
                            Screen.TWO_PLAYER -> Workspace(vm.twoPlayer, common, hooks(vm.twoPlayer))
                            Screen.IMPORT -> importWs?.let { c ->
                                Workspace(c, common, hooks(c))
                            } ?: ImportScreen(vm, importState)
                            Screen.PUZZLES -> {
                                val ps by vm.puzzles.state.collectAsStateWithLifecycle()
                                PuzzleScreen(ps, vm.puzzles, common)
                            }
                            Screen.REVIEW -> reviewWs?.let { c ->
                                Workspace(c, common, hooks(c))
                            } ?: ReviewList(
                                saved, cloudUi,
                                vm::reviewSaved, vm::deleteSaved, vm::clearHistory,
                                header = profileCard,
                            )
                        }
                    }
                }
                if (showProfile) ProfileDialog(profileCard, vm::closeProfile)
                if (themeSheet) BoardThemeSheet(theme, vm::setBoardTheme, vm::closeThemeSheet)
                if (soundSheet) SoundPackSheet(soundPack, vm::setSoundPack, vm::closeSoundSheet)
                message?.let { m ->
                    AlertDialog(onDismissRequest = vm::clearMessage, confirmButton = { TextButton(onClick = vm::clearMessage) { Text("OK") } }, text = { Text(m) })
                }
              }
            }
        }
    }

    @Composable
    private fun Workspace(c: WorkspaceController, menu: List<HeaderAction>, hooks: WorkspaceHooks) {
        val st by c.state.collectAsStateWithLifecycle()
        val speaking by vm.narrator.speaking.collectAsStateWithLifecycle()
        UnifiedChessWorkspace(st, c, vm.narrator, speaking, menu, hooks)
    }

    /** Bottom-bar label: one line, slightly smaller, so "2 Player" lines up with the other tabs. */
    @Composable
    private fun NavLabel(text: String) =
        Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, fontSize = 11.sp)

    /** Game review text (accuracy, game rating, move counts, PGN) to the Android share sheet. */
    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Mr. Chess Trainer game review")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share game review"))
    }

    override fun onStop() {
        super.onStop()
        vm.narrator.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Images or text shared to the app by the user (never captured automatically). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (type.startsWith("image/")) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            uri?.let(vm::importImage)
        } else if (type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                vm.closeImported()
                vm.setImportText(it)
                vm.go(Screen.IMPORT)
            }
        }
    }
}
