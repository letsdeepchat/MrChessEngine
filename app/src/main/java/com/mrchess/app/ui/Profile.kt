package com.mrchess.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrchess.app.data.GameRecordEntity
import com.mrchess.core.puzzle.PuzzleHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Numbers on the profile card. */
@Immutable
data class ProfileStats(
    val puzzleRating: Int = 1500,
    val puzzleProvisional: Boolean = true,
    val onlineRating: Int = 1000,
    val gamesPlayed: Int = 0,
    val averageAccuracy: Double? = null,
)

/** Pure helpers for the profile (username rules, stats, cloud merge). */
object Profile {
    private val ALLOWED = Regex("^[A-Za-z0-9_]{3,20}$")

    /** "@deepak_c" style handle from a display name or e-mail; "player" + digits as a last resort. */
    fun defaultHandle(name: String?, email: String?): String {
        val base = (name?.takeIf { it.isNotBlank() } ?: email?.substringBefore('@') ?: "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(20)
        return if (base.length >= 3) base else "player" + (1000..9999).random()
    }

    /** The cleaned username, or null if it breaks the rules (3–20 letters, digits, underscores). */
    fun sanitize(raw: String): String? = raw.trim().removePrefix("@").takeIf { ALLOWED.matches(it) }

    /** Which colour "You" played, from the saved title "White vs Black". */
    private fun yourAccuracy(g: GameRecordEntity): Double? = when {
        g.title.startsWith("You vs ") -> g.whiteAccuracy
        g.title.endsWith(" vs You") -> g.blackAccuracy
        else -> null
    }

    /** Mean accuracy over your games (bot and online) that have been analysed. */
    fun averageAccuracy(games: List<GameRecordEntity>): Double? =
        games.mapNotNull(::yourAccuracy).takeIf { it.isNotEmpty() }?.average()

    fun toCloud(username: String, p: PuzzleHistory, onlineElo: Int, onlineGames: Int): Map<String, Any?> = mapOf(
        "username" to username,
        "usernameLower" to username.lowercase(),
        "onlineElo" to onlineElo,
        "onlineGames" to onlineGames,
        "puzzleElo" to p.elo,
        "puzzleAttempts" to p.attempts,
        "puzzleSolvedCount" to p.solvedCount,
        "solvedPuzzleIds" to p.solved.toList(),
        "failedPuzzleHistory" to p.failed,
    )

    /** Union of solved puzzles; the rating of whichever side has played more. */
    @Suppress("UNCHECKED_CAST")
    fun mergePuzzles(local: PuzzleHistory, remote: Map<String, Any?>): PuzzleHistory {
        val rAttempts = (remote["puzzleAttempts"] as? Number)?.toInt() ?: return local
        val rSolved = (remote["solvedPuzzleIds"] as? List<String>).orEmpty().toSet()
        val rFailed = (remote["failedPuzzleHistory"] as? Map<String, Number>).orEmpty().mapValues { it.value.toInt() }
        val solved = local.solved + rSolved
        val remoteWins = rAttempts > local.attempts
        return PuzzleHistory(
            elo = if (remoteWins) (remote["puzzleElo"] as? Number)?.toInt() ?: local.elo else local.elo,
            attempts = maxOf(rAttempts, local.attempts),
            solvedCount = maxOf((remote["puzzleSolvedCount"] as? Number)?.toInt() ?: 0, local.solvedCount),
            solved = solved,
            failed = ((if (remoteWins) local.failed + rFailed else rFailed + local.failed) - solved),
        )
    }
}

/**
 * Chess.com-style profile card: avatar, editable @username, account details and ratings.
 * Shown at the top of the Review tab and from ⋮ → Profile / Account.
 */
@Composable
fun ProfileCard(
    username: String,
    displayName: String?,
    email: String?,
    photoUrl: String?,
    stats: ProfileStats,
    cloud: CloudUi,
    onSaveUsername: (String) -> String?,
) {
    var editing by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(photoUrl, displayName ?: username, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("@$username", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        IconButton(onClick = { editing = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Edit, "Edit username", Modifier.size(18.dp)) }
                    }
                    Text(displayName ?: if (email == null) "Guest" else "Signed in", style = MaterialTheme.typography.bodyMedium)
                    email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Stat("Puzzles", stats.puzzleRating.toString() + if (stats.puzzleProvisional) "?" else "", Modifier.weight(1f))
                Stat("Online", stats.onlineRating.toString(), Modifier.weight(1f))
                Stat("Games", stats.gamesPlayed.toString(), Modifier.weight(1f))
                Stat("Accuracy", stats.averageAccuracy?.let { "%.1f%%".format(it) } ?: "–", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            when {
                !cloud.available -> Text("Saved on this phone. Cloud backup isn't set up in this build.", style = MaterialTheme.typography.bodySmall)
                cloud.account == null -> {
                    Text("Sign in to back up games, puzzles and your username.", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = cloud.onSignIn, modifier = Modifier.padding(top = 4.dp)) { Text("Sign in / Sign up") }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = cloud.onSync) { Text("Sync now") }
                    TextButton(onClick = cloud.onSignOut) { Text("Sign out") }
                }
            }
        }
    }
    if (editing) UsernameDialog(username, onDismiss = { editing = false }, onSave = { n -> onSaveUsername(n).also { if (it == null) editing = false } })
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun UsernameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var text by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit username") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(21); error = null }, singleLine = true,
                prefix = { Text("@") }, isError = error != null,
                supportingText = { Text(error ?: "3–20 letters, digits or underscores. Shown to online opponents.") },
            )
        },
        confirmButton = { Button(onClick = { error = onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Google profile photo when there is one, else a coloured monogram. */
@Composable
fun Avatar(photoUrl: String?, name: String, size: Dp) {
    var bitmap by remember(photoUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photoUrl) {
        if (photoUrl != null) bitmap = withContext(Dispatchers.IO) {
            runCatching {
                (URL(photoUrl).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8_000; readTimeout = 8_000
                    try { inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } } finally { disconnect() }
                }
            }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        val palette = listOf(0xFF81B64C, 0xFF5C8BB0, 0xFFE58F2A, 0xFF1BACA6, 0xFFA88865, 0xFFCA3431)
        val color = Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
        Box(Modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
        }
    }
}

/** ⋮ → Profile / Account. */
@Composable
fun ProfileDialog(content: @Composable () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { content() },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
