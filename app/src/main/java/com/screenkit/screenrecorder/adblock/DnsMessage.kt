package com.screenkit.screenrecorder.adblock

import java.util.Locale

/** Strict, bounded DNS question parsing. No packet contents or names are logged/persisted. */
internal object DnsMessage {
    data class Question(val name: String, val type: Int, val clazz: Int, val end: Int, val udpLimit: Int)
    fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 255) shl 8) or (b[at + 1].toInt() and 255)
    fun put16(b: ByteArray, at: Int, n: Int) { b[at] = (n ushr 8).toByte(); b[at + 1] = n.toByte() }
    fun question(b: ByteArray): Question? = runCatching {
        require(b.size in 17..65535 && u16(b, 4) == 1 && (b[2].toInt() and 0x78) == 0)
        var at = 12
        val labels = mutableListOf<String>()
        var nameLength = 0
        while (true) {
            val length = b[at++].toInt() and 255
            if (length == 0) break
            // Compressed questions aren't emitted by ordinary stub resolvers. Reject rather than follow pointers.
            require(length in 1..63 && at + length < b.size)
            val label = b.copyOfRange(at, at + length)
            require(label.all { (it.toInt() and 255) in 33..126 && it != '.'.code.toByte() })
            labels += String(label, Charsets.US_ASCII)
            nameLength += length + 1
            require(nameLength <= 253)
            at += length
        }
        require(at + 4 <= b.size)
        val type = u16(b, at); val clazz = u16(b, at + 2); val end = at + 4
        var udpLimit = 512
        // Only inspect the common single OPT record on a request; never trust lengths from the wire.
        if ((b[2].toInt() and 0x80) == 0 && u16(b, 6) == 0 && u16(b, 8) == 0 && u16(b, 10) == 1 &&
            end + 11 <= b.size && b[end] == 0.toByte() && u16(b, end + 1) == 41) {
            require(end + 11 + u16(b, end + 9) == b.size)
            udpLimit = u16(b, end + 3).coerceIn(512, 1232)
        }
        Question(labels.joinToString(".").lowercase(Locale.ROOT), type, clazz, end, udpLimit)
    }.getOrNull()

    fun isQuery(b: ByteArray) = b.size in 17..4096 && (b[2].toInt() and 0x80) == 0 && question(b) != null
    fun reply(query: ByteArray, rcode: Int, truncated: Boolean = false): ByteArray {
        val end = question(query)?.end ?: 12.coerceAtMost(query.size)
        val result = query.copyOf(end)
        if (result.size < 12) return ByteArray(0)
        // QR, echoed RD/CD, RA. No AD bit: local blocked answers are not DNSSEC-authenticated.
        result[2] = (0x80 or (query[2].toInt() and 1) or if (truncated) 2 else 0).toByte()
        result[3] = (0x80 or (query[3].toInt() and 0x10) or rcode).toByte()
        for (at in 6..11) result[at] = 0
        return result
    }
    fun validReply(query: ByteArray, reply: ByteArray): Boolean {
        val q = question(query) ?: return false
        val r = question(reply) ?: return false
        return reply[0] == query[0] && reply[1] == query[1] && (reply[2].toInt() and 0x80) != 0 &&
            q.name == r.name && q.type == r.type && q.clazz == r.clazz
    }
}

internal class DomainBlocklist(lines: Sequence<String>) {
    private val domains = lines.map { it.substringBefore('#').trim().trimEnd('.').lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }.onEach { domain ->
            require(domain.length <= 253 && domain.contains('.') && domain.split('.').all { label ->
                label.length in 1..63 && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            })
        }.toSet()
    val size get() = domains.size
    fun blocks(name: String): Boolean {
        var suffix = name.trimEnd('.').lowercase(Locale.ROOT)
        while (suffix.isNotEmpty()) {
            if (suffix in domains) return true
            val dot = suffix.indexOf('.')
            if (dot < 0) break
            suffix = suffix.substring(dot + 1)
        }
        return false
    }
}
