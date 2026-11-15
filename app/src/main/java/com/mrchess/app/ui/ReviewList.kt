package com.mrchess.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mrchess.app.data.GameRecordEntity
import java.text.DateFormat
import java.util.Date

data class CloudUi(
    val available: Boolean,
    val account: String?,
    val onSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onSync: () -> Unit,
    val name: String? = null,
)

@Composable
fun ReviewList(
    saved: List<GameRecordEntity>,
    cloud: CloudUi,
    onOpen: (GameRecordEntity) -> Unit,
    onDelete: (GameRecordEntity) -> Unit,
    onClear: (alsoCloud: Boolean) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            CloudCard(cloud)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your games (${saved.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (saved.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear history") }
            }
            if (saved.isEmpty()) Text(
                "Finished games from Play and Two Player, and games you review, are kept here until you delete them.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(saved, key = { it.id }) { g ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(g) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.title, fontWeight = FontWeight.SemiBold)
                    val acc = listOfNotNull(
                        g.whiteAccuracy?.let { "W %.0f%%".format(it) },
                        g.blackAccuracy?.let { "B %.0f%%".format(it) },
                    ).joinToString(" · ")
                    Text(
                        "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(g.timestamp))}  ${g.result}" +
                            (if (acc.isNotEmpty()) "  ·  $acc" else "") + (if (cloud.account != null && g.isSynced) "  ☁" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { onDelete(g) }) { Text("Delete") }
            }
            HorizontalDivider()
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text(if (cloud.account != null) "Delete games from this phone only, or from this phone and your cloud backup?" else "Delete all saved games from this phone?") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { confirmClear = false; onClear(false) }) { Text("This phone") }
                if (cloud.account != null) TextButton(onClick = { confirmClear = false; onClear(true) }) { Text("Phone + cloud") }
            }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun CloudCard(cloud: CloudUi) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            when {
                !cloud.available -> {
                    Text("Saved on this phone", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Your games stay here until you delete them or clear the app's storage. Cloud backup isn't set up in this build.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                cloud.account == null -> {
                    Text("Back up your history", fontWeight = FontWeight.SemiBold)
                    Text("Optional. Sign in with Google to keep your games if you clear the app or change phones.", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = cloud.onSignIn, modifier = Modifier.padding(top = 6.dp)) { Text("Sign in with Google to sync history") }
                }
                else -> {
                    Text(cloud.name ?: "Signed in", fontWeight = FontWeight.SemiBold)
                    Text("${cloud.account} · games sync automatically", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = cloud.onSync) { Text("Sync now") }
                        TextButton(onClick = cloud.onSignOut) { Text("Sign out") }
                    }
                }
            }
        }
    }
}
