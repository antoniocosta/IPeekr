package net.uncorp.ipeekr.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateTest {
    private val v = mapOf(
        "type" to "WiFi", "name" to "Home", "vpn" to null,
        "ip-external" to "1.2.3.4", "standard" to null, "dbm" to null,
    )

    @Test fun replaces() {
        assertEquals(listOf("WiFi", "Home"), Template.render("<type> <vpn>\n<name>", v))
    }

    @Test fun dropsLinesWhoseTokensAreAllEmpty() {
        assertEquals(listOf("[1.2.3.4]"), Template.render("<standard> <dbm>\n[<ip-external>]", v))
    }

    @Test fun keepsLiteralLinesAndUnknownTokens() {
        assertEquals(listOf("hello", "<nope>", ""), Template.render("hello\n<nope>\n", v))
    }

    @Test fun partialLineKept() {
        assertEquals(listOf("WiFi Home"), Template.render("<type> <vpn> <name>", v))
    }

    @Test fun defaultTemplateOffline() {
        val lines = Template.render(Template.DEFAULT, Template.values(NetSnapshot(updatedAt = 1)))
        assertEquals(listOf("Offline"), lines)
    }

    @Test fun tidiesAroundEmptyTags() {
        val w = mapOf("speed" to "300 Mbps", "channel" to null, "dbm" to "-60 dBm", "city" to null, "country" to "Portugal", "flag" to null)
        assertEquals(listOf("300 Mbps (-60 dBm)", "Portugal"), Template.render("<speed> (<channel> <dbm>)\n<flag> <city>, <country>", w))
    }

    @Test fun everyTagHasAValue() {
        val names = Template.values(NetSnapshot()).keys
        Template.GROUPS.flatMap { it.tags }.forEach { assert(it.name in names) { "no value for <${it.name}>" } }
    }

    @Test fun insertSpacesBetweenTags() {
        assertEquals("<type> <name>" to 13, Template.insert("<type>", 6, 6, "name"))
        assertEquals("<name> <type>" to 6, Template.insert("<type>", 0, 0, "name"))
        assertEquals("(<dbm>)" to 6, Template.insert("()", 1, 1, "dbm"))
        assertEquals("a <dbm>" to 7, Template.insert("a ", 2, 2, "dbm"))
    }

    @Test fun migratesDuplicates() {
        assertEquals("<type> <name>", Template.migrate("<type> <name> <ssid>"))
        assertEquals("<name>", Template.migrate("<carrier>"))
        assertEquals("<standard>", Template.migrate("<mobile-type> <wifi-standard>"))
        assertEquals("<speed> (<channel> <dbm> <percent>)", Template.migrate("<wifi>"))
    }

    @Test fun tidiesSeparators() {
        val w = mapOf("a" to null, "b" to "B", "c" to null, "d" to "D", "city" to null, "country" to "NL")
        assertEquals(listOf("B · D", "NL"), Template.render("<a> · <b> · <c> · <d>\n<city>, <country>", w))
    }

    @Test fun defaultTemplateOnWifi() {
        val v = mapOf(
            "type" to "WiFi", "name" to "SSIDname", "encryption" to "WPA3", "speed" to "306 Mbps", "band" to "5 GHz", "channel" to "36",
            "dbm" to "-59dBm", "percent" to "41%", "ip-internal" to "192.168.1.176", "ip-external" to "188.88.145.36",
            "city" to "Amsterdam", "flag" to "🇳🇱", "country" to "The Netherlands", "hostname" to "36-145-88-188.ftth.glasoperator.nl",
        )
        assertEquals(
            listOf(
                "WiFi SSIDname WPA3", "306 Mbps 5 GHz (36 -59dBm 41%)", "192.168.1.176 [188.88.145.36]",
                "Amsterdam 🇳🇱 The Netherlands", "36-145-88-188.ftth.glasoperator.nl",
            ),
            Template.render(Template.DEFAULT, v),
        )
    }
}
