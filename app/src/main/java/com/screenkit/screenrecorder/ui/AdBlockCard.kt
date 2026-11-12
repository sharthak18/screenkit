package com.screenkit.screenrecorder.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenkit.screenrecorder.adblock.AdBlockConsentActivity
import com.screenkit.screenrecorder.adblock.AdBlockState
import com.screenkit.screenrecorder.adblock.AdBlockVpnService

@Composable
internal fun AdBlockCard() {
    val context = LocalContext.current
    val status by AdBlockState.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AdBlockState.refresh(context) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Network ad filtering", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Experimental · automatically runs with floating controls after you opt in.", style = MaterialTheme.typography.bodySmall)
            Text(status.message, color = if (status.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text("${status.blocked} / ${status.queries} DNS queries blocked this session — not a count of ads removed.", style = MaterialTheme.typography.bodySmall)
            Text("Small on-device domain list; allowed DNS queries go encrypted to Cloudflare. No browsing history saved. Not a full VPN or guaranteed social-video ad blocker. Apps using their own DNS can bypass it.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!status.active) Button(onClick = {
                    context.startActivity(Intent(context, AdBlockConsentActivity::class.java))
                }) { Text(if (status.enabled) "Start / retry" else "Set up filtering") }
                if (status.enabled) OutlinedButton(onClick = { AdBlockVpnService.disable(context) }) { Text("Turn off") }
            }
            Text("Uses Android's VPN slot. Stop other VPNs and strict Private DNS first. Closing the bubble stops filtering; turning this off also disables automatic start.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
