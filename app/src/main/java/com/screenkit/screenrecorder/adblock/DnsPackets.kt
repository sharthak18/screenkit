package com.screenkit.screenrecorder.adblock

/** IPv4 only: the VPN routes just its synthetic IPv4 DNS address; IPv6 data traffic bypasses it. */
internal object DnsPackets {
    val DNS_ADDRESS = byteArrayOf(192.toByte(), 0, 2, 2)
    data class Packet(val source: ByteArray, val destination: ByteArray, val sourcePort: Int,
                      val destinationPort: Int, val protocol: Int, val seq: Long, val ack: Long,
                      val flags: Int, val window: Int, val payload: ByteArray)
    fun parse(bytes: ByteArray): Packet? = runCatching {
        require(bytes.size >= 28 && (bytes[0].toInt() ushr 4 and 15) == 4)
        val header = (bytes[0].toInt() and 15) * 4
        val length = DnsMessage.u16(bytes, 2)
        require(header >= 20 && length in (header + 8)..bytes.size)
        require(DnsMessage.u16(bytes, 6) and 0x3fff == 0) // No fragments.
        require(checksum(bytes.copyOfRange(0, header)) == 0)
        val proto = bytes[9].toInt() and 255
        require(proto == 6 || proto == 17)
        val source = bytes.copyOfRange(12, 16); val dest = bytes.copyOfRange(16, 20)
        require(dest.contentEquals(DNS_ADDRESS))
        val segment = bytes.copyOfRange(header, length)
        if (proto == 17) {
            require(DnsMessage.u16(segment, 4) == segment.size)
            require(DnsMessage.u16(segment, 6) == 0 || transportChecksum(source, dest, proto, segment) == 0)
            Packet(source, dest, DnsMessage.u16(segment, 0), DnsMessage.u16(segment, 2), proto,
                0, 0, 0, 0, segment.copyOfRange(8, segment.size))
        } else {
            require(segment.size >= 20)
            val offset = (segment[12].toInt() ushr 4 and 15) * 4
            require(offset in 20..segment.size && transportChecksum(source, dest, proto, segment) == 0)
            Packet(source, dest, DnsMessage.u16(segment, 0), DnsMessage.u16(segment, 2), proto,
                u32(segment, 4), u32(segment, 8), segment[13].toInt() and 255,
                DnsMessage.u16(segment, 14), segment.copyOfRange(offset, segment.size))
        }
    }.getOrNull()
    fun udp(p: Packet, payload: ByteArray): ByteArray {
        val segment = ByteArray(8 + payload.size)
        DnsMessage.put16(segment, 0, p.destinationPort); DnsMessage.put16(segment, 2, p.sourcePort)
        DnsMessage.put16(segment, 4, segment.size); payload.copyInto(segment, 8)
        DnsMessage.put16(segment, 6, transportChecksum(p.destination, p.source, 17, segment).let { if (it == 0) 65535 else it })
        return ip(p.destination, p.source, 17, segment)
    }
    fun tcp(p: Packet, seq: Long, ack: Long, flags: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        // Advertise MSS 1200 in SYN-ACK. No window scaling/SACK/timestamps negotiated.
        val header = if (flags and 2 != 0) 24 else 20
        val segment = ByteArray(header + payload.size)
        DnsMessage.put16(segment, 0, p.destinationPort); DnsMessage.put16(segment, 2, p.sourcePort)
        put32(segment, 4, seq); put32(segment, 8, ack)
        segment[12] = ((header / 4) shl 4).toByte(); segment[13] = flags.toByte()
        DnsMessage.put16(segment, 14, 8192)
        if (header == 24) { segment[20] = 2; segment[21] = 4; DnsMessage.put16(segment, 22, 1200) }
        payload.copyInto(segment, header)
        DnsMessage.put16(segment, 16, transportChecksum(p.destination, p.source, 6, segment))
        return ip(p.destination, p.source, 6, segment)
    }
    fun ip(source: ByteArray, dest: ByteArray, proto: Int, segment: ByteArray): ByteArray {
        val b = ByteArray(20 + segment.size)
        b[0] = 0x45; DnsMessage.put16(b, 2, b.size); b[8] = 64; b[9] = proto.toByte()
        source.copyInto(b, 12); dest.copyInto(b, 16)
        DnsMessage.put16(b, 10, checksum(b.copyOfRange(0, 20)))
        segment.copyInto(b, 20)
        return b
    }
    private fun transportChecksum(source: ByteArray, dest: ByteArray, proto: Int, segment: ByteArray): Int {
        val pseudo = ByteArray(12 + segment.size)
        source.copyInto(pseudo); dest.copyInto(pseudo, 4); pseudo[9] = proto.toByte()
        DnsMessage.put16(pseudo, 10, segment.size); segment.copyInto(pseudo, 12)
        return checksum(pseudo)
    }
    fun checksum(b: ByteArray): Int {
        var sum = 0L
        for (i in b.indices step 2) sum += ((b[i].toInt() and 255) shl 8) + if (i + 1 < b.size) b[i + 1].toInt() and 255 else 0
        while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
        return sum.toInt().inv() and 65535
    }
    fun u32(b: ByteArray, at: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or (b[at + i].toLong() and 255) }
    private fun put32(b: ByteArray, at: Int, n: Long) { for (i in 0..3) b[at + i] = (n ushr ((3 - i) * 8)).toByte() }
}
