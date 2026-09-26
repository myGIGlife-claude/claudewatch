package dev.vpsdash

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.vpsdash.widget.StatsWidget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        Refresh.fetchAndPublish(applicationContext)
        return androidx.work.ListenableWorker.Result.success()
    }
}

object Refresh {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Fetches stats over SSH for one server (or all, in parallel), caches them, and
     *  (optionally) pushes to all widgets. Returns the first error message, or null on success. */
    suspend fun fetchAndPublish(ctx: Context, serverId: String? = null, pushWidgets: Boolean = true): String? {
        val list = Prefs.servers(ctx).filter { serverId == null || it.id == serverId }
        if (list.isEmpty()) return "Not set up yet"
        val errors = coroutineScope { list.map { async { fetchOne(ctx, it) } }.awaitAll() }
        if (pushWidgets) StatsWidget.pushAll(ctx)
        return errors.firstOrNull { it != null }
    }

    private suspend fun fetchOne(ctx: Context, cfg: ServerConfig): String? = try {
        Prefs.saveResult(ctx, cfg.id, SshClient.fetch(ctx, cfg), null)
        null
    } catch (t: Throwable) {
        SshClient.friendly(t).also { Prefs.saveResult(ctx, cfg.id, null, it) }
    }

    /** Background refresh every 15 min (Android's minimum for periodic work). */
    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "vps-refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
        )
    }

    fun now(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "vps-refresh-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net).build(),
        )
    }
}
