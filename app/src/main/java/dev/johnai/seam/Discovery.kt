package dev.johnai.seam

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * A `_seam._tcp` service found on the local network.
 * [txt] is the decoded TXT record: keys as advertised, values as UTF-8 strings.
 * [hosts] is every address to try, best first; it defaults to [host].
 */
data class DiscoveredService(
    val host: String,
    val port: Int,
    val txt: Map<String, String>,
    val hosts: List<String> = listOf(host),
)

/** TXT `fp`, if present and non-empty. Key match is case-insensitive. */
fun advertisedFingerprint(txt: Map<String, String>): String? =
    txt.entries.firstOrNull { it.key.equals("fp", ignoreCase = true) }?.value?.takeIf { it.isNotEmpty() }

fun matchesFingerprint(service: DiscoveredService, fingerprint: String): Boolean {
    if (fingerprint.isEmpty()) return false
    val advertised = advertisedFingerprint(service.txt) ?: return false
    return advertised.equals(fingerprint, ignoreCase = true)
}

/**
 * Choose the service whose TXT `fp` equals [fingerprint], case-insensitively.
 * A different fingerprint, or a missing `fp`, is never chosen. An empty list yields null.
 */
fun pickDiscoveredService(
    services: List<DiscoveredService>,
    fingerprint: String,
): DiscoveredService? {
    if (fingerprint.isEmpty()) return null
    return services.firstOrNull { matchesFingerprint(it, fingerprint) }
}

/** True for IPv6 fe80::/10 and IPv4 169.254.0.0/16, including a zone id (`fe80::1%wlan0`). */
fun isLinkLocalHost(host: String): Boolean {
    var h = host.trim()
    if (h.startsWith("[")) h = h.removePrefix("[").substringBefore(']')
    h = h.substringBefore('%').lowercase()
    return h.startsWith("fe80:") || h.startsWith("169.254.")
}

/**
 * Addresses to try, best first. A link-local address (often an unusable IPv6 fe80::)
 * is never preferred over a routable one, but is still tried if nothing else works.
 */
fun orderedConnectHosts(hosts: List<String>): List<String> {
    val cleaned = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return cleaned.sortedWith(compareBy { rankHost(it) })
}

private fun rankHost(host: String): Int {
    val linkLocal = isLinkLocalHost(host)
    val v6 = host.contains(':')
    return when {
        !linkLocal && !v6 -> 0
        !linkLocal && v6 -> 1
        linkLocal && !v6 -> 2
        else -> 3
    }
}

/**
 * Fold a later mDNS update into an earlier one. A partial update must not wipe a fingerprint,
 * port, or address that arrived before or after it.
 */
fun mergeDiscovered(previous: DiscoveredService, update: DiscoveredService): DiscoveredService {
    val port = if (update.port in 1..65535) update.port else previous.port
    val txt = HashMap(previous.txt)
    for ((key, value) in update.txt) {
        if (value.isNotEmpty()) txt[key] = value
    }
    val hosts = orderedConnectHosts(previous.hosts + update.hosts + listOf(previous.host, update.host))
    val host = hosts.firstOrNull() ?: previous.host.ifBlank { update.host }
    return DiscoveredService(host, port, txt, hosts)
}

/**
 * The paired service to connect to, or null. Hosts of every matching advertisement are
 * combined so a link-local address is followed by any other address of the same computer.
 * A different fingerprint is never included.
 */
fun serviceToConnect(services: List<DiscoveredService>, fingerprint: String): DiscoveredService? {
    if (pickDiscoveredService(services, fingerprint) == null) return null
    val matching = services.filter { matchesFingerprint(it, fingerprint) }
    val port = matching.firstOrNull { it.port in 1..65535 }?.port ?: return null
    val hosts = orderedConnectHosts(matching.flatMap { it.hosts.ifEmpty { listOf(it.host) } })
    if (hosts.isEmpty()) return null
    val txt = matching.firstOrNull { advertisedFingerprint(it.txt) != null }?.txt ?: matching.first().txt
    return DiscoveredService(hosts.first(), port, txt, hosts)
}

/** Usable record: fingerprint, a real port, and at least one address. A non-empty TXT is not enough. */
fun isResolvedService(service: DiscoveredService): Boolean {
    if (service.port !in 1..65535) return false
    if (advertisedFingerprint(service.txt) == null) return false
    return service.hosts.any { it.isNotBlank() } || service.host.isNotBlank()
}

fun hasRoutableHost(service: DiscoveredService): Boolean {
    val hosts = service.hosts.ifEmpty { listOf(service.host) }
    return hosts.any { it.isNotBlank() && !isLinkLocalHost(it) }
}

/**
 * Browses for the paired computer (`_seam._tcp`, TXT `fp`) for up to 10 seconds.
 * Connecting still pins the certificate; this only supplies a new address.
 */
class SeamDiscovery(private val context: Context) {
    fun find(fingerprint: String, timeoutMs: Long = DISCOVER_TIMEOUT_MS): DiscoveredService? {
        if (fingerprint.isEmpty()) return null
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val services = ConcurrentHashMap<String, DiscoveredService>()
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
        val signal = Object()
        val failed = AtomicBoolean(false)
        val ids = AtomicInteger()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                val type = service.serviceType.orEmpty()
                if (type.isNotEmpty() && !type.contains("_seam._tcp")) return
                pending.add(service)
                wake(signal)
            }

            override fun onServiceLost(service: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {
                wake(signal)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                failed.set(true)
                wake(signal)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            return null
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        try {
            while (millisLeft(deadline) > 0L && !Thread.currentThread().isInterrupted) {
                if (routableMatch(services, fingerprint) != null) break
                val request = pending.poll()
                if (request == null) {
                    if (failed.get()) break
                    awaitSignal(signal, millisLeft(deadline).coerceAtMost(200L))
                    continue
                }
                val key = "s-${ids.incrementAndGet()}"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val watched = watchModern(nsd, request, key, services, signal, deadline)
                    // A non-matching service resolved first must not leave the callback
                    // registered, or the real computer's register fails. Retry once after
                    // that unregister has been awaited.
                    if (!watched && millisLeft(deadline) > 0L && !Thread.currentThread().isInterrupted) {
                        awaitSignal(signal, millisLeft(deadline).coerceAtMost(400L))
                        if (millisLeft(deadline) > 0L) watchModern(nsd, request, key, services, signal, deadline)
                    }
                } else {
                    val outcome = resolveLegacy(nsd, request, millisLeft(deadline))
                    outcome.info?.let { addResolved(services, key, it) }
                    if (Thread.currentThread().isInterrupted) break
                    // Only stop resolving when the single slot is still busy after the drain.
                    // A slow resolve must not abort the rest of the browse.
                    if (!outcome.finished) break
                }
            }
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        if (Thread.currentThread().isInterrupted) return null
        return serviceToConnect(services.values.toList(), fingerprint)
    }

    /**
     * Register a service-info callback and keep it until `fp`, a real port, and an address
     * have arrived — or until this service has gone quiet. Returns false if registration
     * never stuck (the caller may retry once the previous callback has unregistered).
     */
    @SuppressLint("NewApi")
    private fun watchModern(
        nsd: NsdManager,
        request: NsdServiceInfo,
        key: String,
        services: ConcurrentHashMap<String, DiscoveredService>,
        signal: Object,
        deadline: Long,
    ): Boolean {
        val lastEvent = AtomicLong(System.nanoTime())
        val unregistered = CountDownLatch(1)
        val failedReg = AtomicBoolean(false)
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                failedReg.set(true)
                unregistered.countDown()
                wake(signal)
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                addResolved(services, key, serviceInfo)
                lastEvent.set(System.nanoTime())
                wake(signal)
            }

            override fun onServiceLost() {
                wake(signal)
            }

            override fun onServiceInfoCallbackUnregistered() {
                unregistered.countDown()
                wake(signal)
            }
        }
        val registered = try {
            nsd.registerServiceInfoCallback(request, context.mainExecutor, callback)
            true
        } catch (_: Exception) {
            false
        }
        if (!registered) {
            unregistered.countDown()
            return false
        }
        try {
            addResolved(services, key, request)
            while (millisLeft(deadline) > 0L && !Thread.currentThread().isInterrupted && !failedReg.get()) {
                val current = services[key]
                val quiet = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastEvent.get())
                when {
                    // fp + port + a routable address: safe to move on. Link-local-only is not.
                    current != null && isResolvedService(current) && hasRoutableHost(current) -> break
                    current != null && isResolvedService(current) && quiet >= LINK_LOCAL_GRACE_MS -> break
                    current == null && quiet >= SILENT_MS -> break
                    // Missing fp or port: do not treat a partial TXT as final. Wait out the
                    // silence so the follow-up update is merged before we unregister.
                    current != null && !isResolvedService(current) && quiet >= PARTIAL_WAIT_MS -> break
                }
                val slice = millisLeft(deadline).coerceAtMost(200L)
                if (slice <= 0L) break
                awaitSignal(signal, slice)
            }
        } finally {
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (_: Exception) {
                unregistered.countDown()
            }
            try {
                unregistered.await(UNREGISTER_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return !failedReg.get()
    }

    /**
     * Pre-34 resolve. Waits out the callback instead of giving up at a few seconds: that used
     * to abort the browse, drop the in-flight result, and leave the single resolve slot busy
     * so the next round failed with ALREADY_ACTIVE.
     */
    @Suppress("DEPRECATION")
    private fun resolveLegacy(nsd: NsdManager, request: NsdServiceInfo, timeoutMs: Long): ResolveOutcome {
        val latch = CountDownLatch(1)
        val holder = AtomicReference<NsdServiceInfo?>(null)
        val finished = AtomicBoolean(false)
        try {
            nsd.resolveService(request, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    finished.set(true)
                    latch.countDown()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    holder.set(serviceInfo)
                    finished.set(true)
                    latch.countDown()
                }
            })
        } catch (_: Exception) {
            return ResolveOutcome(null, finished = true)
        }
        try {
            val wait = timeoutMs.coerceAtLeast(0L)
            if (wait > 0L) latch.await(wait, TimeUnit.MILLISECONDS)
            if (!finished.get()) latch.await(RESOLVE_DRAIN_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            if (!finished.get()) {
                try {
                    latch.await(RESOLVE_DRAIN_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                }
            }
            Thread.currentThread().interrupt()
        }
        return ResolveOutcome(holder.get(), finished.get())
    }

    private fun routableMatch(
        services: Map<String, DiscoveredService>,
        fingerprint: String,
    ): DiscoveredService? {
        val chosen = serviceToConnect(services.values.toList(), fingerprint) ?: return null
        return if (hasRoutableHost(chosen)) chosen else null
    }

    companion object {
        /** How long to browse after stored hosts fail. */
        const val DISCOVER_TIMEOUT_MS = 10_000L

        /** NsdManager form of the `_seam._tcp` type from the protocol. */
        const val SERVICE_TYPE = "_seam._tcp."

        /** Silence after a partial update before giving up on a later fp/port. */
        private const val PARTIAL_WAIT_MS = 5_000L

        /** Keep listening briefly so a global address can follow a link-local-only update. */
        private const val LINK_LOCAL_GRACE_MS = 2_000L

        /** No callback at all: don't burn the whole browse on a service that will not resolve. */
        private const val SILENT_MS = 2_000L

        /** unregisterServiceInfoCallback is async; the next register must wait for it. */
        private const val UNREGISTER_WAIT_MS = 3_000L

        /** Extra wait so a late pre-34 resolve still delivers and frees the resolve slot. */
        private const val RESOLVE_DRAIN_MS = 10_000L
    }
}

private data class ResolveOutcome(val info: NsdServiceInfo?, val finished: Boolean)

private fun addResolved(
    services: ConcurrentHashMap<String, DiscoveredService>,
    key: String,
    info: NsdServiceInfo,
) {
    val partial = toPartial(info) ?: return
    services.merge(key, partial, ::mergeDiscovered)
}

private fun toPartial(info: NsdServiceInfo): DiscoveredService? {
    val hosts = hostStrings(info)
    val txt = txtMap(info)
    val port = info.port
    if (hosts.isEmpty() && txt.isEmpty() && port !in 1..65535) return null
    return DiscoveredService(hosts.firstOrNull().orEmpty(), port, txt, hosts)
}

/** Every address on the record. [NsdServiceInfo.getHost] is only the fallback: it is often the link-local one. */
@Suppress("DEPRECATION")
private fun hostStrings(info: NsdServiceInfo): List<String> {
    val raw = ArrayList<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        raw.addAll(api34Hosts(info))
    }
    if (raw.isEmpty()) {
        info.host?.hostAddress?.takeIf { it.isNotBlank() }?.let { raw.add(it) }
    }
    return orderedConnectHosts(raw)
}

@SuppressLint("NewApi")
private fun api34Hosts(info: NsdServiceInfo): List<String> {
    val listed = info.hostAddresses ?: return emptyList()
    return listed.mapNotNull { addr -> addr.hostAddress?.takeIf { it.isNotBlank() } }
}

private fun txtMap(info: NsdServiceInfo): Map<String, String> {
    val attrs = info.attributes ?: return emptyMap()
    val out = HashMap<String, String>(attrs.size)
    for ((key, value) in attrs) {
        if (key.isNullOrEmpty()) continue
        val text = (value ?: ByteArray(0)).toString(Charsets.UTF_8).trimEnd('\u0000').trim()
        if (text.isNotEmpty()) out[key] = text
    }
    return out
}

private fun millisLeft(deadlineNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

private fun wake(signal: Object) {
    synchronized(signal) { signal.notifyAll() }
}

private fun awaitSignal(signal: Object, ms: Long) {
    if (ms <= 0L) return
    try {
        synchronized(signal) { signal.wait(ms) }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
