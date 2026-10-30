package com.screenkit.screenrecorder.adblock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.screenkit.screenrecorder.services.FloatingOverlayService

/** No consent on boot/background start: only a direct tap can launch this screen. */
class AdBlockConsentActivity : ComponentActivity() {
    private var awaitingSystem = false
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && VpnService.prepare(this) == null) enable()
        else { AdBlockState.set("Not filtering — VPN consent was declined"); finish() }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpn() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingSystem = savedInstanceState?.getBoolean("awaitingSystem") ?: false
        if (awaitingSystem) return // ActivityResultRegistry restores outstanding system consent.
        showDisclosure()
    }
    private fun showDisclosure() {
        AlertDialog.Builder(this).setTitle("Network ad filtering (experimental)")
            .setMessage("ScreenKit uses Android's VPN permission to filter this device's ordinary DNS requests while its floating controls run—even when another app is on screen. Only DNS to a virtual resolver enters the local tunnel; other traffic goes directly to the network.\n\n" +
                "A small bundled list blocks known ad domains. Allowed queries go encrypted to Cloudflare DNS (cloudflare-dns.com), which receives the requested domains and your public IP under its own privacy policy. ScreenKit saves no DNS history and installs no certificate.\n\n" +
                "Does not reliably block YouTube/Facebook/Instagram video ads, cached ads, or apps using their own encrypted DNS. A block can break a site; Turn off restores normal DNS. Another VPN cannot run alongside this. If another VPN is active, stop it first. Strict Android Private DNS must be off. Do not enable Android's Always-on VPN or Block connections without VPN for this split DNS tool.\n\n" +
                "After this one-time setup, filtering starts with the floating controls and stops when you close them. A visible notification has a Turn off action. Resolver failure stops filtering and restores normal networking rather than blocking your connection.")
            .setPositiveButton("Agree & enable") { _, _ ->
                if (!Settings.canDrawOverlays(this)) {
                    AdBlockState.set("Enable ScreenKit floating controls first, then enable Ad filtering")
                    finish()
                } else if (Build.VERSION.SDK_INT >= 33) { awaitingSystem = true; notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                else requestVpn()
            }
            .setNeutralButton("Resolver privacy") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://developers.cloudflare.com/1.1.1.1/privacy/public-dns-resolver/"))) }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
    private fun requestVpn() {
        // Don't launch prepare() over another VPN. Users must explicitly stop it in Android first.
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        if (!AdBlockState.status.value.active && cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }) {
            AdBlockState.set("Stop your other VPN in Android settings, then enable Ad filtering")
            finish(); return
        }
        val consent = VpnService.prepare(this)
        if (consent == null) enable() else { awaitingSystem = true; vpnConsent.launch(consent) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingSystem", awaitingSystem)
        super.onSaveInstanceState(outState)
    }
    private fun enable() {
        AdBlockState.setEnabled(this, true)
        FloatingOverlayService.show(this)
        // The overlay's start hook also calls this, handling the case where it isn't running yet.
        AdBlockVpnService.startIfEnabled(this)
        finish()
    }
}
