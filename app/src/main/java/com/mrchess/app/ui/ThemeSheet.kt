package com.mrchess.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Bottom sheet with a live 4×4 mini-board swatch per theme. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardThemeSheet(current: BoardTheme, onPick: (BoardTheme) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Board theme", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BoardTheme.entries.forEach { t ->
                    Column(
                        Modifier.weight(1f).clickable { onPick(t) }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            border = if (t == current) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Column(Modifier.clip(RoundedCornerShape(8.dp))) {
                                for (r in 0 until 4) Row {
                                    for (f in 0 until 4) {
                                        val color = if (r == 1 && f == 2) t.lastMove.compositeOver((if ((r + f) % 2 == 0) t.light else t.dark))
                                        else if ((r + f) % 2 == 0) t.light else t.dark
                                        Box(Modifier.size(14.dp).background(color))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            t.label, style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (t == current) FontWeight.Bold else FontWeight.Normal, maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/** Bottom sheet listing the board sound packs; tapping one selects it and plays a sample. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundPackSheet(current: com.mrchess.app.audio.SoundPack, onPick: (com.mrchess.app.audio.SoundPack) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Board sounds", style = MaterialTheme.typography.titleMedium)
            Text("Tap a pack to hear it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            com.mrchess.app.audio.SoundPack.entries.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(p) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = p == current, onClick = { onPick(p) })
                    Text(p.label, fontWeight = if (p == current) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
