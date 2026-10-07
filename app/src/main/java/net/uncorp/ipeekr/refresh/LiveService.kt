package net.uncorp.ipeekr.refresh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import net.uncorp.ipeekr.MainActivity
import net.uncorp.ipeekr.R
import net.uncorp.ipeekr.data.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * "Instant updates" (opt-in): a foreground service that listens to every network change, including a
 * switch between two WiFi networks, which the background triggers can't see. Android requires the
 * notification while it runs; its channel is silent and can be turned off.
 * Also refreshes when the screen is unlocked, so speed and signal are fresh when you look.
 */
class LiveService : Service() {
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { Triggers.refreshNow(this) }
    /** Transports per network: capability changes that only update signal strength are ignored. */
    private val transports = mutableMapOf<Network, Long>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) { transports.remove(network); changed() }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = changed()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val t = (0..10).filter { caps.hasTransport(it) }.fold(0L) { acc, i -> acc or (1L shl i) }
            if (transports.put(network, t) != t) changed()
        }
    }

    private val unlock = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = changed()
    }

    /** Events come in bursts (network up, then its addresses, then validation): refresh once after. */
    private fun changed() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 1000)
    }

    override fun onCreate() {
        super.onCreate()
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // also VPNs
            .build()
        cm.registerNetworkCallback(req, callback, handler)
        registerReceiver(unlock, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        runCatching { cm.unregisterNetworkCallback(callback) }
        runCatching { unregisterReceiver(unlock) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Instant updates", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while IPeekr updates on every network change. You can turn it off."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Instant updates on")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "instant"

        /** Starts or stops the service to match the setting. Starting can fail from the background. */
        fun apply(ctx: Context, on: Boolean) {
            val intent = Intent(ctx, LiveService::class.java)
            if (on) runCatching { ctx.startForegroundService(intent) } else ctx.stopService(intent)
        }

        /** For triggers that run without the UI (boot, update, widget tap): restart it if it's on. */
        suspend fun applySaved(ctx: Context) {
            if (SettingsStore.flow(ctx).first().instant) apply(ctx, true)
        }
    }
}
