package com.marcobehler.chattermore.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.marcobehler.chattermore.security.SecureConfigStore
import java.util.concurrent.TimeUnit

object BackgroundPollingScheduler {
    private const val UNIQUE_WORK_NAME = "mattermost-background-message-check"

    fun reconcile(context: Context) {
        if (SecureConfigStore(context).backgroundPollingSettings().enabled) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackgroundPollingWorker>(
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        SecureConfigStore(context).loadBackgroundPollingSession()?.channelId?.let {
            BackgroundPollingWorker.cancelNotification(context, it)
        }
    }
}
