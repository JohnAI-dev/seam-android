package dev.johnai.seam

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Keeps one connection to the paired computer alive: connects, authenticates, sends queued
 * messages, answers pings, and reconnects with backoff. If a full round of stored hosts fails,
 * looks the computer up on the local network and saves the new address. Runs on its own threads.
 *
 * Each connection is a [Session] with its own writer and lock. Every frame is written by
 * [Session.send], so concurrent writers cannot interleave lines. [currentSession] is cleared
 * only with compareAndSet of the session that is ending. [sendOn] refuses a stale session id.
 */
class LinkClient(
    private var pairing: PairingInfo,
    private val deviceId: String,
    private val deviceName: String,
    private val onState: (State, String?) -> Unit,
    private val onDismiss: (String) -> Unit,
    private val onClipboard: (String) -> Unit,
    private val onReply: (String, String) -> String,
    private val discover: (fingerprint: String) -> DiscoveredService? = { null },
    private val onPairingUpdated: (PairingInfo) -> Unit = {},
    private val onRing: () -> Unit = {},
    private val onRingStop: () -> Unit = {},
    private val onFile: (String) -> String? = { null },
    private val onCallAction: (String) -> Unit = {},
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED, REJECTED }

    /**
     * One live connection. [id] increases for every connection this client opens.
     * [send] holds a single lock for the whole line, so concurrent writers cannot interleave frames.
     */
    class Session internal constructor(
        val id: Long,
        private val writer: OutputStream,
    ) {
        private val lock = Any()
        private var open = true

        /**
         * Write [frame] and a newline, UTF-8. Returns false and writes nothing if this
         * session has ended.
         */
        fun send(frame: String): Boolean {
            val bytes = (frame + "\n").toByteArray(Charsets.UTF_8)
            return synchronized(lock) {
                if (!open) return@synchronized false
                try {
                    writer.write(bytes)
                    writer.flush()
                } catch (e: IOException) {
                    open = false
                    throw e
                }
                true
            }
        }

        internal fun close() {
            synchronized(lock) { open = false }
        }
    }

    private val outbox = LinkedBlockingQueue<String>(500)
    @Volatile private var running = false
    @Volatile private var socket: SSLSocket? = null
    private var thread: Thread? = null
    private val nextSessionId = AtomicLong(0)

    /**
     * The connection frame writes must use, or null when none is up.
     * Cleared only with compareAndSet of the session that is ending.
     */
    val currentSession = AtomicReference<Session?>(null)

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "seam-link").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: IOException) {}
        thread?.interrupt()
    }

    /** Queue a message; if the queue is full, the oldest message is dropped. */
    fun send(line: String) {
        while (!outbox.offer(line)) outbox.poll()
    }

    /**
     * Write [frame] only if [sessionId] is still [currentSession].
     * Returns false and writes nothing if that session has ended or a newer one has replaced it.
     * A long operation (a file upload) captures the id when it starts and uses this, so a
     * reconnect cannot make it write onto the next connection.
     */
    fun sendOn(sessionId: Long, frame: String): Boolean {
        val session = currentSession.get() ?: return false
        if (session.id != sessionId) return false
        return session.send(frame)
    }

    /** Id of [currentSession], or null. Capture this before a long upload and pass it to [sendOn]. */
    fun currentSessionId(): Long? = currentSession.get()?.id

    /** Bind [output] to a new session and make it current. The previous session can no longer write. */
    fun startSession(output: OutputStream): Session {
        val session = Session(nextSessionId.incrementAndGet(), output)
        currentSession.getAndSet(session)?.close()
        return session
    }

    /**
     * End [session]. Clears [currentSession] only if it is still [session], so an older
     * connection ending cannot drop a newer one.
     */
    fun endSession(session: Session) {
        // Close before compareAndSet so a sendOn that already observed this id writes nothing.
        session.close()
        currentSession.compareAndSet(session, null)
    }

    private fun loop() {
        var backoffMs = 2_000L
        while (running) {
            var reached = false
            val hosts = pairing.hosts
            val port = pairing.port
            for (host in hosts) {
                if (!running) return
                if (tryHost(host, port) { reached = true }) {
                    backoffMs = 2_000L
                }
                if (!running) return
            }
            // Stored addresses failed (the computer may have a new IP). Find it on the LAN.
            if (!reached && running) {
                onState(State.CONNECTING, null)
                val found = try {
                    discover(pairing.fingerprint)
                } catch (_: Exception) {
                    null
                }
                if (!running || Thread.currentThread().isInterrupted) return
                if (found != null) {
                    val addresses = orderedConnectHosts(
                        found.hosts.ifEmpty { listOf(found.host) },
                    ).ifEmpty { listOf(found.host) }
                    for (host in addresses) {
                        if (!running) return
                        val clean = tryHost(host, found.port) {
                            reached = true
                            remember(host, found.port)
                        }
                        if (clean || reached) backoffMs = 2_000L
                        // Link-local often fails; try the next hostAddresses entry before giving up.
                        // Once the pin matches, keep that address even if the session later drops.
                        if (!running || reached) break
                    }
                }
            }
            if (!running) return
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { return }
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    /**
     * Connect to one address. Returns true if the session ended cleanly, so backoff can reset.
     * [markConnected] runs once the certificate pin matches.
     */
    private fun tryHost(host: String, port: Int, markConnected: () -> Unit): Boolean {
        onState(State.CONNECTING, host)
        try {
            session(host, port, markConnected)
            return true
        } catch (e: Rejected) {
            running = false
            onState(State.REJECTED, e.message)
            return false
        } catch (e: Exception) {
            onState(State.DISCONNECTED, e.message)
            return false
        }
    }

    /** Save a computer found on the local network, and use that address for the next attempt. */
    private fun remember(host: String, port: Int) {
        val hosts = listOf(host) + pairing.hosts.filter { it != host }
        pairing = PairingInfo(hosts, port, pairing.fingerprint, pairing.keyB64, pairing.name)
        try {
            onPairingUpdated(pairing)
        } catch (_: Exception) {
        }
    }

    private class Rejected(message: String) : Exception(message)

    private fun session(host: String, port: Int, markConnected: () -> Unit) {
        val tls = SSLContext.getInstance("TLS")
        tls.init(null, arrayOf(PinnedTrustManager(pairing.fingerprint)), null)
        val s = tls.socketFactory.createSocket() as SSLSocket
        socket = s
        s.use { sock ->
            sock.connect(InetSocketAddress(host, port), 10_000)
            sock.enabledProtocols = arrayOf("TLSv1.3")
            // The computer pings every 30 s, so 90 s of silence means the link is dead.
            sock.soTimeout = 90_000
            sock.startHandshake()
            // Pin matched: this address is the paired computer, even if the read loop later fails.
            markConnected()
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val conn = startSession(sock.outputStream)
            try {
                fun write(line: String) {
                    if (!conn.send(line)) throw IOException("closed")
                }

                val challenge = Protocol.parse(reader.readLine() ?: throw IOException("closed"))
                if (challenge?.optString("type") != "challenge") throw IOException("expected challenge")
                val nonce = challenge.getString("nonce")
                write(Protocol.hello(deviceId, deviceName, Protocol.proof(pairing.key, nonce, deviceId)))
                val reply = Protocol.parse(reader.readLine() ?: throw IOException("closed"))
                when (reply?.optString("type")) {
                    "welcome" -> {}
                    "error" -> throw Rejected(reply.optString("message", "rejected"))
                    else -> throw IOException("unexpected reply")
                }
                onState(State.CONNECTED, reply.optString("desktop_name"))

                val sender = Thread({
                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            val line = outbox.poll(30, TimeUnit.SECONDS) ?: continue
                            write(line)
                        }
                    } catch (_: Exception) {
                        try { sock.close() } catch (_: IOException) {}
                    }
                }, "seam-link-send").apply { isDaemon = true; start() }

                try {
                    while (running) {
                        val line = reader.readLine() ?: break
                        val msg = Protocol.parse(line) ?: continue
                        when (msg.optString("type")) {
                            "ping" -> write(Protocol.pong())
                            "error" -> throw Rejected(msg.optString("message", "rejected"))
                            "dismiss" -> Protocol.dismissId(line)?.let(onDismiss)
                            "clipboard" -> Protocol.clipboardText(line)?.let(onClipboard)
                            "reply" -> Protocol.replyRequest(line)?.let { write(onReply(it.id, it.text)) }
                            "ring" -> if (Protocol.isRing(line)) onRing()
                            "ring_stop" -> if (Protocol.isRingStop(line)) onRingStop()
                            "call_action" -> Protocol.callAction(line)?.let(onCallAction)
                            "file_offer", "file_accept", "file_reject", "file_chunk",
                            "file_done", "file_result", "file_cancel" -> {
                                // Written here so file_accept goes out before the next frame is read.
                                val reply = try {
                                    onFile(line)
                                } catch (_: Exception) {
                                    null
                                }
                                if (reply != null) write(reply)
                            }
                            // Unknown types are ignored so newer computers keep working.
                        }
                    }
                } finally {
                    sender.interrupt()
                }
            } finally {
                endSession(conn)
            }
        }
    }
}

/** Trusts exactly one certificate: the one whose fingerprint was in the QR code. */
class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        if (!Protocol.fingerprint(cert.encoded).equals(fingerprint, ignoreCase = true)) {
            throw CertificateException("this is not the paired computer")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
