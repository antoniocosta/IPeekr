package net.uncorp.ipeekr.refresh

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val type = Refresher.refresh(applicationContext, force = inputData.getBoolean(KEY_FORCE, false))
        NetWatch.watch(applicationContext, type)
        return Result.success()
    }

    companion object { const val KEY_FORCE = "force" }
}

/**
 * How refreshes get triggered, without a foreground service or polling:
 *  - a system job that waits for the connection type to change (WiFi -> mobile, etc.; see [NetWatch])
 *  - widget tap / app button -> forced refresh
 *  - a 15-minute periodic job as a safety net (e.g. a switch between two WiFi networks)
 */
object Triggers {
    fun refreshNow(ctx: Context, force: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInputData(workDataOf(RefreshWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("refresh", ExistingWorkPolicy.REPLACE, req)
    }

    /** Idempotent: the periodic job is kept if it already exists. */
    fun install(ctx: Context) {
        val periodic = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
    }
}

/**
 * Waits for a different kind of connection than the current one. The system runs [NetWatchJob]
 * as soon as the app's default network (the VPN's, which carries the transport underneath)
 * matches. A network-change PendingIntent can't do this: Android drops it 5 s after each delivery.
 */
object NetWatch {
    private const val JOB_ID = 0x1BEE4 // well away from WorkManager's sequential job IDs
    private const val KEY_FROM = "from"
    private val TRANSPORTS = mapOf(
        "WiFi" to NetworkCapabilities.TRANSPORT_WIFI,
        "Mobile" to NetworkCapabilities.TRANSPORT_CELLULAR,
        "Ethernet" to NetworkCapabilities.TRANSPORT_ETHERNET,
        "Bluetooth" to NetworkCapabilities.TRANSPORT_BLUETOOTH,
    )

    /** [type] is what's connected now (null = offline). [delayMs] keeps a confused match from looping. */
    fun watch(ctx: Context, type: String?, delayMs: Long = 0) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, NetWatchJob::class.java))
            .setExtras(PersistableBundle().apply { putString(KEY_FROM, type) })
            .setMinimumLatency(delayMs)
        if (Build.VERSION.SDK_INT >= 28) {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // the default network is often a VPN
            // Any of the other transports (offline: any network at all)
            if (type != null) TRANSPORTS.filterKeys { it != type }.values.forEach { req.addTransportType(it) }
            job.setRequiredNetwork(req.build())
        } else {
            job.setRequiredNetworkType(
                when (type) {
                    null -> JobInfo.NETWORK_TYPE_ANY
                    "WiFi" -> JobInfo.NETWORK_TYPE_METERED
                    else -> JobInfo.NETWORK_TYPE_UNMETERED
                },
            )
        }
        runCatching { js.schedule(job.build()) }
    }

    internal fun from(params: JobParameters): String? = params.extras.getString(KEY_FROM)
}

class NetWatchJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val from = NetWatch.from(params)
            val type = Refresher.refresh(applicationContext, force = false)
            // Matched, yet the type looks the same (e.g. the VPN reports another transport than we pick): wait a bit.
            NetWatch.watch(applicationContext, type, delayMs = if (type == from) 60_000 else 0)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

/** Re-arms the triggers after reboot and app updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Triggers.install(ctx)
        runBlocking { LiveService.applySaved(ctx) } // a quick DataStore read
        Triggers.refreshNow(ctx)
    }
}
