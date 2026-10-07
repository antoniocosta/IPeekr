package net.uncorp.ipeekr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test fun channels() {
        assertEquals(1, Format.channel(2412))
        assertEquals(6, Format.channel(2437))
        assertEquals(13, Format.channel(2472))
        assertEquals(14, Format.channel(2484))
        assertEquals(36, Format.channel(5180))
        assertEquals(165, Format.channel(5825))
        assertEquals(1, Format.channel(5955))
        assertEquals(233, Format.channel(7115))
        assertNull(Format.channel(null))
        assertNull(Format.channel(1234))
    }

    @Test fun bands() {
        assertEquals("2.4 GHz", Format.band(2437))
        assertEquals("5 GHz", Format.band(5180))
        assertEquals("6 GHz", Format.band(5955))
    }

    @Test fun rssi() {
        assertEquals(41, Format.rssiPercent(-59))   // IP Widget's reference screenshot: -59dBm 41%
        assertEquals(0, Format.rssiPercent(-120))
        assertEquals(70, Format.rssiPercent(-30))
        assertEquals(100, Format.cellPercent(70, lteOrNr = true))
        assertEquals(49, Format.cellPercent(36, lteOrNr = true))
        assertEquals(0, Format.cellPercent(2, lteOrNr = true))
        assertEquals(50, Format.cellPercent(14, lteOrNr = false))
        assertEquals(100, Format.cellPercent(31, lteOrNr = false))
    }

    @Test fun subnet() {
        assertEquals("255.255.255.0", Format.netmask(24))
        assertEquals("255.255.240.0", Format.netmask(20))
        assertEquals("192.168.1.0/24", Format.cidr("192.168.1.77", 24))
        assertEquals("192.168.1.255", Format.broadcast("192.168.1.77", 24))
        assertEquals("10.0.15.255", Format.broadcast("10.0.2.16", 20))
        assertEquals(null, Format.cidr(null, 24))
    }

    @Test fun signal() {
        assertEquals(80, Format.channelWidthMhz(2))
        assertEquals("~12 Mbps", Format.estimate(12_500))
    }

    @Test fun misc() {
        assertEquals("1d 1h 1m", Format.duration(90061))
        assertEquals("1h", Format.duration(3600))
        assertEquals(null, Format.mac("02:00:00:00:00:00"))
        assertEquals(true, Format.isRandomMac("DA:A1:19:00:00:01"))
        assertEquals(false, Format.isRandomMac("00:13:10:85:FE:01"))
    }

    @Test fun wifiStandard() {
        assertEquals("WiFi 4", Format.wifiStandard(4))
        assertEquals("WiFi 6", Format.wifiStandard(6))
        assertEquals("WiFi 7", Format.wifiStandard(8))
        assertEquals(null, Format.wifiStandard(0))
        assertEquals(null, Format.wifiStandard(null))
    }

    @Test fun flags() {
        assertEquals("🇳🇱", Format.flag("NL"))
        assertEquals("🇵🇹", Format.flag("pt"))
        assertNull(Format.flag("NLD"))
        assertNull(Format.flag(null))
    }

    @Test fun ssid() {
        assertEquals("Home", Format.ssid("\"Home\""))
        assertNull(Format.ssid("<unknown ssid>"))
        assertNull(Format.ssid(null))
    }

    @Test fun ipv6Sorting() {
        val r = Format.sortIpv6(listOf("fe80::1%wlan0", "fd00::5", "2a02:a46::1", "::1"))
        assertEquals(listOf("2a02:a46::1", "fd00::5"), r)
    }

    @Test fun names() {
        assertEquals("WPA3", Format.security(4))
        assertEquals("4G LTE", Format.mobileType(13))
        assertEquals("5G", Format.mobileType(20))
    }
}
