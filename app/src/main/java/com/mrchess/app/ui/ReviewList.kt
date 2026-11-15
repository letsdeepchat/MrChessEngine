package com.mrchess.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mrchess.app.data.SavedGame

@Composable
fun ReviewList(saved: List<SavedGame>, onOpen: (SavedGame) -> Unit, onDelete: (SavedGame) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Your games", style = MaterialTheme.typography.titleMedium)
        if (saved.isEmpty()) Text(
            "Finished games from Play and Two Player appear here. Open one for a full move-by-move review with a summary.",
            style = MaterialTheme.typography.bodySmall,
        )
        saved.forEach { g ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(g) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.title, fontWeight = FontWeight.SemiBold)
                    Text("${g.date}  ${g.result}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onDelete(g) }) { Text("Delete") }
            }
            HorizontalDivider()
        }
    }
}
