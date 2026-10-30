package com.screenkit.screenrecorder.adblock

import org.junit.Assert.*
import org.junit.Test

class DnsFilterTest {
    private fun query(name: String = "example.org", type: Int = 1): ByteArray {
        val bytes = mutableListOf<Byte>(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        for (label in name.split('.')) { bytes += label.length.toByte(); bytes += label.toByteArray(Charsets.US_ASCII).toList() }
        bytes += 0; bytes += (type ushr 8).toByte(); bytes += type.toByte(); bytes += 0; bytes += 1
        return bytes.toByteArray()
    }
    private val client = byteArrayOf(192.toByte(), 0, 2, 1)
    private fun reverse(port: Int = 53, protocol: Int = 17) = DnsPackets.Packet(
        DnsPackets.DNS_ADDRESS, client, port, 40000, protocol, 0, 0, 0, 8192, byteArrayOf())
    private fun udp(q: ByteArray) = DnsPackets.udp(reverse(), q)
    private fun tcp(seq: Long, ack: Long, flags: Int, payload: ByteArray = byteArrayOf(), port: Int = 53, window: Int = 8192) =
        DnsPackets.tcp(reverse(port, 6), seq, ack, flags, payload, window)
    private fun frame(q: ByteArray) = ByteArray(q.size + 2).also { DnsMessage.put16(it, 0, q.size); q.copyInto(it, 2) }
    private fun tcpPayload(packet: ByteArray) = packet.copyOfRange(20 + (packet[32].toInt() ushr 4 and 15) * 4, packet.size)

    @Test fun blocklistMatchesDomainBoundariesNotSubstrings() {
        val list = DomainBlocklist(sequenceOf("# comment", "DoubleClick.net", "ad.example.org # note", ""))
        assertEquals(2, list.size)
        assertTrue(list.blocks("A.DoubleClick.NET."))
        assertTrue(list.blocks("ad.example.org"))
        assertFalse(list.blocks("notdoubleclick.net"))
        assertFalse(list.blocks("doubleclick.net.good.org"))
        assertFalse(list.blocks("example.org"))
    }
    @Test fun malformedListIsRejected() {
        for (bad in listOf("com", "https://ads.example.com", "ads..example.com", "*example.com")) {
            try { DomainBlocklist(sequenceOf(bad)); fail(bad) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun parsesAaaaAndHttpsWithoutFilteringByRecordType() {
        for (type in listOf(1, 28, 65, 16)) {
            val q = query("Ads.Example.org", type)
            assertTrue(DnsMessage.isQuery(q))
            assertEquals("ads.example.org", DnsMessage.question(q)!!.name)
            assertEquals(type, DnsMessage.question(q)!!.type)
        }
    }
    @Test fun localBlockedReplyKeepsIdAndQuestionButNotAuthenticatedBit() {
        val q = query().also { it[3] = 0x30 }
        val r = DnsMessage.reply(q, 3)
        assertTrue(DnsMessage.validReply(q, r))
        assertEquals(3, r[3].toInt() and 15)
        assertEquals(0, r[3].toInt() and 0x20)
        assertEquals(0x10, r[3].toInt() and 0x10)
        assertEquals(0, DnsMessage.u16(r, 6))
        assertFalse(DnsMessage.isQuery(r))
    }
    @Test fun malformedAndCompressedQuestionsAreRejected() {
        val q = query()
        assertNull(DnsMessage.question(q.copyOf(15)))
        assertNull(DnsMessage.question(q.copyOf().also { it[12] = 0xc0.toByte() }))
        assertNull(DnsMessage.question(q.copyOf().also { it[5] = 2 }))
        assertNull(DnsMessage.question(q.copyOf().also { it[2] = 0x79 }))
        assertNull(DnsMessage.question(query("a".repeat(64) + ".org")))
    }
    @Test fun replyMustMatchIdQuestionClassAndType() {
        val q = query()
        val answer = DnsMessage.reply(q, 0)
        assertTrue(DnsMessage.validReply(q, answer))
        assertFalse(DnsMessage.validReply(q, answer.copyOf().also { it[0] = 4 }))
        assertFalse(DnsMessage.validReply(q, DnsMessage.reply(query("other.org"), 0)))
        assertFalse(DnsMessage.validReply(q, DnsMessage.reply(query(type = 28), 0)))
        assertFalse(DnsMessage.validReply(q, q))
    }
    @Test fun ednsLimitIsBoundedAndMalformedOptRejected() {
        val q = query()
        assertEquals(512, DnsMessage.question(q)!!.udpLimit)
        val edns = (q + byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)).also { it[11] = 1 }
        assertEquals(1232, DnsMessage.question(edns)!!.udpLimit)
        edns[edns.lastIndex] = 50
        assertNull(DnsMessage.question(edns))
    }
    @Test fun ipv4UdpAndTcpChecksumsAndOptionsParse() {
        val u = udp(query())
        assertArrayEquals(query(), DnsPackets.parse(u)!!.payload)
        assertEquals(0, DnsPackets.checksum(u.copyOf(20)))
        val t = tcp(100, 0, 2)
        assertEquals(100, DnsPackets.parse(t)!!.seq)
        assertEquals(2, DnsPackets.parse(t)!!.flags)
        assertNull(DnsPackets.parse(u.copyOf().also { it[it.lastIndex] = 2 }))
        assertNull(DnsPackets.parse(u.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }))
    }
    @Test fun fragmentsOtherDestinationsAndTruncationAreRejected() {
        val u = udp(query())
        val fragment = u.copyOf().also {
            it[6] = 0x20; it[10] = 0; it[11] = 0
            DnsMessage.put16(it, 10, DnsPackets.checksum(it.copyOf(20)))
        }
        assertNull(DnsPackets.parse(fragment))
        assertNull(DnsPackets.parse(u.copyOf(24)))
        assertNull(DnsPackets.parse(DnsPackets.ip(client, byteArrayOf(1, 1, 1, 1), 17, u.copyOfRange(20, u.size))))
    }
    @Test fun udpRepliesAndOversizeAnswersUseTcpFallback() {
        val output = mutableListOf<ByteArray>()
        var big = false
        val gateway = DnsPacketGateway(output::add, { q, done ->
            done(DnsMessage.reply(q, 0).let { if (big) it.copyOf(1300) else it })
        }, { 0L })
        gateway.accept(udp(query()))
        assertTrue(DnsMessage.validReply(query(), output.last().copyOfRange(28, output.last().size)))
        big = true; gateway.accept(udp(query()))
        assertEquals(2, output.last()[30].toInt() and 2)
        assertTrue(output.last().size < 512)
    }
    @Test fun udpQueueIsBoundedAndCloseDropsLateResults() {
        val output = mutableListOf<ByteArray>()
        val callbacks = mutableListOf<(ByteArray) -> Unit>()
        val gateway = DnsPacketGateway(output::add, { _, done -> callbacks += done }, { 0L })
        repeat(33) { gateway.accept(udp(query())) }
        assertEquals(32, callbacks.size)
        assertEquals(1, output.size)
        assertEquals(2, output.last()[31].toInt() and 15)
        gateway.close(); callbacks[0](DnsMessage.reply(query(), 0))
        assertEquals(1, output.size)
    }
    @Test fun tcpSegmentedQueryReplyFinAndDuplicateAreHandled() {
        val output = mutableListOf<ByteArray>()
        var resolved = 0
        val gateway = DnsPacketGateway(output::add, { q, done -> resolved++; done(DnsMessage.reply(q, 3)) }, { 0L })
        gateway.accept(tcp(100, 0, 2))
        val server = (DnsPackets.u32(output.last(), 24) + 1) and 0xffffffffL
        gateway.accept(tcp(101, server, 16))
        val q = frame(query())
        gateway.accept(tcp(101, server, 0x18, q.copyOf(3)))
        assertEquals(0, resolved)
        gateway.accept(tcp(104, server, 0x18, q.copyOfRange(3, q.size)))
        assertEquals(1, resolved)
        val reply = output.last { tcpPayload(it).isNotEmpty() }
        assertTrue(DnsMessage.validReply(query(), tcpPayload(reply).drop(2).toByteArray()))
        // A retransmitted query must not perform a second DNS lookup.
        gateway.accept(tcp(101, server, 0x18, q))
        assertEquals(1, resolved)
        val ack = (server + tcpPayload(reply).size) and 0xffffffffL
        gateway.accept(tcp(101 + q.size.toLong(), ack, 0x11))
        assertEquals(0x11, output.last()[33].toInt() and 0xff)
        gateway.accept(tcp(102 + q.size.toLong(), (ack + 1) and 0xffffffffL, 16))
    }
    @Test fun tcpHandshakeRetransmitsAndHonorsClose() {
        var now = 0L
        val output = mutableListOf<ByteArray>()
        val gateway = DnsPacketGateway(output::add, { _, _ -> fail("No query") }, { now })
        gateway.accept(tcp(100, 0, 2))
        now = 1100; gateway.tick()
        assertEquals(2, output.size)
        assertArrayEquals(output[0], output[1])
        gateway.close(); now = 10000; gateway.tick(); gateway.accept(tcp(200, 0, 2))
        assertEquals(2, output.size)
    }
    @Test fun privateDnsPortGetsResetNotIntercepted() {
        val output = mutableListOf<ByteArray>()
        val gateway = DnsPacketGateway(output::add, { _, _ -> fail("TLS must never be intercepted") }, { 0L })
        gateway.accept(tcp(100, 0, 2, port = 853))
        assertEquals(0x14, output.single()[33].toInt() and 0xff)
    }
    @Test fun tcpInvalidFrameIsReset() {
        val output = mutableListOf<ByteArray>()
        val gateway = DnsPacketGateway(output::add, { _, _ -> fail("Invalid query") }, { 0L })
        gateway.accept(tcp(100, 0, 2))
        val server = (DnsPackets.u32(output.last(), 24) + 1) and 0xffffffffL
        gateway.accept(tcp(101, server, 16))
        gateway.accept(tcp(101, server, 0x18, byteArrayOf(0x20, 0)))
        assertEquals(4, output.last()[33].toInt() and 4)
    }
    @Test fun unrelatedTlsResetDoesNotDestroyDnsConnection() {
        val output = mutableListOf<ByteArray>()
        var resolved = 0
        val gateway = DnsPacketGateway(output::add, { q, done -> resolved++; done(DnsMessage.reply(q, 0)) }, { 0L })
        gateway.accept(tcp(100, 0, 2))
        val server = (DnsPackets.u32(output.last(), 24) + 1) and 0xffffffffL
        gateway.accept(tcp(100, 0, 4, port = 853))
        gateway.accept(tcp(101, server, 16))
        gateway.accept(tcp(101, server, 0x18, frame(query())))
        assertEquals(1, resolved)
    }
    @Test fun largeTcpReplyRespectsWindowAndResumesAfterAcknowledgments() {
        val output = mutableListOf<ByteArray>()
        val response = DnsMessage.reply(query(), 0).copyOf(65535)
        val gateway = DnsPacketGateway(output::add, { _, done -> done(response) }, { 0L })
        gateway.accept(tcp(100, 0, 2))
        val server = (DnsPackets.u32(output.last(), 24) + 1) and 0xffffffffL
        gateway.accept(tcp(101, server, 16))
        val q = frame(query())
        gateway.accept(tcp(101, server, 0x18, q, window = 0))
        assertTrue(output.none { tcpPayload(it).isNotEmpty() })
        val collected = java.io.ByteArrayOutputStream()
        var ack = server
        repeat(9) {
            output.clear()
            gateway.accept(tcp(101 + q.size.toLong(), ack, 16))
            val chunk = output.flatMap { tcpPayload(it).toList() }.toByteArray()
            assertTrue(chunk.size <= 8192)
            collected.write(chunk)
            ack = (ack + chunk.size) and 0xffffffffL
        }
        assertArrayEquals(frame(response), collected.toByteArray())
    }

    @Test fun arbitraryInputNeverEscapesParserBounds() {
        val random = java.util.Random(7)
        val gateway = DnsPacketGateway({}, { _, _ -> }, { 0L })
        repeat(2000) {
            val packet = ByteArray(random.nextInt(300)).also(random::nextBytes)
            DnsMessage.question(packet); DnsPackets.parse(packet); gateway.accept(packet)
        }
    }
}
