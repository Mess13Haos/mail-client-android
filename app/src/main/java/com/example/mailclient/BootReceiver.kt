package com.example.mailclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val workRequest = PeriodicWorkRequestBuilder<MailCheckWorker>(
                MailNotificationState.CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "mail_check_worker",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            // On Android 15 (API 35+), dataSync foreground service cannot be started directly from BOOT_COMPLETED.
            // WorkManager handles post-boot background checks, and ImapIdleService starts when app is opened.
        }
    }
}
