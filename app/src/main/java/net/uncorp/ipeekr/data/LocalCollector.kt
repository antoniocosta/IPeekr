package net.uncorp.ipeekr.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import kotlin.coroutines.resume

/** Reads everything that's available on the device itself (no network requests). */
class LocalCollector(private val ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)

    /** The network that carries traffic, and (if any) the VPN on top of it. */
    data class Active(val network: Network, val caps: NetworkCapabilities, val lp: LinkProperties?, val vpn: Network?)

    @Suppress("DEPRECATION") // allNetworks: the only way to see the VPN's underlying network on API 26-30
    fun active(): Active? {
        val def = cm.activeNetwork ?: return null
        val defCaps = cm.getNetworkCapabilities(def) ?: return null
        val vpn = if (defCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) def
        else cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        if (!defCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return Active(def, defCaps, cm.getLinkProperties(def), vpn)
        }
        // Default is the VPN: describe the physical network underneath it instead.
        val under = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) ->
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .maxByOrNull { (_, c) -> if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 1 }
            ?: return Active(def, defCaps, cm.getLinkProperties(def), vpn)
        return Active(under.first, under.second, cm.getLinkProperties(under.first), vpn)
    }

    suspend fun collect(): NetSnapshot {
        val now = System.currentTimeMillis()
        val device = deviceInfo(NetSnapshot(updatedAt = now))
        val a = active() ?: return device
        val caps = a.caps
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            Build.VERSION.SDK_INT >= 31 && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB"
            else -> "Other"
        }
        val lp = a.lp
        val links = lp?.linkAddresses.orEmpty()
        val v4Link = links.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        val ipv4 = links.map { it.address }.filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress }.mapNotNull { it.hostAddress }
        val ipv6 = Format.sortIpv6(links.map { it.address }.filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress })
        val vpnIps = a.vpn?.let { cm.getLinkProperties(it) }?.linkAddresses
            ?.mapNotNull { it.address.hostAddress?.substringBefore('%') }
            ?.filterNot { it.startsWith("fe80") }.orEmpty()
        val gateways = lp?.routes.orEmpty().filter { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }.mapNotNull { it.gateway }
        val gateway = (gateways.firstOrNull { it is Inet4Address } ?: gateways.firstOrNull())?.hostAddress?.substringBefore('%')

        var snap = device.copy(
            type = type, iface = lp?.interfaceName, ipv4 = ipv4, ipv6 = ipv6,
            prefixLength = v4Link?.prefixLength,
            gateway = gateway,
            dhcpServer = if (Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress else null,
            dns = lp?.dnsServers.orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') },
            privateDns = if (Build.VERSION.SDK_INT >= 28) lp?.privateDnsServerName else null,
            downKbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            vpn = a.vpn != null, vpnIps = vpnIps,
            networkKey = "$type|${lp?.interfaceName}|${ipv4.firstOrNull()}|${a.vpn != null}",
        )
        if (type == "WiFi") snap = withWifi(snap, a.network)
        if (type == "Mobile") snap = withMobile(snap)
        return snap
    }

    /** Things that don't depend on a connection: WiFi radio on/off, battery, device MAC. */
    @Suppress("DEPRECATION")
    private fun deviceInfo(s: NetSnapshot): NetSnapshot {
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val mac = Format.mac(runCatching { wm.connectionInfo?.macAddress }.getOrNull())
        return s.copy(
            wifiEnabled = runCatching { wm.isWifiEnabled }.getOrNull(),
            battery = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            mac = mac,
            macVendor = mac?.let { Oui.vendor(ctx, it) },
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun withWifi(s: NetSnapshot, network: Network): NetSnapshot {
        @Suppress("DEPRECATION") val dhcp = runCatching { wm.dhcpInfo }.getOrNull()
        val withDhcp = s.copy(
            dhcpServer = s.dhcpServer ?: dhcp?.serverAddress?.takeIf { it != 0 }?.let(::intToIp),
            dhcpLeaseSec = dhcp?.leaseDuration?.takeIf { it > 0 },
        )
        val info = wifiInfo(network) ?: return withDhcp
        val ssid = Format.ssid(info.ssid)
        val bssid = info.bssid?.uppercase()?.takeIf { it != "02:00:00:00:00:00" }
        // Channel width and AP capabilities only exist on the scan result (needs location permission)
        val scan = bssid?.let { b ->
            if (has(Manifest.permission.ACCESS_FINE_LOCATION)) runCatching { wm.scanResults }.getOrNull()
                ?.firstOrNull { it.BSSID.equals(b, ignoreCase = true) } else null
        }
        val prev = SnapshotStore.current(ctx)
        val apVendor = if (prev?.bssid == bssid && prev?.apVendor != null) prev.apVendor else Oui.vendor(ctx, bssid)
        return withDhcp.copy(
            ssid = ssid,
            ssidHidden = ssid == null,
            bssid = bssid,
            apVendor = apVendor,
            apCapabilities = scan?.capabilities?.ifBlank { null },
            channelWidthMhz = scan?.let { Format.channelWidthMhz(it.channelWidth) },
            security = if (Build.VERSION.SDK_INT >= 31) Format.security(info.currentSecurityType) else null,
            wifiStandard = if (Build.VERSION.SDK_INT >= 30) info.wifiStandard.takeIf { it > 0 } else null,
            linkSpeedMbps = info.linkSpeed.takeIf { it > 0 },
            frequencyMhz = info.frequency.takeIf { it > 0 },
            rssi = info.rssi.takeIf { it in -127..-1 },
            networkKey = "${s.networkKey}|$bssid",
        )
    }

    private fun intToIp(a: Int): String = listOf(0, 8, 16, 24).joinToString(".") { ((a ushr it) and 0xFF).toString() }

    /**
     * On API 31+, unredacted WifiInfo (SSID, BSSID) is only delivered to a NetworkCallback created with
     * FLAG_INCLUDE_LOCATION_INFO, and only if we hold location permission (plus background location
     * when not in the foreground). Older versions use the deprecated WifiManager.connectionInfo.
     */
    @SuppressLint("MissingPermission")
    private suspend fun wifiInfo(network: Network): WifiInfo? {
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            return wm.connectionInfo
        }
        val fallback = cm.getNetworkCapabilities(network)?.transportInfo as? WifiInfo
        return withTimeoutOrNull(1500) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                        val wi = c.transportInfo as? WifiInfo ?: return
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(wi)
                    }
                }
                val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
                cm.registerNetworkCallback(req, cb)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        } ?: fallback
    }

    @SuppressLint("MissingPermission")
    private suspend fun withMobile(s: NetSnapshot): NetSnapshot {
        val carrier = tm.networkOperatorName?.ifBlank { null } ?: tm.simOperatorName?.ifBlank { null }
        val type = if (has(Manifest.permission.READ_PHONE_STATE)) {
            // 5G NSA reports LTE as the data type; the status bar's "5G" comes from the display override
            displayOverride() ?: runCatching { Format.mobileType(tm.dataNetworkType) }.getOrNull()
        } else null
        // Signal of the serving cell: dBm, ASU (Arbitrary Strength Unit) and % (API 29+; no permission needed)
        val cell = if (Build.VERSION.SDK_INT >= 29) runCatching { tm.signalStrength?.cellSignalStrengths?.firstOrNull() }.getOrNull() else null
        return s.copy(
            carrier = carrier, mobileType = type,
            mobileDbm = cell?.dbm?.takeIf { it in -140..-1 },
            mobileAsu = cell?.asuLevel?.takeIf { it in 0..97 },
            mobilePercent = Format.cellPercent(
                cell?.asuLevel?.takeIf { it in 0..97 },
                lteOrNr = cell is CellSignalStrengthLte || cell is CellSignalStrengthNr,
            ),
            networkKey = "${s.networkKey}|$carrier",
        )
    }

    /** What the status bar shows (5G, 5G+, LTE+), or null when it shows the plain data type. API 31+. */
    @SuppressLint("MissingPermission")
    private suspend fun displayOverride(): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        val info = withTimeoutOrNull(1000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                    override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) {
                        runCatching { tm.unregisterTelephonyCallback(this) }
                        if (cont.isActive) cont.resume(d)
                    }
                }
                // The current value is delivered right after registering
                runCatching { tm.registerTelephonyCallback(ctx.mainExecutor, cb) }
                    .onFailure { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { runCatching { tm.unregisterTelephonyCallback(cb) } }
            }
        }
        return Format.mobileOverride(info?.overrideNetworkType)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}
