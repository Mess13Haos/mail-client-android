package com.example.mailclient

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class ImapIdleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            // Заменяем фоновый сервис с уведомлением на WorkManager — никакой иконки в шторке.
            val request = PeriodicWorkRequestBuilder<MailCheckWorker>(
                MailNotificationState.CHECK_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "mail_check_worker",
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
            // Также запускаем немедленную проверку при старте.
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<MailCheckWorker>().build()
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("mail_check_worker")
        }
    }
}
