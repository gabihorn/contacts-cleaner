package app.contactscleaner.me

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.contactscleaner.data.ChangeLog
import java.util.concurrent.TimeUnit

/** Adds one batch from the ME queue a day, until the queue is empty or paused. */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val store = MeStore(context)
        val data = store.load()
        if (data == null || data.queue.isEmpty()) {
            cancel(context)
            return Result.success()
        }
        if (data.settings.paused) return Result.success()
        if (context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }
        MeImporter(context.contentResolver, store, ChangeLog(context)).importNext()
        return Result.success()
    }

    companion object {
        private const val NAME = "me-import"

        /** Starts (or keeps) the daily schedule. The first batch runs about a day from now. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ImportWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
