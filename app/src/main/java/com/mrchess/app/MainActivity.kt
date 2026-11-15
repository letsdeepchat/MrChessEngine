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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrchess.app.ui.HeaderAction
import com.mrchess.app.ui.ImportScreen
import com.mrchess.app.ui.MainViewModel
import com.mrchess.app.ui.ReviewList
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
                val screen by vm.screen.collectAsStateWithLifecycle()
                val importWs by vm.importWorkspace.collectAsStateWithLifecycle()
                val reviewWs by vm.reviewWorkspace.collectAsStateWithLifecycle()
                val importState by vm.importState.collectAsStateWithLifecycle()
                val saved by vm.saved.collectAsStateWithLifecycle()
                val soundOn by vm.soundOn.collectAsStateWithLifecycle()
                val soundAction = HeaderAction(if (soundOn) "Board sounds: on" else "Board sounds: off") { vm.toggleSound() }
                Scaffold(
                    bottomBar = {
                        NavigationBar(Modifier.height(64.dp)) {
                            NavigationBarItem(screen == Screen.PLAY, { vm.go(Screen.PLAY) }, { Icon(Icons.Filled.SportsEsports, null) }, label = { Text("Play") })
                            NavigationBarItem(screen == Screen.TWO_PLAYER, { vm.go(Screen.TWO_PLAYER) }, { Icon(Icons.Filled.People, null) }, label = { Text("Two Player") })
                            NavigationBarItem(screen == Screen.IMPORT, { vm.go(Screen.IMPORT) }, { Icon(Icons.Filled.FileOpen, null) }, label = { Text("Import") })
                            NavigationBarItem(screen == Screen.REVIEW, { vm.go(Screen.REVIEW) }, { Icon(Icons.Filled.Analytics, null) }, label = { Text("Review") })
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (screen) {
                            Screen.PLAY -> Workspace(vm.play, listOf(
                                HeaderAction("Resign") { vm.play.resign() },
                                HeaderAction("Review this game") { vm.reviewFrom(vm.play) },
                                soundAction,
                            ))
                            Screen.TWO_PLAYER -> Workspace(vm.twoPlayer, listOf(
                                HeaderAction("Review this game") { vm.reviewFrom(vm.twoPlayer) },
                                soundAction,
                            ))
                            Screen.IMPORT -> importWs?.let { c ->
                                Workspace(c, listOf(HeaderAction("New import") { vm.closeImported() }, soundAction))
                            } ?: ImportScreen(vm, importState)
                            Screen.REVIEW -> reviewWs?.let { c ->
                                Workspace(c, listOf(HeaderAction("Back to games") { vm.closeReview() }, soundAction))
                            } ?: ReviewList(saved, vm::reviewSaved, vm::deleteSaved)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Workspace(c: WorkspaceController, menu: List<HeaderAction>) {
        val st by c.state.collectAsStateWithLifecycle()
        val speaking by vm.narrator.speaking.collectAsStateWithLifecycle()
        UnifiedChessWorkspace(st, c, vm.narrator, speaking, menu)
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
