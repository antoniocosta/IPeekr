package net.uncorp.ipeekr.data

import org.json.JSONArray
import org.json.JSONObject

/** Everything the widget shows. Null = unknown / not applicable. */
data class NetSnapshot(
    val type: String? = null,            // "WiFi", "Mobile", "Ethernet", "Bluetooth", "USB", null = offline
    // WiFi
    val ssid: String? = null,
    val ssidHidden: Boolean = false,     // true when SSID is redacted (no location permission/service)
    val bssid: String? = null,
    val apVendor: String? = null,        // from the BSSID's OUI (bundled database)
    val apCapabilities: String? = null,  // ScanResult.capabilities, e.g. "[WPA2-PSK-CCMP][ESS][WPS]"
    val security: String? = null,
    val wifiStandard: Int? = null,       // WifiInfo.WIFI_STANDARD_* (API 30+): 4=n, 5=ac, 6=ax, 8=be
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val channelWidthMhz: Int? = null,
    val rssi: Int? = null,
    val wifiEnabled: Boolean? = null,
    // Mobile
    val carrier: String? = null,
    val mobileType: String? = null,
    val mobileDbm: Int? = null,
    val mobileAsu: Int? = null,
    val mobilePercent: Int? = null,      // from ASU, see Format.cellPercent
    val downKbps: Int? = null,           // NetworkCapabilities bandwidth estimate (non-WiFi "speed")
    // Local network
    val iface: String? = null,
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
    val prefixLength: Int? = null,       // of the first IPv4 address
    val gateway: String? = null,
    val dhcpServer: String? = null,
    val dhcpLeaseSec: Int? = null,
    val dns: List<String> = emptyList(),
    val privateDns: String? = null,
    val mac: String? = null,             // device MAC; Android 10+ hides it from apps
    val macVendor: String? = null,
    val vpn: Boolean = false,
    val vpnIps: List<String> = emptyList(),
    // Device
    val battery: Int? = null,
    val charging: Boolean = false,
    val ext: ExternalInfo? = null,
    val networkKey: String? = null,      // identifies the network, for the external-IP cache
    val updatedAt: Long = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("type", type); put("ssid", ssid); put("ssidHidden", ssidHidden); put("bssid", bssid)
        put("apVendor", apVendor); put("apCapabilities", apCapabilities); put("security", security)
        put("wifiStandard", wifiStandard); put("linkSpeedMbps", linkSpeedMbps); put("frequencyMhz", frequencyMhz)
        put("channelWidthMhz", channelWidthMhz); put("rssi", rssi); put("wifiEnabled", wifiEnabled)
        put("carrier", carrier); put("mobileType", mobileType); put("mobileDbm", mobileDbm)
        put("mobileAsu", mobileAsu); put("mobilePercent", mobilePercent); put("downKbps", downKbps)
        put("iface", iface); put("ipv4", JSONArray(ipv4)); put("ipv6", JSONArray(ipv6))
        put("prefixLength", prefixLength); put("gateway", gateway); put("dhcpServer", dhcpServer)
        put("dhcpLeaseSec", dhcpLeaseSec); put("dns", JSONArray(dns)); put("privateDns", privateDns)
        put("mac", mac); put("macVendor", macVendor); put("vpn", vpn); put("vpnIps", JSONArray(vpnIps))
        put("battery", battery); put("charging", charging)
        put("ext", ext?.toJsonObject()); put("networkKey", networkKey); put("updatedAt", updatedAt)
    }.toString()

    companion object {
        fun fromJson(s: String): NetSnapshot = JSONObject(s).run {
            NetSnapshot(
                type = str("type"), ssid = str("ssid"), ssidHidden = optBoolean("ssidHidden"), bssid = str("bssid"),
                apVendor = str("apVendor"), apCapabilities = str("apCapabilities"), security = str("security"),
                wifiStandard = int("wifiStandard"), linkSpeedMbps = int("linkSpeedMbps"),
                frequencyMhz = int("frequencyMhz"), channelWidthMhz = int("channelWidthMhz"), rssi = int("rssi"),
                wifiEnabled = if (has("wifiEnabled") && !isNull("wifiEnabled")) optBoolean("wifiEnabled") else null,
                carrier = str("carrier"), mobileType = str("mobileType"), mobileDbm = int("mobileDbm"),
                mobileAsu = int("mobileAsu"), mobilePercent = int("mobilePercent"), downKbps = int("downKbps"),
                iface = str("iface"), ipv4 = list("ipv4"), ipv6 = list("ipv6"), prefixLength = int("prefixLength"),
                gateway = str("gateway"), dhcpServer = str("dhcpServer"), dhcpLeaseSec = int("dhcpLeaseSec"),
                dns = list("dns"), privateDns = str("privateDns"), mac = str("mac"), macVendor = str("macVendor"),
                vpn = optBoolean("vpn"), vpnIps = list("vpnIps"), battery = int("battery"), charging = optBoolean("charging"),
                ext = optJSONObject("ext")?.let(ExternalInfo::fromJsonObject),
                networkKey = str("networkKey"), updatedAt = optLong("updatedAt"),
            )
        }
    }
}

data class ExternalInfo(
    val ipv4: String? = null,
    val ipv6: String? = null,
    val city: String? = null,
    val country: String? = null,
    val countryIso: String? = null,
    val hostname: String? = null,
    val networkKey: String? = null,
    val fetchedAt: Long = 0,
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("ipv4", ipv4); put("ipv6", ipv6); put("city", city); put("country", country)
        put("countryIso", countryIso); put("hostname", hostname); put("networkKey", networkKey)
        put("fetchedAt", fetchedAt)
    }

    companion object {
        fun fromJsonObject(o: JSONObject) = ExternalInfo(
            o.str("ipv4"), o.str("ipv6"), o.str("city"), o.str("country"),
            o.str("countryIso"), o.str("hostname"), o.str("networkKey"), o.optLong("fetchedAt"),
        )
    }
}

internal fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifBlank { null }
private fun JSONObject.int(k: String): Int? = if (!has(k) || isNull(k)) null else optInt(k)
private fun JSONObject.list(k: String): List<String> =
    optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
