package com.screenkit.screenrecorder.adblock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.screenkit.screenrecorder.MainActivity
import com.screenkit.screenrecorder.R
import com.screenkit.screenrecorder.services.FloatingOverlayService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Split DNS VPN: only 192.0.2.2/32 enters TUN, never app video/HTTPS traffic. */
class AdBlockVpnService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val failures = AtomicInteger(0)
    private val failing = AtomicBoolean(false)
    private var started = false
    private var tunnel: ParcelFileDescriptor? = null
    private var gateway: DnsPacketGateway? = null
    private var reader: Thread? = null
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(32))
    private lateinit var blocklist: DomainBlocklist
    private val ticker = object : Runnable {
        override fun run() {
            if (closed.get()) return
            if (!FloatingOverlayService.isRunning) { stopSelf(); return }
            if (strictPrivateDns()) { fail("Stopped: turn off strict Private DNS before enabling this DNS filter"); return }
            gateway?.tick()
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) }
            main.postDelayed(this, 1000)
        }
    }
    companion object {
        private const val CHANNEL = "dns_filter"
        private const val NOTIFICATION = 5401
        private const val DISABLE = "screenkit.adfilter.DISABLE"
        @Volatile private var running = false
        fun startIfEnabled(context: Context) {
            AdBlockState.refresh(context)
            if (!AdBlockState.enabled(context) || running || !FloatingOverlayService.isRunning) return
            if (prepare(context) != null) { AdBlockState.set("VPN permission needed — open Ad filtering to enable"); return }
            val manager = context.getSystemService(ConnectivityManager::class.java)
            if (manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
                AdBlockState.set("Not filtering: another VPN is active. Stop it, then retry.")
                return
            }
            runCatching {
                val intent = Intent(context, AdBlockVpnService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { AdBlockState.set("Could not start filtering — open ScreenKit and retry") }
        }
        fun disable(context: Context) {
            AdBlockState.setEnabled(context, false)
            context.stopService(Intent(context, AdBlockVpnService::class.java))
        }
        fun stopWithOverlay(context: Context) {
            context.stopService(Intent(context, AdBlockVpnService::class.java))
            if (AdBlockState.enabled(context)) AdBlockState.set("Ready — starts with floating controls")
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISABLE) { disable(this); stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        if (!AdBlockState.enabled(this) || !FloatingOverlayService.isRunning) { stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Network ad filtering", NotificationManager.IMPORTANCE_LOW))
            AdBlockState.set("Starting DNS filter…", resetCounts = true)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification())
            if (strictPrivateDns()) { fail("Not filtering: turn off strict Private DNS in Android settings first"); return START_NOT_STICKY }
            if (prepare(this) != null) { fail("VPN permission needed — enable Ad filtering again"); return START_NOT_STICKY }
            // Never take over an existing VPN automatically, even if consent changed during startup.
            val cm = getSystemService(ConnectivityManager::class.java)
            if (cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
                fail("Not filtering: another VPN is active"); return START_NOT_STICKY
            }
            blocklist = assets.open("ad-domains.txt").bufferedReader().use { DomainBlocklist(it.readLines().asSequence()) }
            val open = PendingIntent.getActivity(this, 5400, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            tunnel = Builder().setSession("ScreenKit DNS ad filter")
                .setConfigureIntent(open).setMtu(1500).setBlocking(false)
                .addAddress("192.0.2.1", 32).addDnsServer("192.0.2.2").addRoute("192.0.2.2", 32)
                .allowFamily(OsConstants.AF_INET6) // Do not black-hole IPv6 app traffic.
                .establish() ?: error("VPN permission unavailable")
            val descriptor = tunnel!!.fileDescriptor
            gateway = DnsPacketGateway(::writePacket, ::resolve, SystemClock::elapsedRealtime)
            running = true
            AdBlockState.set("Filtering DNS · ${blocklist.size} starter domains · Cloudflare encrypted resolver", active = true)
            reader = Thread({
                val buffer = ByteArray(32767)
                try {
                    while (!closed.get()) {
                        val poll = android.system.StructPollfd().apply {
                            fd = descriptor; events = OsConstants.POLLIN.toShort()
                        }
                        if (android.system.Os.poll(arrayOf(poll), 250) == 0) continue
                        if (closed.get()) break
                        val count = try { android.system.Os.read(descriptor, buffer, 0, buffer.size) }
                        catch (error: android.system.ErrnoException) {
                            if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                            throw error
                        }
                        if (count <= 0) break
                        gateway?.accept(buffer.copyOf(count))
                    }
                    if (!closed.get()) fail("Filtering stopped: VPN connection closed. Retry in ScreenKit.")
                } catch (_: Exception) {
                    if (!closed.get()) fail("Filtering stopped: VPN connection interrupted. Retry in ScreenKit.")
                }
            }, "ScreenKitDnsPackets").also { it.start() }
            main.post(ticker)
        } catch (_: Exception) { fail("Could not start DNS filtering. Normal networking is restored; retry in ScreenKit.") }
        return START_NOT_STICKY
    }
    private fun strictPrivateDns(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { network -> cm.getLinkProperties(network)?.privateDnsServerName?.isNotBlank() == true }
    }
    private fun writePacket(bytes: ByteArray) {
        if (closed.get()) return
        try { tunnel?.fileDescriptor?.let { android.system.Os.write(it, bytes, 0, bytes.size) } }
        catch (error: android.system.ErrnoException) {
            // Backpressure drops a packet; UDP clients retry, TCP retransmission is bounded by the gateway.
            if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR && !closed.get())
                fail("Filtering stopped: DNS tunnel failed. Normal networking is restored.")
        } catch (_: Exception) {
            if (!closed.get()) fail("Filtering stopped: DNS tunnel failed. Normal networking is restored.")
        }
    }
    private fun resolve(query: ByteArray, done: (ByteArray) -> Unit) {
        if (closed.get()) return
        val blocked = blocklist.blocks(DnsMessage.question(query)!!.name)
        AdBlockState.counted(blocked)
        if (blocked) { done(DnsMessage.reply(query, 3)); return }
        try {
            workers.execute {
                if (closed.get()) return@execute
                val reply = try {
                    val answer = encryptedLookup(query)
                    check(DnsMessage.validReply(query, answer))
                    failures.set(0)
                    answer
                } catch (_: Exception) {
                    // Fail open after repeated resolver failures; no persistent dead DNS tunnel.
                    if (!closed.get() && failures.incrementAndGet() >= 3) fail("Filtering stopped: encrypted DNS is unreachable. Normal networking is restored; retry when connected.")
                    DnsMessage.reply(query, 2)
                }
                if (!closed.get()) done(reply)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { done(DnsMessage.reply(query, 2)) }
    }
    private fun encryptedLookup(query: ByteArray): ByteArray {
        val cm = getSystemService(ConnectivityManager::class.java)
        val ipv4 = cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true &&
                cm.getLinkProperties(n)?.linkAddresses?.any { it.address is Inet4Address } == true
        }
        // Literal addresses avoid circular DNS resolution through this tunnel. No plaintext fallback.
        val address = if (ipv4) "1.1.1.1" else "2606:4700:4700::1111"
        val raw = Socket()
        sockets.add(raw)
        val deadline = Runnable { runCatching { raw.close() } }
        main.postDelayed(deadline, 8000) // Absolute lookup bound, including handshake and a slow response.
        try {
            check(!closed.get() && protect(raw))
            raw.soTimeout = 3000
            raw.connect(InetSocketAddress(address, 853), 2500)
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, "cloudflare-dns.com", 853, true) as SSLSocket
            ssl.use {
                it.soTimeout = 3000
                it.sslParameters = it.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName("cloudflare-dns.com"))
                }
                it.startHandshake() // System trust + hostname verification, never a custom trust-all verifier.
                val out = DataOutputStream(it.outputStream)
                out.writeShort(query.size); out.write(query); out.flush()
                val input = DataInputStream(it.inputStream)
                val size = input.readUnsignedShort()
                require(size in 12..65535)
                return ByteArray(size).also(input::readFully)
            }
        } finally { main.removeCallbacks(deadline); sockets.remove(raw); runCatching { raw.close() } }
    }
    private fun fail(message: String) {
        if (!failing.compareAndSet(false, true)) return
        main.post {
            if (!closed.get()) {
                AdBlockState.set(message)
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
    }
    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_capture).setContentTitle("ScreenKit DNS ad filtering")
        .setContentText("${AdBlockState.status.value.blocked} DNS queries blocked · tap to manage")
        .setContentIntent(PendingIntent.getActivity(this, 5400, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .addAction(0, "Turn off", PendingIntent.getService(this, 5401, Intent(this, javaClass).setAction(DISABLE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()
    override fun onRevoke() {
        AdBlockState.setEnabled(this, false)
        AdBlockState.set("VPN permission revoked or another VPN started — filtering is off")
        stopSelf()
    }
    override fun onDestroy() {
        closed.set(true); running = false
        main.removeCallbacksAndMessages(null)
        gateway?.close(); gateway = null
        sockets.forEach { runCatching { it.close() } }; sockets.clear()
        workers.shutdownNow()
        runCatching { tunnel?.close() }; tunnel = null
        reader?.interrupt(); reader = null
        if (AdBlockState.status.value.active) AdBlockState.set("Ready — starts with floating controls")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
