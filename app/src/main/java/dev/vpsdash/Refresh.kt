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
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        Refresh.fetchAndPublish(applicationContext)
        return androidx.work.ListenableWorker.Result.success()
    }
}

object Refresh {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Fetches stats over SSH, caches them, and (optionally) pushes to all widgets.
     *  Returns an error message, or null on success. */
    suspend fun fetchAndPublish(ctx: Context, pushWidgets: Boolean = true): String? {
        val cfg = Prefs.config(ctx) ?: return "Not set up yet"
        val error = try {
            Prefs.saveResult(ctx, SshClient.fetch(ctx, cfg), null)
            null
        } catch (t: Throwable) {
            SshClient.friendly(t).also { Prefs.saveResult(ctx, null, it) }
        }
        if (pushWidgets) StatsWidget.pushAll(ctx)
        return error
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
