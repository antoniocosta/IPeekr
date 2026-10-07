package net.uncorp.ipeekr.data

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * External IP, geo and reverse hostname.
 *  - primary : ifconfig.co/json (ip, city, country, country_iso, hostname in one call)
 *  - fallback: api.ipify.org (IPv4) + api.db-ip.com free (geo) + local PTR lookup
 *  - IPv6    : api6.ipify.org (fails fast when there's no IPv6 route)
 * Requests go through [network] when given, so they use the network we're describing (see [fetch]).
 */
object ExternalLookup {
    private const val TIMEOUT_MS = 4000

    /**
     * Through [network] first; if that fails, through the default route. A VPN that doesn't allow bypassing
     * (AdGuard's local VPN, for example) makes every connection bound to the underlying network fail at once.
     */
    suspend fun fetch(network: Network?, networkKey: String?): ExternalInfo? =
        lookup(network, networkKey) ?: if (network != null) lookup(null, networkKey) else null

    private suspend fun lookup(network: Network?, networkKey: String?): ExternalInfo? = withContext(Dispatchers.IO) {
        coroutineScope {
            val v6 = async { runCatching { get(network, "https://api6.ipify.org", 2500).trim() }.getOrNull()?.takeIf { ':' in it } }
            val primary = runCatching { ifconfigCo(network) }
                .onFailure { Log.w("IPeekr", "ifconfig.co via ${network ?: "default"}: $it") }.getOrNull()
            val info = primary ?: runCatching { fallback(network) }.getOrNull()
            val ipv6 = v6.await()
            when {
                info != null -> info.copy(ipv6 = info.ipv6 ?: ipv6)
                ipv6 != null -> ExternalInfo(ipv6 = ipv6)
                else -> null
            }?.copy(networkKey = networkKey, fetchedAt = System.currentTimeMillis())
        }
    }

    private fun ifconfigCo(network: Network?): ExternalInfo {
        val o = JSONObject(get(network, "https://ifconfig.co/json"))
        val ip = o.str("ip") ?: error("no ip")
        return ExternalInfo(
            ipv4 = ip.takeIf { ':' !in it },
            ipv6 = ip.takeIf { ':' in it },
            city = o.str("city"),
            country = o.str("country"),
            countryIso = o.str("country_iso"),
            hostname = o.str("hostname") ?: ptr(ip),
        )
    }

    private fun fallback(network: Network?): ExternalInfo {
        val ip = get(network, "https://api.ipify.org").trim()
        require(ip.isNotEmpty())
        val geo = runCatching { JSONObject(get(network, "https://api.db-ip.com/v2/free/$ip")) }.getOrNull()
        return ExternalInfo(
            ipv4 = ip,
            city = geo?.str("city"),
            country = geo?.str("countryName"),
            countryIso = geo?.str("countryCode"),
            hostname = ptr(ip),
        )
    }

    /** Reverse DNS; null when there is no PTR record (getCanonicalHostName then returns the IP). */
    private fun ptr(ip: String): String? = runCatching {
        InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
    }.getOrNull()

    private fun get(network: Network?, url: String, timeout: Int = TIMEOUT_MS): String {
        val u = URL(url)
        val conn = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        return try {
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "IPeekr/1 (Android)")
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
