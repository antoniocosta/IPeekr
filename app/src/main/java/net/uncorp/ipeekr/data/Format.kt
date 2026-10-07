package net.uncorp.ipeekr.data

/** Pure formatting helpers (no Android dependencies, unit-tested). */
object Format {

    /** WiFi centre frequency (MHz) to channel number, for 2.4 / 5 / 6 GHz. */
    fun channel(freqMhz: Int?): Int? = when (freqMhz) {
        null -> null
        2484 -> 14
        in 2412..2472 -> (freqMhz - 2407) / 5
        5935 -> 2
        in 5955..7115 -> (freqMhz - 5950) / 5
        in 5160..5885 -> (freqMhz - 5000) / 5
        else -> null
    }

    fun band(freqMhz: Int?): String? = when (freqMhz) {
        null -> null
        in 2400..2500 -> "2.4 GHz"
        in 5935..7125 -> "6 GHz"
        in 4900..5900 -> "5 GHz"
        else -> null
    }

    /** WiFi signal quality: dBm + 100, so -100 dBm = 0 %, -59 dBm = 41 %, 0 dBm = 100 %. */
    fun rssiPercent(rssi: Int?): Int? = rssi?.let { (it + 100).coerceIn(0, 100) }

    /**
     * Cell signal quality from ASU. LTE/5G ASU is RSRP + 140 (0..97): 3 -> 0 %, 70+ -> 100 %.
     * 2G/3G ASU is 0..31: 0 -> 0 %, 28+ -> 100 %.
     */
    fun cellPercent(asu: Int?, lteOrNr: Boolean): Int? {
        val a = asu ?: return null
        return if (lteOrNr) ((a.coerceIn(3, 70) - 3) * 100 / 67) else (a.coerceIn(0, 28) * 100 / 28)
    }

    /** ISO 3166-1 alpha-2 ("NL") to a flag emoji (🇳🇱). */
    fun flag(iso: String?): String? {
        val code = iso?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: return null
        return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** Bandwidth estimate in kbps -> "~12 Mbps" (the system's guess, not a measurement). */
    fun estimate(kbps: Int?): String? = kbps?.takeIf { it > 0 }?.let {
        if (it >= 1000) "~${it / 1000} Mbps" else "~$it kbps"
    }

    /** ScanResult.CHANNEL_WIDTH_* to MHz. */
    fun channelWidthMhz(width: Int?): Int? = when (width) {
        0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 160 /* 80+80 */; 5 -> 320
        else -> null
    }

    /** IPv4 prefix length to dotted netmask: 24 -> 255.255.255.0 */
    fun netmask(prefix: Int?): String? {
        val p = prefix?.takeIf { it in 0..32 } ?: return null
        return ipv4String(maskBits(p))
    }

    /** "192.168.1.0/24" */
    fun cidr(ip: String?, prefix: Int?): String? {
        val a = ipv4Int(ip) ?: return null
        val p = prefix?.takeIf { it in 0..32 } ?: return null
        return "${ipv4String(a and maskBits(p))}/$p"
    }

    fun broadcast(ip: String?, prefix: Int?): String? {
        val a = ipv4Int(ip) ?: return null
        val p = prefix?.takeIf { it in 0..32 } ?: return null
        return ipv4String(a or maskBits(p).inv())
    }

    /** 90061 -> "1d 1h 1m"; 3600 -> "1h" */
    fun duration(sec: Int?): String? {
        val s = sec?.takeIf { it > 0 } ?: return null
        val parts = listOfNotNull(
            (s / 86400).takeIf { it > 0 }?.let { "${it}d" },
            (s % 86400 / 3600).takeIf { it > 0 }?.let { "${it}h" },
            (s % 3600 / 60).takeIf { it > 0 }?.let { "${it}m" },
        )
        return parts.joinToString(" ").ifEmpty { "${s}s" }
    }

    /** "02:00:00:00:00:00" and similar placeholders mean Android hid the MAC. */
    fun mac(raw: String?): String? =
        raw?.uppercase()?.takeIf { it.length == 17 && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }

    /** Locally administered (randomised) MACs have bit 1 of the first octet set. */
    fun isRandomMac(mac: String?): Boolean =
        mac?.take(2)?.toIntOrNull(16)?.let { it and 0x02 != 0 } ?: false

    private fun maskBits(p: Int): Int = if (p == 0) 0 else -1 shl (32 - p)
    private fun ipv4Int(ip: String?): Int? {
        val o = ip?.split('.')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 && it.all { b -> b in 0..255 } } ?: return null
        return (o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]
    }
    private fun ipv4String(a: Int): String = listOf(24, 16, 8, 0).joinToString(".") { ((a ushr it) and 0xFF).toString() }

    /** WifiInfo.WIFI_STANDARD_* (API 30) to the marketing name. */
    fun wifiStandard(std: Int?): String? = when (std) {
        1 -> "WiFi 1-3"      // LEGACY: 802.11a/b/g
        4 -> "WiFi 4"        // 802.11n
        5 -> "WiFi 5"        // 802.11ac
        6 -> "WiFi 6"        // 802.11ax (6E on the 6 GHz band)
        7 -> "WiGig"         // 802.11ad
        8 -> "WiFi 7"        // 802.11be
        else -> null
    }

    /** WifiInfo.SECURITY_TYPE_* (API 31) to a short name. */
    fun security(type: Int?): String? = when (type) {
        0 -> "Open"
        1 -> "WEP"
        2 -> "WPA2"
        3 -> "WPA2-Enterprise"
        4 -> "WPA3"
        5, 9 -> "WPA3-Enterprise"
        6 -> "OWE"
        7, 8 -> "WAPI"
        10 -> "OSEN"
        11, 12 -> "Passpoint"
        13 -> "DPP"
        else -> null
    }

    /** TelephonyManager.NETWORK_TYPE_* to a user-facing name. */
    fun mobileType(type: Int?): String? = when (type) {
        1 -> "2G GPRS"
        2 -> "2G EDGE"
        4, 7, 11 -> "2G"
        16 -> "2G GSM"
        3 -> "3G UMTS"
        5, 6, 12, 14 -> "3G"
        8 -> "3G HSDPA"
        9 -> "3G HSUPA"
        10 -> "3G HSPA"
        15 -> "3G HSPA+"
        17 -> "3G TD-SCDMA"
        13 -> "4G LTE"
        18 -> "IWLAN"
        19 -> "4G LTE-CA"
        20 -> "5G"
        else -> null
    }

    /** TelephonyDisplayInfo.overrideNetworkType, as the status bar names it; null = no override. */
    fun mobileOverride(type: Int?): String? = when (type) {
        1 -> "4G LTE-CA"   // LTE_CA
        2 -> "4G LTE+"     // LTE_ADVANCED_PRO
        3 -> "5G"          // NR_NSA
        4 -> "5G+"         // NR_NSA_MMWAVE (deprecated, still sent by some modems)
        5 -> "5G+"         // NR_ADVANCED
        else -> null
    }

    /** Strip the quotes Android puts around SSIDs; null for the redacted placeholder. */
    fun ssid(raw: String?): String? {
        val s = raw?.removeSurrounding("\"")?.trim()
        return if (s.isNullOrEmpty() || s == "<unknown ssid>" || s == "0x") null else s
    }

    /** Sort IPv6 so global addresses come first, then ULA/site-local; drops link-local. */
    fun sortIpv6(addrs: List<String>): List<String> = addrs
        .map { it.substringBefore('%') }
        .filterNot { it.lowercase().startsWith("fe80:") || it == "::1" }
        .sortedBy { a ->
            val l = a.lowercase()
            when {
                l.startsWith("fc") || l.startsWith("fd") || l.startsWith("fec0") -> 1
                else -> 0
            }
        }
}
