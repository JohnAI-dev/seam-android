package dev.johnai.seam

import java.security.MessageDigest

/**
 * Receiving a file (protocol/PROTOCOL.md, "Sending files"). Plain Kotlin: no Android APIs,
 * so the name rules and the incoming-transfer checks run in JVM unit tests.
 */
object FileTransfer {
    /** Download names are at most this many characters. */
    const val MAX_NAME_CHARS = 200

    /** Files are at most 2 GiB. */
    const val MAX_BYTES = Protocol.FILE_MAX_BYTES

    /** One chunk is at most 256 KiB of raw bytes. */
    const val MAX_CHUNK_BYTES = Protocol.FILE_CHUNK_MAX_BYTES

    /**
     * A name that cannot escape the download folder. Keeps only the final path segment
     * (so `a/b` and `../x` cannot carry a directory), then strips `\\`, `/`, `..`, and
     * leading dots. Empty becomes "file". Longer than [MAX_NAME_CHARS] is cut, keeping
     * the extension.
     */
    fun sanitizeName(raw: String): String {
        var name = raw.replace('\\', '/').substringAfterLast('/')
        name = name.replace("..", "").replace("/", "").replace("\\", "")
        name = name.filter { !it.isISOControl() }.trim().trimStart { it == '.' }.trim()
        if (name.isEmpty()) return "file"
        return limitLength(name)
    }

    private fun limitLength(name: String): String {
        if (name.length <= MAX_NAME_CHARS) return name
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.length - 1) return name.take(MAX_NAME_CHARS)
        val ext = name.substring(dot)
        if (ext.length >= MAX_NAME_CHARS) return name.take(MAX_NAME_CHARS)
        return name.take(MAX_NAME_CHARS - ext.length) + ext
    }

    /**
     * One file the computer is sending. [chunk] must be called with seq 0, 1, 2, ...
     * (Long, so a long transfer cannot wrap). It never accepts more bytes than [size].
     * [done] checks SHA-256 of the bytes accepted so far.
     */
    class Incoming(
        val transfer: String,
        rawName: String,
        val size: Long,
        val mime: String? = null,
    ) {
        val name: String = sanitizeName(rawName)

        var failed: Boolean = false
            private set
        var complete: Boolean = false
            private set
        var failure: String? = null
            private set
        var bytesReceived: Long = 0L
            private set

        private var nextSeq = 0L
        private val digest = MessageDigest.getInstance("SHA-256")

        init {
            if (size !in 0..MAX_BYTES) {
                failed = true
                failure = ERR_TOO_LARGE
            }
        }

        /** Accept the next chunk. False if this transfer must be failed. */
        fun chunk(seq: Long, data: ByteArray): Boolean {
            if (failed || complete) return false
            if (seq != nextSeq) return fail(ERR_SEQ)
            if (data.size > MAX_CHUNK_BYTES) return fail(ERR_CHUNK)
            if (data.size.toLong() > size - bytesReceived) return fail(ERR_TOO_MANY)
            digest.update(data)
            bytesReceived += data.size.toLong()
            nextSeq += 1L
            return true
        }

        /** True only when every offered byte arrived and [sha256] matches. */
        fun done(sha256: String): Boolean {
            if (failed || complete) return false
            if (bytesReceived != size) return fail(ERR_INCOMPLETE)
            val actual = digest.digest().toHex()
            if (!isSha256Hex(sha256) || !actual.equals(sha256, ignoreCase = true)) return fail(ERR_HASH)
            complete = true
            return true
        }

        private fun fail(reason: String): Boolean {
            failed = true
            if (failure == null) failure = reason
            return false
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun isSha256Hex(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        companion object {
            const val ERR_SEQ = "wrong seq"
            const val ERR_TOO_MANY = "too many bytes"
            const val ERR_CHUNK = "chunk too large"
            const val ERR_TOO_LARGE = "file is too large"
            const val ERR_INCOMPLETE = "incomplete"
            const val ERR_HASH = "wrong hash"
        }
    }
}
