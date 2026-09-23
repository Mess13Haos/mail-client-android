package com.example.mailclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ID = "new_mail_channel"
    const val ACTION_MARK_READ = "com.example.mailclient.ACTION_MARK_READ"
    const val ACTION_SPAM = "com.example.mailclient.ACTION_SPAM"
    const val ACTION_DELETE = "com.example.mailclient.ACTION_DELETE"

    const val EXTRA_EMAIL = "extra_email"
    const val EXTRA_PRESET_LABEL = "extra_preset_label"
    const val EXTRA_UID = "extra_uid"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Новые письма",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun showNewMailNotification(
        context: Context,
        notificationId: Int,
        accountEmail: String,
        presetLabel: String,
        uid: Long,
        from: String,
        subject: String
    ) {
        ensureChannel(context)

        fun actionIntent(action: String): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_EMAIL, accountEmail)
                putExtra(EXTRA_PRESET_LABEL, presetLabel)
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                (accountEmail + action + uid).hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, notificationId, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(from)
            .setContentText(subject)
            .setSubText(accountEmail)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Прочитано", actionIntent(ACTION_MARK_READ))
            .addAction(0, "Спам", actionIntent(ACTION_SPAM))
            .addAction(0, "Удалить", actionIntent(ACTION_DELETE))
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}