package com.screenkit.screenrecorder.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenkit.screenrecorder.data.CaptureAudioMode
import com.screenkit.screenrecorder.streaming.LiveDestination
import com.screenkit.screenrecorder.streaming.LiveStreamConfig
import com.screenkit.screenrecorder.streaming.LiveStreamSession
import kotlinx.coroutines.delay

@Composable
internal fun LiveStreamCard(
    busy: Boolean,
    audioMode: CaptureAudioMode,
    onAudioMode: (CaptureAudioMode) -> Unit,
    onStart: (LiveStreamConfig) -> Unit,
    onStop: () -> Unit,
    onMute: () -> Unit,
    setupRequest: Int = 0,
) {
    val context = LocalContext.current
    val status by LiveStreamSession.status.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<LiveDestination?>(null) }
    var credentials by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    // Never save stream keys/server URLs to saved-instance-state, preferences, diagnostics or disk.
    var server by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var edge by remember { mutableStateOf(720) }
    var fps by remember { mutableStateOf(30) }
    var bitrate by remember { mutableStateOf("2500") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    fun closeSetup() { setup = false; confirmation = false; key = ""; server = ""; destination = null; error = null }
    fun openSetup() { destination = null; credentials = false; advanced = false; key = ""; server = ""; error = null; setup = true }
    LaunchedEffect(setupRequest) { if (setupRequest > 0 && !status.active) openSetup() }
    LaunchedEffect(status.active, starting) {
        if (status.active) {
            closeSetup(); starting = false
            while (true) { tick = System.currentTimeMillis(); delay(1000) }
        } else if (starting) { delay(1500); starting = false }
    }
    val editable = !status.active && !busy && !starting
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Go Live", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Choose YouTube, Facebook, TikTok or your own streaming service.", style = MaterialTheme.typography.bodySmall)
            Text(status.message, color = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (status.active) {
                val seconds = if (status.startedAt == 0L) 0L else ((tick - status.startedAt) / 1000).coerceAtLeast(0)
                Text("%02d:%02d · %d kbps · %s".format(seconds / 60, seconds % 60, status.bitrate / 1000, if (status.muted) "Audio muted" else "Audio on"))
                Text("Server connected does not mean publicly live. Check your platform's dashboard.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMute) { Text(if (status.muted) "Unmute audio" else "Mute audio") }
                    Button(onClick = onStop) { Text("End stream") }
                }
            } else {
                Button(enabled = editable, onClick = { openSetup() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Finish the active capture first" else if (starting) "Starting…" else "Go Live")
                }
                Text("Start with a platform. Advanced encoder settings are optional. Account linking is not configured; screen broadcasting still needs a stream key from an eligible account.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (setup && !status.active && !confirmation) AlertDialog(
        onDismissRequest = { closeSetup() },
        title = { Text(destination?.let { "Go Live · ${it.label}" } ?: "Where do you want to go live?") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val target = destination
                if (target == null) {
                    Text("Pick a platform to see its setup options.")
                    LiveDestination.entries.forEach { choice ->
                        OutlinedButton(onClick = {
                            destination = choice; server = choice.defaultServer; key = ""; error = null
                            credentials = choice == LiveDestination.CUSTOM
                        }, modifier = Modifier.fillMaxWidth()) { Text(choice.label) }
                    }
                } else {
                    Text(target.guidance)
                    target.dashboardUrl?.let { url ->
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { error = "No app/browser could open this platform. Open it manually, then return here." }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open ${target.label}") }
                    }
                    Text("Opens the platform or browser—not an account connection to ScreenKit. Starting LIVE inside that app is separate from broadcasting your screen here.", style = MaterialTheme.typography.bodySmall)
                    if (!credentials) {
                        Button(enabled = editable, onClick = { credentials = true }, modifier = Modifier.fillMaxWidth()) { Text("I have a stream key") }
                    } else {
                        OutlinedTextField(value = server, onValueChange = { server = it; error = null }, label = { Text("Server URL") },
                            placeholder = { Text("rtmps://server.example/live") }, singleLine = true, enabled = editable,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = key, onValueChange = { key = it; error = null }, label = { Text("Stream key · not saved") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = editable,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
                        Text("Use the exact values from your platform. Preset URLs can be replaced.", style = MaterialTheme.typography.bodySmall)
                        Text("Sound")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CaptureAudioMode.entries.forEach { choice -> FilterChip(selected = audioMode == choice, enabled = editable,
                                onClick = { onAudioMode(choice) }, label = { Text(choice.label) }) }
                        }
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Advanced settings · ${edge}p / $fps fps") }
                        if (advanced) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(480, 720, 1080).forEach { value -> FilterChip(selected = edge == value, enabled = editable,
                                    onClick = { edge = value; bitrate = when (value) { 480 -> "1200"; 720 -> "2500"; else -> "4500" } }, label = { Text("${value}p") }) }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(24, 30, 60).forEach { value -> FilterChip(selected = fps == value, enabled = editable,
                                    onClick = { fps = value }, label = { Text("$value fps") }) }
                            }
                            OutlinedTextField(value = bitrate, onValueChange = { bitrate = it.filter(Char::isDigit).take(5) }, enabled = editable,
                                label = { Text("Bitrate · 500–12000 kbps") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        }
                        if (server.trim().startsWith("rtmp://")) Text("RTMP is unencrypted. Prefer RTMPS.", color = MaterialTheme.colorScheme.error)
                        Button(enabled = editable, onClick = {
                            val config = LiveStreamConfig(server, key, edge, fps, bitrate.toIntOrNull() ?: 0)
                            val failure = runCatching { config.validate() }.exceptionOrNull()
                            if (failure != null) error = failure.message else confirmation = true
                        }, modifier = Modifier.fillMaxWidth()) { Text("Start screen broadcast") }
                        Text("Screen only · no local MP4 · protected content cannot be streamed.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { destination = null; credentials = false; key = ""; server = ""; error = null }) { Text("Choose another platform") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { closeSetup() }) { Text("Close") } },
    )
    if (confirmation) AlertDialog(
        onDismissRequest = { confirmation = false },
        title = { Text("Share your screen live?") },
        text = { Text("Visible notifications, overlays and personal information may be broadcast. Enable Do Not Disturb first.\n\nSound: ${audioMode.label}. Some platforms require a final Go Live confirmation in their dashboard.") },
        confirmButton = { TextButton(enabled = editable, onClick = {
            val config = LiveStreamConfig(server, key, edge, fps, bitrate.toIntOrNull() ?: 0)
            val failure = runCatching { config.validate() }.exceptionOrNull()
            if (failure != null) { confirmation = false; error = failure.message }
            else { closeSetup(); starting = true; onStart(config) }
        }) { Text("Continue to screen sharing") } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text("Back") } },
    )
}
