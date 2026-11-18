package com.mrchess.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val WelcomeTop = Color(0xFF1E1B2E)
private val WelcomeBottom = Color(0xFF2E2647)
private val Accent = Color(0xFF81B64C)
private val Muted = Color(0xFFB4ADC9)

/**
 * First-launch screen (also opened later from ⋮ or the Review tab). Signing in only backs up the
 * game history; "Play as Guest" opens the full app with every feature.
 */
@Composable
fun WelcomeScreen(
    busy: Boolean,
    cloudAvailable: Boolean,
    /** Opened later from the app (shows a close button instead of being the first screen). */
    dismissible: Boolean,
    onGoogle: () -> Unit,
    onEmail: (email: String, password: String, create: Boolean) -> Unit,
    onResetPassword: (String) -> Unit,
    onGuest: () -> Unit,
) {
    var showEmail by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(WelcomeTop, WelcomeBottom))),
    ) {
        if (dismissible) {
            IconButton(onClick = onGuest, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "Close", tint = Color.White)
            }
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            Box(
                Modifier.size(112.dp).background(Brush.linearGradient(listOf(Accent, Color(0xFF5C8BB0))), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("♞︎", fontSize = 72.sp, color = Color.White) }
            Spacer(Modifier.height(24.dp))
            Text("Mr. Chess Trainer", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Play. Analyze. Master Chess.", color = Muted, fontSize = 17.sp)
            Spacer(Modifier.height(20.dp))
            listOf(
                "Stockfish up to 3200, fully offline",
                "Best move + why, live coach and game review",
                "Grandmaster puzzles with your Elo estimate",
            ).forEach { Text("•  $it", color = Color(0xFFE6E1F2), fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) }
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = onGoogle, enabled = !busy && cloudAvailable,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F)),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                GoogleG(20.dp); Spacer(Modifier.width(12.dp))
                Text("Continue with Google", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showEmail = true }, enabled = !busy && cloudAvailable,
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Filled.Email, null); Spacer(Modifier.width(12.dp))
                Text("Continue with Email", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Text("  Signing in…", color = Muted)
            }
            TextButton(onClick = onGuest, enabled = !busy) {
                Text(if (dismissible) "Not now" else "Play as Guest", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Every feature works as a guest. Signing in only backs up your game history so you can review it on any phone.",
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
    if (showEmail) EmailAuthDialog(
        busy = busy,
        onDismiss = { showEmail = false },
        onSubmit = { e, p, create -> onEmail(e, p, create) },
        onReset = onResetPassword,
    )
}

/** E-mail + password sign-in / sign-up (Firebase Auth). */
@Composable
fun EmailAuthDialog(busy: Boolean, onDismiss: () -> Unit, onSubmit: (String, String, Boolean) -> Unit, onReset: (String) -> Unit) {
    var create by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val valid = email.contains('@') && email.contains('.') && password.length >= 6
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (create) "Create account" else "Sign in with e-mail") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!create, { create = false }, { Text("Sign in") })
                    FilterChip(create, { create = true }, { Text("Create account") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    email, { email = it.trim() }, label = { Text("E-mail") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password (6+ characters)") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide password" else "Show password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!create) TextButton(onClick = { onReset(email) }, enabled = email.contains('@') && !busy) { Text("Forgot password?") }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(email, password, create) }, enabled = valid && !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (create) "Create account" else "Sign in")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The multicolour Google "G", drawn as arcs (no image asset). */
@Composable
fun GoogleG(size: Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.22f
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = Offset(inset, inset)
        fun arc(color: Color, start: Float, sweep: Float) =
            drawArc(color, start, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
        arc(Color(0xFF4285F4), -5f, 50f)    // blue, right
        arc(Color(0xFF34A853), 45f, 90f)    // green, bottom
        arc(Color(0xFFFBBC05), 135f, 70f)   // yellow, left
        arc(Color(0xFFEA4335), 205f, 112f)  // red, top
        val cy = this.size.height / 2
        drawRect(Color(0xFF4285F4), topLeft = Offset(this.size.width / 2, cy - stroke / 2), size = Size(this.size.width / 2 - inset / 2, stroke))
    }
}
