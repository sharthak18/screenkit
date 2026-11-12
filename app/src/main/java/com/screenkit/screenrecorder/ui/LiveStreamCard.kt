package com.screenkit.screenrecorder.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenkit.screenrecorder.data.CaptureAudioMode
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
) {
    val status by LiveStreamSession.status.collectAsStateWithLifecycle()
    // Not rememberSaveable: keys must not enter saved-instance-state or disk.
    var server by remember { mutableStateOf("rtmps://a.rtmps.youtube.com/live2") }
    var key by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("YouTube") }
    var edge by remember { mutableStateOf(720) }
    var fps by remember { mutableStateOf(30) }
    var bitrate by remember { mutableStateOf("2500") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.active, starting) {
        if (status.active) {
            starting = false
            while (true) { tick = System.currentTimeMillis(); delay(1000) }
        } else if (starting) {
            // Permission/consent cancellation may not start a service. Allow another attempt.
            delay(1500)
            starting = false
        }
    }
    val editable = !status.active && !busy && !starting
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Go Live", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Your screen → YouTube, Facebook, or a custom RTMP/RTMPS destination.", style = MaterialTheme.typography.bodySmall)
            Text(status.message, color = if (status.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (status.active) {
                val seconds = if (status.startedAt == 0L) 0L else ((tick - status.startedAt) / 1000).coerceAtLeast(0)
                Text("%02d:%02d · %d kbps · %s".format(seconds / 60, seconds % 60, status.bitrate / 1000, if (status.muted) "Audio muted" else "Audio on"))
                Text("Connected means the server accepts the stream—not that it is publicly live. Check your platform's dashboard.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMute) { Text(if (status.muted) "Unmute audio" else "Mute audio") }
                    Button(onClick = onStop) { Text("End stream") }
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("YouTube", "Facebook", "Custom").forEach { choice ->
                        FilterChip(selected = platform == choice, enabled = editable, onClick = {
                            platform = choice
                            key = ""
                            server = when (choice) {
                                "YouTube" -> "rtmps://a.rtmps.youtube.com/live2"
                                "Facebook" -> "rtmps://live-api-s.facebook.com:443/rtmp"
                                else -> ""
                            }
                        }, label = { Text(choice) })
                    }
                }
                OutlinedTextField(value = server, onValueChange = { server = it; error = null }, label = { Text("Server URL") },
                    placeholder = { Text("rtmps://server.example/live") }, singleLine = true, enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = key, onValueChange = { key = it; error = null }, label = { Text("Stream key (not saved)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth())
                Text("Copy both values from your live dashboard. Preset URLs are starting points; replace them if your platform provides a different URL. No account sign-in or stream keys are stored.", style = MaterialTheme.typography.bodySmall)
                Text("Resolution · preserves screen aspect ratio")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(480, 720, 1080).forEach { value ->
                        FilterChip(selected = edge == value, enabled = editable, onClick = {
                            edge = value; bitrate = when(value) { 480 -> "1200"; 720 -> "2500"; else -> "4500" }
                        }, label = { Text("${value}p") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24, 30, 60).forEach { value ->
                        FilterChip(selected = fps == value, enabled = editable, onClick = { fps = value }, label = { Text("$value fps") })
                    }
                }
                OutlinedTextField(value = bitrate, onValueChange = { bitrate = it.filter(Char::isDigit).take(5) },
                    enabled = editable, label = { Text("Video bitrate · 500–12000 kbps") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text("Sound · choose before starting")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureAudioMode.entries.forEach { choice ->
                        FilterChip(selected = audioMode == choice, enabled = editable, onClick = { onAudioMode(choice) }, label = { Text(choice.label) })
                    }
                }
                if (server.trim().startsWith("rtmp://")) Text("RTMP is unencrypted. Prefer RTMPS to protect your stream and key in transit.", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = editable, onClick = {
                    val config = LiveStreamConfig(server, key, edge, fps, bitrate.toIntOrNull() ?: 0)
                    val failure = runCatching { config.validate() }.exceptionOrNull()
                    if (failure != null) error = failure.message else confirmation = true
                }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Finish the active capture first" else "Go Live") }
                Text("Screen only • No local MP4 • Device audio requires Android 10+ and app permission to capture playback • Protected content cannot be streamed.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmation) AlertDialog(
        onDismissRequest = { confirmation = false },
        title = { Text("Share your screen live?") },
        text = { Text("Everything in the screen or app you choose—including visible notifications, overlays, and personal information—may be broadcast. Enable Do Not Disturb first.\n\nYour sound setting is ${audioMode.label}. You can mute audio or end the stream from the floating controls or notification. Some platforms require a final Go Live confirmation in their dashboard.") },
        confirmButton = { TextButton(onClick = {
            confirmation = false
            starting = true
            val config = LiveStreamConfig(server, key, edge, fps, bitrate.toIntOrNull() ?: 2500)
            key = ""
            onStart(config)
        }) { Text("Continue to screen sharing") } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text("Cancel") } },
    )
}
