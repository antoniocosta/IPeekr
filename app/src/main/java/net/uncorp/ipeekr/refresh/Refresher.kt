package net.uncorp.ipeekr.refresh

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import net.uncorp.ipeekr.data.ExternalLookup
import net.uncorp.ipeekr.data.LocalCollector
import net.uncorp.ipeekr.data.SnapshotStore
import net.uncorp.ipeekr.widget.IPeekrWidget

/** One refresh: local info first (instant), then the external lookup (network). */
object Refresher {
    private const val TAG = "IPeekr"
    private const val EXT_MAX_AGE_MS = 30 * 60_000L

    /** Returns the connection type it found (null = offline). */
    suspend fun refresh(ctx: Context, force: Boolean): String? {
        val collector = LocalCollector(ctx)
        val local = collector.collect()
        val prev = SnapshotStore.current(ctx)
        val prevExt = prev?.ext?.takeIf { it.networkKey == local.networkKey }
        val fresh = prevExt != null && System.currentTimeMillis() - prevExt.fetchedAt < EXT_MAX_AGE_MS

        // Show local data right away, keeping the old external info if it's for the same network.
        // Nothing changed (the usual case for the periodic job): skip the write and the widget redraw.
        val shown = local.copy(ext = prevExt)
        if (shown.copy(updatedAt = 0) != prev?.copy(updatedAt = 0)) {
            SnapshotStore.save(ctx, shown)
            IPeekrWidget().updateAll(ctx)
        }
        Log.d(TAG, "local: $local")

        if (local.type == null || (fresh && !force)) return local.type
        val ext = ExternalLookup.fetch(collector.active()?.network, local.networkKey)
        Log.d(TAG, "external: $ext")
        if (ext != null) {
            SnapshotStore.save(ctx, local.copy(ext = ext))
            IPeekrWidget().updateAll(ctx)
        }
        return local.type
    }
}
