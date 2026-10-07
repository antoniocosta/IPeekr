package net.uncorp.ipeekr.data

/**
 * The widget text is a user template with <placeholders>, e.g. "<name>\n[<ip-external>]".
 *  - Unknown placeholders are left as typed.
 *  - A line whose placeholders are all empty is dropped (so WiFi lines vanish on mobile, etc.).
 *  - Lines without placeholders are always shown.
 *
 * The tags cover every field IP Widget can display, one tag per field, plus a few extras.
 * Fields that can't both apply at once share one tag: <name> is the SSID on WiFi and the operator on
 * mobile; <standard>, <speed>, <dbm> and <percent> likewise follow the active connection.
 * <asu> is mobile-only (as in IP Widget): ASU is a cellular unit, WiFi has none.
 */
object Template {
    /** What the widget shows until the user edits it. Lines with no data hide themselves. */
    val DEFAULT = """
        <type> <name> <encryption>
        <speed> <band> (<channel> <dbm> <percent>)
        <ip-internal> [<ip-external>]
        <city> <flag> <country>
        <hostname>
    """.trimIndent()

    /** Earlier builds' defaults: a saved template equal to one of these was never edited, so it becomes [DEFAULT]. */
    val OLD_DEFAULTS = setOf(
        """
        <type> <name> <encryption>
        <speed> (<channel> <dbm> <percent>)
        <ip-internal> [<ip-external>]
        <city> <flag> <country>
        <hostname>
        """.trimIndent(),
        """
        <type> <name> <standard> <vpn>
        <speed> · <dbm> · <asu> · <percent>
        ch <channel> · <freq> · <band> · <channel-width>
        <encryption> · <ap-vendor> · <bssid>
        <ap-capabilities>
        <ip-internal> · <netmask> · <cidr>
        Broadcast <broadcast>
        Gateway <gateway>
        DHCP <dhcp> · <dhcp-lease>
        DNS <dns1> <dns2>
        Private DNS <private-dns>
        <iface> · <mac> · <vendor>
        VPN <vpn-ip>
        [<ip-external>]
        <hostname>
        <flag> <city>, <country> (<country-code>)
        <wifi-enabled> · Battery <battery>
        """.trimIndent(),
    )

    data class Tag(val name: String, val description: String)
    data class Group(val title: String, val tags: List<Tag>)

    /** All tags, grouped, in the order shown in the app. */
    val GROUPS: List<Group> = listOf(
        Group("Connection", listOf(
            Tag("type", "WiFi / Mobile / Ethernet / Offline"),
            Tag("name", "WiFi SSID or mobile operator"),
            Tag("standard", "WiFi 4/5/6/7 or 2G/3G/4G/5G"),
            Tag("vpn", "\"VPN\" while a VPN is up"),
            Tag("wifi-enabled", "WiFi on / WiFi off"),
        )),
        Group("Speed & signal", listOf(
            Tag("speed", "WiFi link speed (mobile: estimate)"),
            Tag("dbm", "signal in dBm"),
            Tag("asu", "mobile signal in ASU (Arbitrary Strength Unit)"),
            Tag("percent", "signal in %"),
        )),
        Group("WiFi", listOf(
            Tag("channel", "channel number"),
            Tag("freq", "frequency (MHz)"),
            Tag("band", "2.4 / 5 / 6 GHz, or 4G / 5G"),
            Tag("channel-width", "20 / 40 / 80 / 160 MHz"),
            Tag("encryption", "Open / WPA2 / WPA3"),
            Tag("bssid", "access point MAC"),
            Tag("ap-vendor", "access point maker"),
            Tag("ap-capabilities", "[WPA2-PSK-CCMP][ESS]"),
        )),
        Group("Local network", listOf(
            Tag("ip-internal", "local IPv4"),
            Tag("ipv6-internal", "local IPv6"),
            Tag("netmask", "255.255.255.0"),
            Tag("cidr", "192.168.1.0/24"),
            Tag("broadcast", "broadcast address"),
            Tag("gateway", "router"),
            Tag("dhcp", "DHCP server"),
            Tag("dhcp-lease", "DHCP lease time"),
            Tag("dns1", "DNS 1"),
            Tag("dns2", "DNS 2"),
            Tag("dns1-v6", "IPv6 DNS 1"),
            Tag("dns2-v6", "IPv6 DNS 2"),
            Tag("private-dns", "Private DNS host"),
            Tag("iface", "interface (wlan0 …)"),
            Tag("mac", "device MAC (hidden on Android 10+)"),
            Tag("vendor", "device maker, from MAC"),
            Tag("vpn-ip", "VPN address"),
        )),
        Group("Internet", listOf(
            Tag("ip-external", "public IPv4"),
            Tag("ipv6-external", "public IPv6"),
            Tag("hostname", "reverse hostname"),
            Tag("city", "city"),
            Tag("country", "country"),
            Tag("country-code", "NL, PT …"),
            Tag("flag", "🇳🇱"),
        )),
        Group("Device", listOf(
            Tag("battery", "battery %"),
        )),
    )

    /** Tags from earlier builds -> what replaces them (applied when a saved template is loaded). */
    val REPLACED = mapOf(
        "security" to "<encryption>", "rssi" to "<dbm>", "signal" to "<percent>",
        // duplicates of other tags
        "ssid" to "<name>", "carrier" to "<name>",
        "mobile-type" to "<standard>", "wifi-standard" to "<standard>",
        "wifi" to "<speed> (<channel> <dbm> <percent>)", "channel-freq" to "<channel> (<freq>)",
    )

    /** Applies [REPLACED] and collapses a tag repeated back to back ("<name> <name>"). */
    fun migrate(line: String): String {
        var l = REPLACED.entries.fold(line) { acc, (old, new) -> acc.replace("<$old>", new) }
        val repeat = Regex("(<[a-z0-9-]+>)(\\s+\\1)+")
        while (repeat.containsMatchIn(l)) l = repeat.replace(l) { it.groupValues[1] }
        return l
    }

    /**
     * Inserts "<name>" at the cursor, adding a space when it would touch another tag
     * ("<type>" + "<name>" -> "<type> <name>"). Returns the new text and cursor position.
     */
    fun insert(text: String, selStart: Int, selEnd: Int, name: String): Pair<String, Int> {
        val before = text.substring(0, selStart)
        val after = text.substring(selEnd)
        val token = (if (before.endsWith(">")) " " else "") + "<$name>" + (if (after.startsWith("<")) " " else "")
        val cursor = before.length + token.length - (if (after.startsWith("<")) 1 else 0)
        return before + token + after to cursor
    }

    private val TOKEN = Regex("<([a-z0-9-]+)>")

    fun render(template: String, values: Map<String, String?>): List<String> =
        template.lines().mapNotNull { line ->
            val names = TOKEN.findAll(line).map { it.groupValues[1] }.filter { it in values }.toList()
            if (names.isNotEmpty() && names.all { values[it].isNullOrBlank() }) return@mapNotNull null
            TOKEN.replace(line) { m ->
                val n = m.groupValues[1]
                if (n in values) values[n].orEmpty() else m.value
            }.let(::tidy)
        }

    /** Cleans up what empty tags leave behind: "()", "( x)", stray commas and "·" separators, double spaces. */
    private fun tidy(s: String): String = s
        .replace(Regex("\\(\\s*\\)|\\[\\s*]"), "")
        .replace(Regex("\\(\\s+"), "(").replace(Regex("\\s+\\)"), ")")
        .replace(Regex("(\\s*·\\s*){2,}"), " · ")
        .replace(Regex("^\\s*·\\s*|\\s*·\\s*$"), "")
        .replace(Regex("(^|\\s),\\s*"), "$1")
        .replace(Regex("\\s*,\\s*$"), "")
        .replace(Regex(" {2,}"), " ")
        .trim()

    fun values(s: NetSnapshot?): Map<String, String?> {
        val ext = s?.ext
        val wifi = s?.type == "WiFi"
        val mobile = s?.type == "Mobile"
        val dbm = if (wifi) s?.rssi else s?.mobileDbm
        val dns4 = s?.dns.orEmpty().filter { '.' in it }
        val dns6 = s?.dns.orEmpty().filter { ':' in it }
        val ipv4 = s?.ipv4?.firstOrNull()
        return mapOf(
            // Connection
            "type" to (s?.type ?: if (s != null) "Offline" else null),
            "name" to if (wifi) (s?.ssid ?: "<ssid hidden>") else s?.carrier,
            "standard" to if (wifi) Format.wifiStandard(s?.wifiStandard) else s?.mobileType,
            "vpn" to if (s?.vpn == true) "VPN" else null,
            "wifi-enabled" to s?.wifiEnabled?.let { if (it) "WiFi on" else "WiFi off" },
            // Speed & signal
            "speed" to if (wifi) s?.linkSpeedMbps?.let { "$it Mbps" } else Format.estimate(s?.downKbps),
            "dbm" to dbm?.let { "${it}dBm" },   // "-59dBm", as IP Widget shows it
            "asu" to if (mobile) s?.mobileAsu?.let { "$it asu" } else null,   // a cellular unit; WiFi has none
            "percent" to (if (mobile) s?.mobilePercent else if (wifi) Format.rssiPercent(s?.rssi) else null)?.let { "$it%" },
            // WiFi
            "channel" to Format.channel(s?.frequencyMhz)?.toString(),
            "freq" to s?.frequencyMhz?.let { "$it MHz" },
            "band" to if (mobile) s?.mobileType else Format.band(s?.frequencyMhz),   // mobile: 4G / 5G
            "channel-width" to s?.channelWidthMhz?.let { "$it MHz" },
            "encryption" to s?.security,
            "bssid" to s?.bssid,
            "ap-vendor" to s?.apVendor,
            "ap-capabilities" to s?.apCapabilities,
            // Local network
            "ip-internal" to ipv4,
            "ipv6-internal" to s?.ipv6?.firstOrNull(),
            "netmask" to Format.netmask(s?.prefixLength),
            "cidr" to Format.cidr(ipv4, s?.prefixLength),
            "broadcast" to Format.broadcast(ipv4, s?.prefixLength),
            "gateway" to s?.gateway,
            "dhcp" to s?.dhcpServer,
            "dhcp-lease" to Format.duration(s?.dhcpLeaseSec),
            "dns1" to dns4.getOrNull(0),
            "dns2" to dns4.getOrNull(1),
            "dns1-v6" to dns6.getOrNull(0),
            "dns2-v6" to dns6.getOrNull(1),
            "private-dns" to s?.privateDns,
            "iface" to s?.iface,
            "mac" to s?.mac,
            "vendor" to s?.macVendor,
            "vpn-ip" to s?.vpnIps?.firstOrNull(),
            // Internet
            "ip-external" to ext?.ipv4,
            "ipv6-external" to ext?.ipv6,
            "hostname" to ext?.hostname,
            "city" to ext?.city,
            "country" to ext?.country,
            "country-code" to ext?.countryIso,
            "flag" to Format.flag(ext?.countryIso),
            // Device
            "battery" to s?.battery?.let { if (s.charging) "$it% ⚡" else "$it%" },
        )
    }
}
