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
    ): String = JSONObject().put("type", "notification").put("id", id).put("app", app)
        .put("app_name", appName).put("title", title).put("text", text).put("time", time)
        .toString()

    fun notificationRemoved(id: String): String =
        JSONObject().put("type", "notification_removed").put("id", id).toString()

    fun battery(level: Int, charging: Boolean): String =
        JSONObject().put("type", "battery").put("level", level.coerceIn(0, 100))
            .put("charging", charging).toString()

    fun pong(): String = JSONObject().put("type", "pong").toString()

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
