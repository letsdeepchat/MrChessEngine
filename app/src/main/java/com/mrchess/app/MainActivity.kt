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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrchess.app.ui.ImportScreen
import com.mrchess.app.ui.MainViewModel
import com.mrchess.app.ui.PlayScreen
import com.mrchess.app.ui.ReviewScreen
import com.mrchess.app.ui.Screen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                val screen by vm.screen.collectAsStateWithLifecycle()
                val board by vm.board.collectAsStateWithLifecycle()
                val importState by vm.importState.collectAsStateWithLifecycle()
                val review by vm.review.collectAsStateWithLifecycle()
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(screen == Screen.PLAY, { vm.go(Screen.PLAY) }, { Icon(Icons.Filled.SportsEsports, null) }, label = { Text("Play") })
                            NavigationBarItem(screen == Screen.IMPORT, { vm.go(Screen.IMPORT) }, { Icon(Icons.Filled.FileOpen, null) }, label = { Text("Import") })
                            NavigationBarItem(screen == Screen.REVIEW, { vm.go(Screen.REVIEW) }, { Icon(Icons.Filled.Analytics, null) }, label = { Text("Review") })
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (screen) {
                            Screen.PLAY -> PlayScreen(vm, board)
                            Screen.IMPORT -> ImportScreen(vm, importState)
                            Screen.REVIEW -> ReviewScreen(vm, review)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Handles images / text shared to the app by the user. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (type.startsWith("image/")) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            uri?.let(vm::importImage)
        } else if (type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                vm.setImportText(it)
                vm.go(Screen.IMPORT)
            }
        }
    }
}
