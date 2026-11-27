package com.example.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.KrasRepository
import com.example.data.model.UserSettings
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker powered by the single centralized [SmartSyncEngine].
 * Runs silently on the user's configured periodic cadence, respects Wi-Fi Only and
 * Battery Saving Sync constraints, and skips every content type that is still fresh.
 */
class SmartSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val engine = SmartSyncEngine.getInstance(applicationContext)
            val repository = engine.repository
            val settings = repository.getUserSettings()
            if (!settings.smartAutoSyncEnabled) {
                return Result.success()
            }
            val activeAccount = repository.getActiveAccount()
            val report = engine.syncAccount(
                account = activeAccount,
                requestedTypes = SyncContentType.catalogAndMetadataTypes,
                triggerReason = SyncTriggerReason.PERIODIC_BACKGROUND,
                settings = settings
            )
            // Keep WorkManager periodic cadence healthy; SmartSyncEngine tracks per-type exponential backoff
            if (report.failedTypes.isNotEmpty() && report.updatedTypes.isEmpty()) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "kras_global_smart_sync_periodic"

        /**
         * Schedules, updates, or cancels the unique periodic Smart Sync background job
         * based on [UserSettings]. Never spawns duplicate jobs.
         */
        fun scheduleOrUpdate(context: Context, settings: UserSettings) {
            try {
                val wm = WorkManager.getInstance(context.applicationContext)
                if (!settings.smartAutoSyncEnabled) {
                    wm.cancelUniqueWork(UNIQUE_WORK_NAME)
                    return
                }

                // Choose periodic wakeup interval from the shortest active interval (min 1h, doubled in Battery Saving)
                val minIntervalHours = listOf(
                    settings.syncIntervalEpgHours,
                    settings.syncIntervalMoviesHours,
                    settings.syncIntervalSeriesHours,
                    settings.syncIntervalLiveHours,
                    settings.syncIntervalCategoriesHours,
                    settings.syncIntervalTmdbHours,
                    settings.syncIntervalAccountHours
                ).minOrNull()?.coerceAtLeast(1) ?: 3

                val effectiveWakeHours = (minIntervalHours * if (settings.batterySavingSyncMode) 2 else 1)
                    .coerceIn(1, 24)
                    .toLong()

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(
                        if (settings.syncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .setRequiresBatteryNotLow(settings.batterySavingSyncMode)
                    .build()

                val request = PeriodicWorkRequestBuilder<SmartSyncWorker>(
                    effectiveWakeHours,
                    TimeUnit.HOURS
                )
                    .setConstraints(constraints)
                    .build()

                wm.enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            } catch (_: Exception) {
                // Safe guard in environments where WorkManager is not initialized (e.g. unit tests)
            }
        }
    }
}
