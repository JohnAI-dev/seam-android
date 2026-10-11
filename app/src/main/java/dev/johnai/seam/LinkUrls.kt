package dev.johnai.seam

import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * http(s) link checks for open_url (protocol/PROTOCOL.md, "Opening links").
 * No Android types, so detection and validation run in JVM unit tests.
 *
 * A share is a single URL only when the whole text is that URL. Text that merely
 * contains a URL is not one. javascript:, intent:, file: and any other scheme are
 * rejected. The URL is at most [Protocol.OPEN_URL_MAX_CHARS] characters.
 *
 * Non-canonical IPv4 is rejected. A WHATWG browser would turn `2130706433`,
 * `127.1`, hex, or octal into a different address (often loopback or private)
 * than the text in the prompt. Names are never resolved: no [java.net.InetAddress].
 *
 * A non-ASCII host is rewritten with [IDN.toASCII] so the prompt and the URL
 * opened later both use punycode, not a Unicode lookalike. [displayHost] keeps
 * an explicit port, which the prompt must not drop.
 */
object LinkUrls {
    /**
     * The shared text if it is exactly one http(s) URL, or null.
     * Surrounding whitespace is ignored; any other text is not.
     */
    fun singleHttpUrl(text: String): String? = openableHttpUrl(text)

    /**
     * [raw] if it is an http(s) URL this phone may offer to open, or null.
     * The scheme is normalized to lowercase. A host is required. An IDN host is
     * replaced with its punycode form so the opened URL matches the prompt.
     */
    fun openableHttpUrl(raw: String): String? {
        val url = canonicalize(raw) ?: return null
        if (hasForbiddenChar(url)) return null
        val parsed = parseHttp(url) ?: return null
        if ('%' in parsed.host) return null
        if (!isAcceptableHost(parsed.host)) return null
        val asciiHost = canonicalHost(parsed) ?: return null
        val rewrite = shouldRewrite(parsed.host, asciiHost, parsed.ipv6)
        val finalUrl = if (rewrite) rebuild(parsed, asciiHost) else url
        if (finalUrl.length > Protocol.OPEN_URL_MAX_CHARS || hasForbiddenChar(finalUrl)) return null
        val check = parseHttp(finalUrl) ?: return null
        val want = if (rewrite) asciiHost else parsed.host
        if (!check.host.equals(want, ignoreCase = true)) return null
        if (check.port != parsed.port) return null
        if ('%' in check.host) return null
        if (canonicalHost(check) == null) return null
        if (!uriAgrees(finalUrl, check)) return null
        return finalUrl
    }

    /** Host of an openable URL, or null. Lowercase, no userinfo, no port. */
    fun hostOf(url: String): String? {
        val safe = openableHttpUrl(url) ?: return null
        return parseHttp(safe)?.host?.lowercase(Locale.ROOT)
    }

    /**
     * Host shown on the confirmation. Punycode for IDN, brackets for IPv6, and
     * an explicit port when the URL has one. Null if [url] must not be opened.
     */
    fun displayHost(url: String): String? {
        val safe = openableHttpUrl(url) ?: return null
        val parsed = parseHttp(safe) ?: return null
        val host = parsed.host.lowercase(Locale.ROOT)
        val body = if (parsed.ipv6) "[$host]" else host
        return if (parsed.port != null) "$body:${parsed.port}" else body
    }

    /** Explicit port, or -1 when the URL has none or must not be opened. */
    fun portOf(url: String): Int {
        val safe = openableHttpUrl(url) ?: return -1
        return parseHttp(safe)?.port ?: -1
    }

    /**
     * Host comparison for the notification tap. [Locale.ROOT] so a Turkish or
     * Azerbaijani default locale does not map I to dotless ı and drop the open.
     */
    fun hostsEqual(a: String, b: String): Boolean {
        fun norm(h: String) = h.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        return norm(a) == norm(b)
    }

    private data class ParsedUrl(
        val scheme: String,
        val userInfo: String?,
        val host: String,
        val ipv6: Boolean,
        val port: Int?,
        val rest: String,
    )

    private fun canonicalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > Protocol.OPEN_URL_MAX_CHARS) return null
        val scheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https://"
            trimmed.startsWith("http://", ignoreCase = true) -> "http://"
            else -> return null
        }
        return scheme + trimmed.substring(scheme.length)
    }

    private fun hasForbiddenChar(url: String): Boolean {
        for (ch in url) {
            // 0x5C is backslash: browsers may treat it as a path separator and retarget the host.
            if (ch.code == 0 || ch.code == 0x5C) return true
            if (ch.isISOControl() || ch.isWhitespace() || Character.isSpaceChar(ch)) return true
            if (ch.category == CharCategory.FORMAT) return true
        }
        return false
    }

    private fun isAcceptableHost(host: String): Boolean {
        if (host.isEmpty() || host.all { it == '.' }) return false
        if (host.any { it == '@' || it == '/' || it == '#' || it == '?' || it == ' ' }) return false
        if (host.any { it.isISOControl() || it.isWhitespace() || Character.isSpaceChar(it) }) return false
        if (host.any { it.category == CharCategory.FORMAT || it.code == 0x5C }) return false
        return true
    }

    /**
     * Authority host, with IPv6 brackets removed. A percent-encoded host is left as written
     * so the caller can reject it: parsers disagree on those.
     */
    private fun parseHttp(url: String): ParsedUrl? {
        val scheme = when {
            url.startsWith("https://") -> "https"
            url.startsWith("http://") -> "http"
            else -> return null
        }
        val rest = url.substring(scheme.length + 3)
        if (rest.isEmpty() || rest[0] == '/' || rest[0] == '?' || rest[0] == '#') return null
        val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authEnd < 0) rest else rest.substring(0, authEnd)
        val tail = if (authEnd < 0) "" else rest.substring(authEnd)
        if (authority.isEmpty()) return null
        val (userInfo, hostPort) = splitUserInfo(authority) ?: return null
        val host = splitHost(hostPort) ?: return null
        return ParsedUrl(scheme, userInfo, host.host, host.ipv6, host.port, tail)
    }

    /** Last '@' outside an IPv6 bracket pair. An '@' inside the brackets is malformed. */
    private fun splitUserInfo(authority: String): Pair<String?, String>? {
        val at = authority.lastIndexOf('@')
        if (at < 0) return null to authority
        val open = authority.indexOf('[')
        if (open >= 0 && at > open) {
            val close = authority.indexOf(']', open + 1)
            if (close < 0 || at < close) return null
        }
        if (at == 0 || at == authority.lastIndex) return null
        return authority.substring(0, at) to authority.substring(at + 1)
    }

    private data class HostPort(val host: String, val ipv6: Boolean, val port: Int?)

    private fun splitHost(hostPort: String): HostPort? {
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end < 2) return null
            val raw = hostPort.substring(1, end)
            if (raw.isEmpty()) return null
            val after = hostPort.substring(end + 1)
            val port = when {
                after.isEmpty() -> null
                else -> parsePort(after) ?: return null
            }
            if (!isIpv6Literal(raw)) return null
            return HostPort(raw, ipv6 = true, port)
        }
        val colon = hostPort.lastIndexOf(':')
        val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
        val port = if (colon >= 0) parsePort(hostPort.substring(colon)) ?: return null else null
        if (host.isEmpty() || ':' in host || '[' in host || ']' in host) return null
        return HostPort(host, ipv6 = false, port)
    }

    private fun parsePort(suffix: String): Int? {
        if (suffix.length < 2 || suffix[0] != ':') return null
        val port = suffix.substring(1)
        if (port.any { it !in '0'..'9' }) return null
        val n = port.toIntOrNull() ?: return null
        return n.takeIf { it in 1..65535 }
    }

    /**
     * True when [java.net.URI] sees the same host, or cannot parse a bracketed IPv6 literal
     * that we already checked. A different host is never accepted. No name lookup.
     */
    private fun uriAgrees(url: String, parsed: ParsedUrl): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return parsed.ipv6
        }
        if (!parsed.scheme.equals(uri.scheme, ignoreCase = true)) return false
        if (uri.isOpaque) return false
        val reported = uri.host?.let { stripBrackets(it) }
        if (reported.isNullOrEmpty()) return parsed.ipv6
        return parsed.host.equals(reported, ignoreCase = true)
    }

    private fun stripBrackets(host: String): String {
        if (host.length >= 2 && host.first() == '[' && host.last() == ']') {
            return host.substring(1, host.length - 1)
        }
        return host
    }

    /**
     * Host to show and, when it differs from the written host, to open.
     * IPv4 must already be a canonical dotted-quad. Other hosts go through IDN.
     */
    private fun canonicalHost(parsed: ParsedUrl): String? {
        if (parsed.ipv6) {
            if (!isIpv6Literal(parsed.host)) return null
            return parsed.host
        }
        if (endsInANumber(parsed.host)) return strictIpv4(parsed.host)
        val ascii = domainToAscii(parsed.host) ?: return null
        if (endsInANumber(ascii)) return strictIpv4(ascii)
        return ascii
    }

    /**
     * Rewrite when the opened host would otherwise differ from the prompt
     * (punycode, or an IDN that normalized to a dotted-quad). Case-only ASCII
     * stays as written; the prompt lowercases with [Locale.ROOT].
     */
    private fun shouldRewrite(originalHost: String, asciiHost: String, ipv6: Boolean): Boolean {
        if (ipv6) return false
        if (originalHost.any { it.code > 127 }) return true
        return !originalHost.equals(asciiHost, ignoreCase = true)
    }

    private fun rebuild(parsed: ParsedUrl, host: String): String {
        val sb = StringBuilder()
        sb.append(parsed.scheme).append("://")
        if (parsed.userInfo != null) sb.append(parsed.userInfo).append('@')
        if (parsed.ipv6) sb.append('[').append(host).append(']') else sb.append(host)
        if (parsed.port != null) sb.append(':').append(parsed.port)
        sb.append(parsed.rest)
        return sb.toString()
    }

    /**
     * Dotted-quad with no leading zeros, or null. Decimal, hex, octal, short
     * (`127.1`), and trailing-dot forms are not the address a browser opens.
     */
    private fun strictIpv4(host: String): String? {
        val value = parseWhatwgIpv4(host) ?: return null
        val canonical = formatIpv4(value)
        return if (host == canonical) canonical else null
    }

    /** WHATWG "ends in a number": the last label is an IPv4 number, ignoring one trailing dot. */
    private fun endsInANumber(host: String): Boolean {
        if (host.isEmpty()) return false
        var parts = host.split('.')
        if (parts.last().isEmpty()) parts = parts.dropLast(1)
        if (parts.isEmpty()) return false
        return parseIpv4Number(parts.last()) != null
    }

    /**
     * WHATWG IPv4 parser, returning the 32-bit value, or null. Does not accept
     * the address: [strictIpv4] still requires the canonical spelling.
     */
    private fun parseWhatwgIpv4(input: String): Long? {
        var parts = input.split('.')
        if (parts.isNotEmpty() && parts.last().isEmpty()) parts = parts.dropLast(1)
        if (parts.isEmpty() || parts.size > 4) return null
        val numbers = LongArray(parts.size)
        for (i in parts.indices) {
            numbers[i] = parseIpv4Number(parts[i]) ?: return null
        }
        for (i in 0 until numbers.lastIndex) {
            if (numbers[i] > 255L) return null
        }
        val maxLast = pow256(5 - numbers.size)
        if (numbers.last() >= maxLast) return null
        var ipv4 = numbers.last()
        var counter = 0
        for (i in 0 until numbers.lastIndex) {
            ipv4 += numbers[i] * pow256(3 - counter)
            counter++
        }
        if (ipv4 !in 0L..0xFFFF_FFFFL) return null
        return ipv4
    }

    /** 256^exp for exp in 0..4. Avoids a shift so the 2^32 bound cannot be masked. */
    private fun pow256(exp: Int): Long {
        var n = 1L
        repeat(exp) { n *= 256L }
        return n
    }

    /** WHATWG IPv4 number: decimal, leading-zero octal, or 0x hex. Empty after 0x/0 is 0. */
    private fun parseIpv4Number(input: String): Long? {
        if (input.isEmpty()) return null
        var s = input
        val radix: Int
        if (s.length >= 2 && (s.startsWith("0x") || s.startsWith("0X"))) {
            radix = 16
            s = s.substring(2)
            if (s.isEmpty()) return 0L
            if (s.any { it !in "0123456789abcdefABCDEF" }) return null
        } else if (s.length >= 2 && s[0] == '0') {
            radix = 8
            s = s.substring(1)
            if (s.isEmpty()) return 0L
            if (s.any { it !in '0'..'7' }) return null
        } else {
            radix = 10
            if (s.any { it !in '0'..'9' }) return null
        }
        val n = s.toLongOrNull(radix) ?: return null
        return n.takeIf { it >= 0L }
    }

    private fun formatIpv4(value: Long): String {
        val v = value.toInt()
        return listOf(
            (v ushr 24) and 0xff,
            (v ushr 16) and 0xff,
            (v ushr 8) and 0xff,
            v and 0xff,
        ).joinToString(".")
    }

    /**
     * ASCII host for a domain. Non-ASCII is punycode, lowercased. An ASCII host
     * is returned unchanged when [IDN.toASCII] agrees, so `Example.COM` stays.
     */
    private fun domainToAscii(host: String): String? {
        val ascii = try {
            IDN.toASCII(host)
        } catch (_: Exception) {
            return null
        }
        if (!isDnsHost(ascii)) return null
        if (host.any { it.code > 127 }) return ascii.lowercase(Locale.ROOT)
        if (!host.equals(ascii, ignoreCase = true)) return null
        return host
    }

    private fun isDnsHost(ascii: String): Boolean {
        if (ascii.isEmpty() || ascii.length > 253) return false
        val labels = ascii.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return false
        for (ch in ascii) {
            if (ch == '.') continue
            val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_'
            if (!ok) return false
        }
        return true
    }

    /**
     * Bracketed IPv6 text: eight groups, or one "::" compression, optional dotted IPv4 tail.
     * No zone id (that is a '%', rejected with other encoded hosts).
     */
    private fun isIpv6Literal(host: String): Boolean {
        if (host.isEmpty() || host.length > 45 || ':' !in host) return false
        if (host.any { it !in "0123456789abcdefABCDEF:." }) return false
        if (":::" in host) return false
        val first = host.indexOf("::")
        if (first != host.lastIndexOf("::")) return false
        val groups = ArrayList<String>(8)
        if (first < 0) {
            if (!collectGroups(host, groups, allowIpv4 = '.' in host)) return false
            return groups.size == 8
        }
        val left = host.substring(0, first)
        val right = host.substring(first + 2)
        if (left.isNotEmpty() && !collectGroups(left, groups, allowIpv4 = false)) return false
        if (right.isNotEmpty() && !collectGroups(right, groups, allowIpv4 = '.' in right)) return false
        if ('.' in host && '.' !in right) return false
        return groups.size < 8
    }

    private fun collectGroups(part: String, groups: MutableList<String>, allowIpv4: Boolean): Boolean {
        if (part.isEmpty() || part.startsWith(":") || part.endsWith(":")) return false
        val pieces = part.split(':')
        for (i in pieces.indices) {
            val piece = pieces[i]
            if (piece.isEmpty()) return false
            if ('.' in piece) {
                if (!allowIpv4 || i != pieces.lastIndex || !isIpv4DottedQuad(piece)) return false
                groups.add("v4")
                groups.add("v4")
            } else {
                if (piece.length > 4 || piece.any { it !in "0123456789abcdefABCDEF" }) return false
                groups.add(piece)
            }
        }
        return true
    }

    /** Four decimal octets, used only inside a bracketed IPv6 literal. */
    private fun isIpv4DottedQuad(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return false
            val n = part.toIntOrNull() ?: return false
            if (n !in 0..255) return false
        }
        return true
    }
}
