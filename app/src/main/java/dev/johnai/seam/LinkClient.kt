package dev.johnai.seam

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Keeps one connection to the paired computer alive: connects, authenticates, sends queued
 * messages, answers pings, and reconnects with backoff. Runs on its own threads.
 */
class LinkClient(
    private val pairing: PairingInfo,
    private val deviceId: String,
    private val deviceName: String,
    private val onState: (State, String?) -> Unit,
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED, REJECTED }

    private val outbox = LinkedBlockingQueue<String>(500)
    @Volatile private var running = false
    @Volatile private var socket: SSLSocket? = null
    private var thread: Thread? = null

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

    private fun loop() {
        var backoffMs = 2_000L
        while (running) {
            for (host in pairing.hosts) {
                if (!running) return
                onState(State.CONNECTING, host)
                try {
                    session(host)
                    backoffMs = 2_000L
                } catch (e: Rejected) {
                    running = false
                    onState(State.REJECTED, e.message)
                    return
                } catch (e: Exception) {
                    onState(State.DISCONNECTED, e.message)
                }
            }
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { return }
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private class Rejected(message: String) : Exception(message)

    private fun session(host: String) {
        val tls = SSLContext.getInstance("TLS")
        tls.init(null, arrayOf(PinnedTrustManager(pairing.fingerprint)), null)
        val s = tls.socketFactory.createSocket() as SSLSocket
        socket = s
        s.use { sock ->
            sock.connect(InetSocketAddress(host, pairing.port), 10_000)
            sock.enabledProtocols = arrayOf("TLSv1.3")
            // The computer pings every 30 s, so 90 s of silence means the link is dead.
            sock.soTimeout = 90_000
            sock.startHandshake()
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val writer = OutputStreamWriter(sock.outputStream, Charsets.UTF_8)
            fun write(line: String) = synchronized(writer) { writer.write(line + "\n"); writer.flush() }

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
                        // Unknown types are ignored so newer computers keep working.
                    }
                }
            } finally {
                sender.interrupt()
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
