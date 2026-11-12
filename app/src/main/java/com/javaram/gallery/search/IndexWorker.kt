package com.javaram.gallery.search

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.javaram.gallery.data.MediaRepository
import java.util.concurrent.TimeUnit

/** Keeps analysing photos in the background while the phone is charging. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val items = MediaRepository(applicationContext).load(trashed = false)
        if (items.isEmpty()) return Result.success()
        // WorkManager gives us about 10 minutes; stop a little before that.
        Indexer.run(applicationContext, items, deadlineMillis = System.currentTimeMillis() + 8 * 60_000L)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "photo-index", ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
