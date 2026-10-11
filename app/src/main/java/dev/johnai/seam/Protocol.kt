package dev.johnai.seam

import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The Seam link protocol (see protocol/PROTOCOL.md). Pure Kotlin + org.json, so it runs in
 * plain JVM unit tests against the same vectors the desktop uses.
 */
object Protocol {
    const val VERSION = 1

    /** At most 100 000 characters in a clipboard message. */
    const val CLIPBOARD_MAX_CHARS = 100_000

    /** At most 4 096 characters in an open_url (protocol/PROTOCOL.md, "Opening links"). */
    const val OPEN_URL_MAX_CHARS = 4_096

    /** At most 5 000 characters in a notification reply. */
    const val REPLY_MAX_CHARS = 5_000

    /** Find-my-phone rings this long unless Found it is pressed or ring_stop arrives. */
    const val RING_DURATION_MS = 60_000L

    /** A file is at most 2 GiB. */
    const val FILE_MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** A file_chunk carries at most 256 KiB of raw bytes. */
    const val FILE_CHUNK_MAX_BYTES = 256 * 1024

    /** On connect, announce this many recent photos, oldest first. */
    const val PHOTO_RECENT_LIMIT = 12

    /** photo_new thumbnail is a JPEG of at most this many bytes. */
    const val PHOTO_THUMB_MAX_BYTES = 32 * 1024

    /** Longest side of a photo_new thumbnail, in pixels. */
    const val PHOTO_THUMB_SIDE = 256

    /** app_icon PNG is at most 16 KiB. */
    const val APP_ICON_MAX_BYTES = 16 * 1024

    /** App icons are drawn into a square bitmap of this side, in pixels. */
    const val APP_ICON_SIDE = 96

    /** reply_result error when the notification is no longer available. */
    const val REPLY_ERROR_GONE = "notification is gone"

    /** reply_result error when the notification has no usable reply action. */
    const val REPLY_ERROR_CANNOT = "cannot reply to this notification"

    /** hex(HMAC-SHA256(key, "seam-v1|" + nonce + "|" + deviceId)) */
    fun proof(key: ByteArray, nonce: String, deviceId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal("seam-v1|$nonce|$deviceId".toByteArray(Charsets.UTF_8)).toHex()
    }

    /** SHA-256 of a DER certificate, lowercase hex. */
    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).toHex()

    fun hello(deviceId: String, name: String, proof: String): String =
        JSONObject().put("type", "hello").put("device_id", deviceId).put("name", name)
            .put("proof", proof).toString()

    fun notification(
        id: String, app: String, appName: String, title: String, text: String, time: Long,
        replyable: Boolean = false,
    ): String = JSONObject().put("type", "notification").put("id", id).put("app", app)
        .put("app_name", appName).put("title", title).put("text", text).put("time", time)
        .put("replyable", replyable)
        .toString()

    fun notificationRemoved(id: String): String =
        JSONObject().put("type", "notification_removed").put("id", id).toString()

    fun battery(level: Int, charging: Boolean): String =
        JSONObject().put("type", "battery").put("level", level.coerceIn(0, 100))
            .put("charging", charging).toString()

    /** Do Not Disturb report (protocol/PROTOCOL.md, "Do Not Disturb"). */
    fun dnd(on: Boolean): String =
        JSONObject().put("type", "dnd").put("on", on).toString()

    /**
     * `on` from a dnd_set frame, or null if it is not a dnd_set or `on` is not a boolean.
     * Null is ignored: the phone does not change Do Not Disturb.
     */
    fun parseDndSet(frame: String): Boolean? {
        val msg = parse(frame) ?: return null
        if (msg.opt("type") != "dnd_set") return null
        if (!msg.has("on") || msg.isNull("on")) return null
        return msg.opt("on") as? Boolean
    }

    fun pong(): String = JSONObject().put("type", "pong").toString()

    /** Phone to computer call state. [number] and [name] are empty when unknown. */
    fun call(state: String, number: String, name: String): String =
        JSONObject().put("type", "call").put("state", state)
            .put("number", number).put("name", name).toString()

    /** decline or silence from a call_action frame; unknown actions are ignored. */
    fun callAction(frame: String): String? {
        val msg = parse(frame) ?: return null
        if (msg.opt("type") != "call_action") return null
        val action = msg.opt("action") as? String ?: return null
        return action.takeIf { it == "decline" || it == "silence" }
    }

    /**
     * photo_new frame. Null if [id] is empty or [thumbJpeg] is empty or larger than
     * [PHOTO_THUMB_MAX_BYTES]. [name] is reduced to a file name (no path).
     */
    fun photoNew(id: String, name: String, taken: Long, thumbJpeg: ByteArray): String? {
        if (id.isEmpty() || thumbJpeg.isEmpty() || thumbJpeg.size > PHOTO_THUMB_MAX_BYTES) return null
        return JSONObject()
            .put("type", "photo_new")
            .put("id", id)
            .put("name", FileTransfer.sanitizeName(name))
            .put("taken", taken)
            .put("thumb", Base64.getEncoder().encodeToString(thumbJpeg))
            .toString()
    }

    /**
     * app_icon frame: base64 PNG of one app's icon.
     * Null if [app] is empty or [png] is empty or larger than [APP_ICON_MAX_BYTES].
     * Sent at most once per package per connection (protocol/PROTOCOL.md, "App icons").
     */
    fun appIcon(app: String, png: ByteArray): String? {
        if (app.isEmpty() || png.isEmpty() || png.size > APP_ICON_MAX_BYTES) return null
        return JSONObject()
            .put("type", "app_icon")
            .put("app", app)
            .put("icon", Base64.getEncoder().encodeToString(png))
            .toString()
    }

    /** Id from a photo_request frame, or null if it is not one. Blank ids are ignored. */
    fun parsePhotoRequest(frame: String): String? {
        val msg = parse(frame) ?: return null
        if (msg.opt("type") != "photo_request") return null
        if (msg.isNull("id")) return null
        val id = msg.opt("id") as? String ?: return null
        return id.trim().takeIf { it.isNotEmpty() }
    }

    /** Clipboard frame; text is truncated to CLIPBOARD_MAX_CHARS. */
    fun clipboard(text: String): String =
        JSONObject().put("type", "clipboard").put("text", text.take(CLIPBOARD_MAX_CHARS)).toString()

    /** Text from a clipboard frame, truncated, or null if it isn't a clipboard message. */
    fun clipboardText(frame: String): String? {
        val msg = parse(frame) ?: return null
        if (msg.optString("type") != "clipboard") return null
        if (msg.isNull("text")) return null
        val text = msg.opt("text") as? String ?: return null
        return text.take(CLIPBOARD_MAX_CHARS)
    }

    /**
     * open_url frame, or null if [url] is not an http(s) URL of at most
     * [OPEN_URL_MAX_CHARS] characters. javascript:, intent:, file: and any other
     * scheme are not sent.
     */
    fun openUrl(url: String): String? {
        val safe = LinkUrls.openableHttpUrl(url) ?: return null
        return JSONObject().put("type", "open_url").put("url", safe).toString()
    }

    /**
     * URL from an open_url frame, or null if it is not one or must be ignored
     * (not http(s), too long, or not a URL).
     */
    fun parseOpenUrl(frame: String): String? {
        val msg = parse(frame) ?: return null
        if (msg.opt("type") != "open_url") return null
        if (msg.isNull("url")) return null
        val url = msg.opt("url") as? String ?: return null
        return LinkUrls.openableHttpUrl(url)
    }

    /** Parse one frame; returns null for anything that isn't a JSON object with a type. */
    fun parse(line: String): JSONObject? = try {
        JSONObject(line).takeIf { it.has("type") }
    } catch (e: Exception) {
        null
    }

    /** Id from a dismiss frame, or null for any other frame or a dismiss with no id. */
    fun dismissId(frame: String): String? {
        val msg = parse(frame) ?: return null
        if (msg.optString("type") != "dismiss") return null
        if (msg.isNull("id")) return null
        return msg.optString("id").takeIf { it.isNotEmpty() }
    }

    /** A computer's request to reply to a notification. */
    data class ReplyRequest(val id: String, val text: String)

    /** An offered file. [mime] is null when the sender omitted it. */
    data class FileOffer(val transfer: String, val name: String, val size: Long, val mime: String?)

    /** A rejected offer. [reason] is null when omitted. */
    data class FileReject(val transfer: String, val reason: String?)

    /** One chunk. [data] is the decoded bytes; [seq] is 0, 1, 2, ... as a Long. */
    class FileChunk(val transfer: String, val seq: Long, val data: ByteArray)

    /** Sender is finished. [sha256] is 64 hex characters of the whole file. */
    data class FileDone(val transfer: String, val sha256: String)

    /** Receiver's outcome. [error] is set only when [ok] is false and a reason was given. */
    data class FileResult(val transfer: String, val ok: Boolean, val error: String?)

    /** reply_result frame. The error is included only when ok is false. */
    fun replyResult(id: String, ok: Boolean, error: String? = null): String {
        val o = JSONObject().put("type", "reply_result").put("id", id).put("ok", ok)
        if (!ok && !error.isNullOrEmpty()) o.put("error", error)
        return o.toString()
    }

    /**
     * Id and text from a reply frame, or null if it isn't a valid reply.
     * Text longer than REPLY_MAX_CHARS is truncated. Malformed frames are ignored.
     */
    fun replyRequest(frame: String): ReplyRequest? {
        val msg = parse(frame) ?: return null
        if (msg.optString("type") != "reply") return null
        if (msg.isNull("id") || msg.isNull("text")) return null
        val id = msg.opt("id") as? String ?: return null
        val text = msg.opt("text") as? String ?: return null
        if (id.isEmpty()) return null
        return ReplyRequest(id, text.take(REPLY_MAX_CHARS))
    }

    /** True for a find-my-phone ring frame. Extra fields are ignored; ring_stop is not a ring. */
    fun isRing(frame: String): Boolean = parse(frame)?.opt("type") == "ring"

    /** True for a ring_stop frame. A ring frame is not a stop. */
    fun isRingStop(frame: String): Boolean = parse(frame)?.opt("type") == "ring_stop"

    fun fileOffer(transfer: String, name: String, size: Long, mime: String? = null): String {
        val o = JSONObject().put("type", "file_offer").put("transfer", transfer)
            .put("name", name).put("size", size)
        if (!mime.isNullOrEmpty()) o.put("mime", mime)
        return o.toString()
    }

    fun fileAccept(transfer: String): String =
        JSONObject().put("type", "file_accept").put("transfer", transfer).toString()

    fun fileReject(transfer: String, reason: String? = null): String {
        val o = JSONObject().put("type", "file_reject").put("transfer", transfer)
        if (!reason.isNullOrEmpty()) o.put("reason", reason)
        return o.toString()
    }

    fun fileChunk(transfer: String, seq: Long, data: ByteArray): String =
        JSONObject().put("type", "file_chunk").put("transfer", transfer).put("seq", seq)
            .put("data", Base64.getEncoder().encodeToString(data)).toString()

    fun fileDone(transfer: String, sha256: String): String =
        JSONObject().put("type", "file_done").put("transfer", transfer).put("sha256", sha256).toString()

    fun fileResult(transfer: String, ok: Boolean, error: String? = null): String {
        val o = JSONObject().put("type", "file_result").put("transfer", transfer).put("ok", ok)
        if (!ok && !error.isNullOrEmpty()) o.put("error", error)
        return o.toString()
    }

    fun fileCancel(transfer: String): String =
        JSONObject().put("type", "file_cancel").put("transfer", transfer).toString()

    fun parseFileOffer(frame: String): FileOffer? {
        val msg = typed(frame, "file_offer") ?: return null
        val transfer = transferId(msg) ?: return null
        if (msg.isNull("name")) return null
        val name = msg.opt("name") as? String ?: return null
        val size = jsonLong(msg, "size") ?: return null
        if (size < 0L) return null
        val mime = optionalText(msg, "mime") ?: return null
        return FileOffer(transfer, name, size, mime.takeIf { it.isNotEmpty() })
    }

    /** Transfer id from a file_accept frame, or null if it is not one. */
    fun parseFileAccept(frame: String): String? {
        val msg = typed(frame, "file_accept") ?: return null
        return transferId(msg)
    }

    fun parseFileReject(frame: String): FileReject? {
        val msg = typed(frame, "file_reject") ?: return null
        val transfer = transferId(msg) ?: return null
        val reason = optionalText(msg, "reason") ?: return null
        return FileReject(transfer, reason.takeIf { it.isNotEmpty() })
    }

    fun parseFileChunk(frame: String): FileChunk? {
        val msg = typed(frame, "file_chunk") ?: return null
        val transfer = transferId(msg) ?: return null
        val seq = jsonLong(msg, "seq") ?: return null
        if (seq < 0L) return null
        if (msg.isNull("data")) return null
        val encoded = msg.opt("data") as? String ?: return null
        val data = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return FileChunk(transfer, seq, data)
    }

    fun parseFileDone(frame: String): FileDone? {
        val msg = typed(frame, "file_done") ?: return null
        val transfer = transferId(msg) ?: return null
        if (msg.isNull("sha256")) return null
        val sha = msg.opt("sha256") as? String ?: return null
        if (!isHex(sha, 64)) return null
        return FileDone(transfer, sha)
    }

    fun parseFileResult(frame: String): FileResult? {
        val msg = typed(frame, "file_result") ?: return null
        val transfer = transferId(msg) ?: return null
        if (!msg.has("ok") || msg.isNull("ok")) return null
        val ok = msg.opt("ok") as? Boolean ?: return null
        val error = optionalText(msg, "error") ?: return null
        return FileResult(transfer, ok, if (ok) null else error.takeIf { it.isNotEmpty() })
    }

    /** Transfer id from a file_cancel frame, or null if it is not one. */
    fun parseFileCancel(frame: String): String? {
        val msg = typed(frame, "file_cancel") ?: return null
        return transferId(msg)
    }

    private fun typed(frame: String, type: String): JSONObject? {
        val msg = parse(frame) ?: return null
        return msg.takeIf { it.opt("type") == type }
    }

    private fun transferId(msg: JSONObject): String? {
        if (msg.isNull("transfer")) return null
        val id = msg.opt("transfer") as? String ?: return null
        return id.takeIf { isHex(it, 32) }
    }

    private fun isHex(value: String, length: Int): Boolean =
        value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Empty string when absent or null. Null when present but not a string. */
    private fun optionalText(msg: JSONObject, key: String): String? {
        if (!msg.has(key) || msg.isNull(key)) return ""
        return msg.opt(key) as? String
    }

    private fun jsonLong(msg: JSONObject, key: String): Long? {
        if (!msg.has(key) || msg.isNull(key)) return null
        return when (val v = msg.opt(key)) {
            is Int -> v.toLong()
            is Long -> v
            is Double -> wholeLong(v)
            is Float -> wholeLong(v.toDouble())
            is java.math.BigInteger -> if (v.bitLength() <= 63) v.toLong() else null
            is java.math.BigDecimal -> try {
                v.longValueExact()
            } catch (_: ArithmeticException) {
                null
            }
            is Number -> v.toLong()
            else -> null
        }
    }

    private fun wholeLong(v: Double): Long? {
        if (!v.isFinite() || v % 1.0 != 0.0) return null
        if (v < Long.MIN_VALUE.toDouble() || v > Long.MAX_VALUE.toDouble()) return null
        return v.toLong()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** What the desktop's QR code contains. */
class PairingInfo(
    val hosts: List<String>,
    val port: Int,
    /** SHA-256 of the desktop certificate, lowercase hex. */
    val fingerprint: String,
    /** Shared key, base64url without padding (32 bytes). */
    val keyB64: String,
    val name: String,
) {
    val key: ByteArray get() = Base64.getUrlDecoder().decode(keyB64)

    companion object {
        /**
         * True for addresses that can only be on the local network: private IPv4 (10/8, 172.16/12,
         * 192.168/16), carrier-grade NAT and VPNs like Tailscale (100.64/10), link-local, loopback,
         * IPv6 unique-local (fc00::/7) and link-local (fe80::/10), and `.local` mDNS names.
         * Literal parsing only: never resolves names.
         */
        fun isLocalAddress(host: String): Boolean {
            val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
            if (h.endsWith(".local") && h.length > 6 && h.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return true
            val parts = h.split(".")
            if (parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }) {
                val b = parts.map { it.toInt() }
                if (b.any { it > 255 }) return false
                return b[0] == 10 ||
                    (b[0] == 172 && b[1] in 16..31) ||
                    (b[0] == 192 && b[1] == 168) ||
                    (b[0] == 169 && b[1] == 254) ||
                    (b[0] == 100 && b[1] in 64..127) ||
                    b[0] == 127
            }
            if (h.contains(":") && h.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '%' || it.isLetter() }) {
                val addr = h.substringBefore('%')
                if (addr == "::1") return true
                val first = addr.substringBefore(':').ifEmpty { return false }.toIntOrNull(16) ?: return false
                return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
            }
            return false
        }

        /** Parse and validate a seam://pair link. Throws IllegalArgumentException if invalid. */
        fun parse(uri: String): PairingInfo {
            val query = uri.trim().removePrefix("seam://pair?")
            require(query != uri.trim()) { "Not a Seam pairing link" }
            val params = query.split("&").associate { part ->
                val i = part.indexOf('=')
                if (i < 0) part to "" else
                    part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }
            require(params["v"] == Protocol.VERSION.toString()) { "Unsupported pairing link version" }
            val hosts = params["host"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            require(hosts.isNotEmpty()) { "Missing host" }
            // The computer is on the local network. Refusing other addresses means a link on a
            // web page can't point the phone at a server on the internet.
            require(hosts.all { isLocalAddress(it) }) { "The computer's address must be on your local network" }
            val port = params["port"]?.toIntOrNull()
            require(port != null && port in 1..65535) { "Invalid port" }
            val fp = params["fp"].orEmpty().lowercase()
            require(fp.length == 64 && fp.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid fingerprint" }
            val keyB64 = params["key"].orEmpty()
            val keyBytes = try {
                Base64.getUrlDecoder().decode(keyB64)
            } catch (e: IllegalArgumentException) {
                null
            }
            require(keyBytes != null && keyBytes.size == 32) { "Invalid key" }
            val name = params["name"]
            require(name != null) { "Missing name" }
            return PairingInfo(hosts, port, fp, keyB64, name)
        }
    }
}
