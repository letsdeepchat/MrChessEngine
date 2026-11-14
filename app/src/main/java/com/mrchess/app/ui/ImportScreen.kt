package com.mrchess.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrchess.core.rules.Position
import com.mrchess.core.rules.Side

@Composable
fun ImportScreen(vm: MainViewModel, s: ImportState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importImage(uri)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Import a position or game", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.text, onValueChange = vm::setImportText,
            modifier = Modifier.fillMaxWidth().height(180.dp),
            label = { Text("Paste FEN or PGN") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = vm::importText) { Text("Analyze") }
            OutlinedButton(onClick = { vm.reviewPgn(s.text) }, enabled = s.text.contains('.')) { Text("Review PGN") }
        }
        Spacer(Modifier.height(16.dp))
        Text("From a screenshot", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick a saved image of a 2D board (or share one to this app). Recognition runs on-device; nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Choose image…") }

        if (s.recognizing) Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp)); Text("Detecting board and pieces…")
        }

        s.recognition?.let { r ->
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(r.boardBitmap.asImageBitmap(), "Detected board", Modifier.size(140.dp))
                val fen = r.toFen(s.sideToMove, s.blackAtBottom)
                val pos = runCatching { Position.fromFen(fen) }.getOrNull()
                if (pos != null) ChessBoard(pos, Modifier.width(180.dp))
            }
            Text(
                "Mean confidence ${"%.0f".format(r.meanConfidence * 100)}%" +
                    r.lowConfidenceSquares(s.blackAtBottom).let { low -> if (low.isNotEmpty()) " — check: ${low.joinToString()}" else "" },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.sideToMove == Side.WHITE, { vm.setImportSide(Side.WHITE) }, { Text("White to move") })
                FilterChip(s.sideToMove == Side.BLACK, { vm.setImportSide(Side.BLACK) }, { Text("Black to move") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Black pieces at bottom of image", Modifier.weight(1f))
                Switch(s.blackAtBottom, vm::setBlackAtBottom)
            }
            val fen = vm.recognizedFen()
            fen?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
            fen?.let { Position.validate(it) }?.takeIf { it.isNotEmpty() }?.let { probs ->
                Text("Needs fixing: ${probs.joinToString("; ")}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = vm::analyzeRecognized) { Text("Analyze") }
                OutlinedButton(onClick = { fen?.let { vm.playFromHere(it, s.sideToMove) } }) { Text("Play from here") }
                OutlinedButton(onClick = vm::editRecognizedAsText) { Text("Edit FEN") }
            }
        }

        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}
