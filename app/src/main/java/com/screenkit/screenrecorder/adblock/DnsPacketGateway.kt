package com.screenkit.screenrecorder.adblock

import java.security.SecureRandom
import java.util.ArrayDeque

/** Small bounded DNS-only endpoint, NOT a general TCP/IP proxy. No default VPN route exists.
 * Supports UDP and length-framed TCP DNS (segmentation, ACKs, retransmission, FIN and flow control).
 * All mutable transport state is serialized. Resolver callbacks may come from bounded worker threads.
 */
internal class DnsPacketGateway(
    private val emit: (ByteArray) -> Unit,
    private val resolve: (ByteArray, (ByteArray) -> Unit) -> Unit,
    private val clock: () -> Long,
) {
    private data class Key(val source: List<Byte>, val port: Int)
    private data class Sent(val seq: Long, val flags: Int, val bytes: ByteArray, var at: Long, var retries: Int = 0) {
        val end get() = plus(seq, bytes.size + if (flags and 3 != 0) 1 else 0)
    }
    private class Connection(val packet: DnsPackets.Packet, val clientStart: Long, val serverStart: Long, now: Long) {
        var receiveNext = plus(clientStart, 1)
        var sendNext = serverStart
        var acked = serverStart
        var window = packet.window
        var established = false
        var input = byteArrayOf()
        var output = byteArrayOf()
        var pending = 0
        var peerFin = false
        var finSent = false
        var touched = now
        val sent = ArrayDeque<Sent>()
    }
    private val connections = LinkedHashMap<Key, Connection>()
    private val random = SecureRandom()
    private var closed = false
    private var udpPending = 0
    private companion object {
        fun plus(n: Long, amount: Int) = (n + amount) and 0xffffffffL
        fun distance(end: Long, start: Long) = (end - start) and 0xffffffffL
    }
    @Synchronized fun close() { closed = true; connections.clear() }
    @Synchronized fun accept(bytes: ByteArray) {
        if (closed) return
        val p = DnsPackets.parse(bytes) ?: return
        if (p.protocol == 17) {
            if (p.destinationPort != 53 || !DnsMessage.isQuery(p.payload)) return
            if (udpPending >= 32) { emit(DnsPackets.udp(p, DnsMessage.reply(p.payload, 2))); return }
            udpPending++
            resolve(p.payload) { response -> synchronized(this) {
                udpPending--
                if (!closed) {
                    val max = DnsMessage.question(p.payload)!!.udpLimit
                    emit(DnsPackets.udp(p, if (response.size > max) DnsMessage.reply(p.payload, 0, true) else response))
                }
            } }
            return
        }
        // Port is part of a TCP flow: a TLS probe's RST must not destroy a DNS connection
        // which happens to use the same client port.
        if (p.destinationPort != 53) {
            if (p.flags and 4 == 0) reset(p)
            return
        }
        if (p.flags and 4 != 0) { connections.remove(Key(p.source.toList(), p.sourcePort)); return }
        val key = Key(p.source.toList(), p.sourcePort)
        val c = connections[key]
        if (p.flags and 2 != 0) {
            if (p.flags and 16 != 0 || p.payload.isNotEmpty()) { reset(p); return }
            if (c != null && c.clientStart == p.seq) { c.sent.firstOrNull()?.let { retransmit(c, it) }; return }
            if (connections.size >= 32 && c == null) { reset(p); return }
            val created = Connection(p, p.seq, random.nextInt().toLong() and 0xffffffffL, clock())
            connections[key] = created
            send(created, 0x12, byteArrayOf())
            return
        }
        if (c == null) { reset(p); return }
        if (p.flags and 16 == 0) return
        val advanced = distance(p.ack, c.acked)
        if (advanced > distance(c.sendNext, c.acked)) return
        c.acked = p.ack; c.window = p.window; c.touched = clock()
        while (c.sent.isNotEmpty() && distance(p.ack, c.sent.first.end) < 0x80000000L) c.sent.removeFirst()
        if (!c.established) {
            if (p.ack != plus(c.serverStart, 1)) return
            c.established = true
        }
        if (c.finSent && c.sent.isEmpty()) { connections.remove(key); return }
        if (p.seq != c.receiveNext) { acknowledge(c); flush(c); return }
        if (p.payload.isNotEmpty()) {
            if (c.peerFin || c.input.size + p.payload.size > 8192) { connections.remove(key); reset(p); return }
            c.input += p.payload; c.receiveNext = plus(c.receiveNext, p.payload.size)
            acknowledge(c)
            while (c.input.size >= 2) {
                val size = DnsMessage.u16(c.input, 0)
                if (size !in 17..4096 || c.pending >= 8) { connections.remove(key); reset(p); return }
                if (c.input.size < size + 2) break
                val query = c.input.copyOfRange(2, size + 2); c.input = c.input.copyOfRange(size + 2, c.input.size)
                if (!DnsMessage.isQuery(query)) { connections.remove(key); reset(p); return }
                c.pending++
                val connection = c
                resolve(query) { reply -> synchronized(this) {
                    if (!closed && connections[key] === connection) {
                        connection.pending--
                        val data = ByteArray(2 + reply.size)
                        DnsMessage.put16(data, 0, reply.size); reply.copyInto(data, 2)
                        if (connection.output.size + distance(connection.sendNext, connection.acked) + data.size > 96 * 1024) {
                            connections.remove(key); reset(p)
                        } else {
                            connection.output += data
                            flush(connection)
                        }
                    }
                } }
            }
        }
        if (p.flags and 1 != 0 && !c.peerFin) {
            c.peerFin = true; c.receiveNext = plus(c.receiveNext, 1); acknowledge(c)
            if (c.input.isNotEmpty()) { connections.remove(key); reset(p); return }
        }
        flush(c)
    }
    private fun reset(p: DnsPackets.Packet) {
        if (p.flags and 16 != 0) emit(DnsPackets.tcp(p, p.ack, 0, 4))
        else emit(DnsPackets.tcp(p, 0, plus(p.seq, p.payload.size + if (p.flags and 3 != 0) 1 else 0), 0x14))
    }
    private fun acknowledge(c: Connection) { emit(DnsPackets.tcp(c.packet, c.sendNext, c.receiveNext, 16)) }
    private fun send(c: Connection, flags: Int, payload: ByteArray) {
        val sent = Sent(c.sendNext, flags, payload, clock())
        c.sent.addLast(sent); c.sendNext = sent.end
        retransmit(c, sent)
    }
    private fun retransmit(c: Connection, sent: Sent) { emit(DnsPackets.tcp(c.packet, sent.seq, c.receiveNext, sent.flags, sent.bytes)) }
    private fun flush(c: Connection) {
        if (!c.established) return
        while (c.output.isNotEmpty()) {
            val available = c.window - distance(c.sendNext, c.acked).toInt()
            if (available <= 0) return
            val count = minOf(1200, available, c.output.size)
            val bytes = c.output.copyOfRange(0, count); c.output = c.output.copyOfRange(count, c.output.size)
            send(c, 0x18, bytes)
        }
        if (c.peerFin && c.pending == 0 && !c.finSent && c.window > distance(c.sendNext, c.acked)) {
            c.finSent = true; send(c, 0x11, byteArrayOf())
        }
    }
    @Synchronized fun tick() {
        if (closed) return
        val now = clock()
        val it = connections.entries.iterator()
        while (it.hasNext()) {
            val c = it.next().value
            if (now - c.touched > 30_000) { it.remove(); continue }
            val first = c.sent.firstOrNull() ?: continue
            if (now - first.at >= (1000L shl first.retries.coerceAtMost(3))) {
                if (first.retries++ >= 4) { it.remove(); continue }
                first.at = now; retransmit(c, first)
            }
        }
    }
}
