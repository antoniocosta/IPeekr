package net.uncorp.ipeekr.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("snapshot")
private val KEY = stringPreferencesKey("snapshot_json")

/** The last snapshot, persisted so the widget survives process death. */
object SnapshotStore {
    fun flow(ctx: Context): Flow<NetSnapshot?> = ctx.applicationContext.store.data.map { p ->
        p[KEY]?.let { runCatching { NetSnapshot.fromJson(it) }.getOrNull() }
    }

    suspend fun current(ctx: Context): NetSnapshot? = flow(ctx).first()

    suspend fun save(ctx: Context, s: NetSnapshot) {
        ctx.applicationContext.store.edit { it[KEY] = s.toJson() }
    }
}
