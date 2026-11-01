package com.example.mailclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val CHANNEL_ID = "new_mail_channel"
    const val ACTION_MARK_READ = "com.example.mailclient.ACTION_MARK_READ"
    const val ACTION_SPAM = "com.example.mailclient.ACTION_SPAM"
    const val ACTION_DELETE = "com.example.mailclient.ACTION_DELETE"

    const val EXTRA_EMAIL = "extra_email"
    const val EXTRA_PRESET_LABEL = "extra_preset_label"
    const val EXTRA_UID = "extra_uid"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    fun isNotificationAllowed(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            if (permission != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = manager.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun dismissNotification(context: Context, accountEmail: String, uid: Long) {
        val repository = MailSyncRepository(context)
        val emailKey = accountEmail.trim().lowercase()
        val savedValidity = repository.dbHelper.getSavedUidValidity(
            repository.dbHelper.readableDatabase,
            emailKey,
            "INBOX"
        )
        val notificationId = MailNotificationState.notificationId(emailKey, uid, "INBOX", savedValidity)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
        val db = repository.dbHelper.writableDatabase
        repository.dbHelper.removeNotifiedUid(db, emailKey, uid)
        val eventKey = "${emailKey}_${savedValidity}_${uid}"
        repository.dbHelper.removePendingNotification(db, eventKey)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val soundUriStr = ThemeStore.getNotificationSoundUri(context)
                val soundUri = if (soundUriStr.isNotBlank()) Uri.parse(soundUriStr) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Новые письма",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(soundUri, audioAttributes)
                    enableVibration(true)
                }
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
    ): Boolean {
        if (!isNotificationAllowed(context)) {
            Log.w("NotificationHelper", "Notifications disabled or permission not granted for $accountEmail")
            return false
        }

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
            putExtra("target_email", accountEmail)
            putExtra("target_uid", uid)
            putExtra("target_folder", "INBOX")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, MailNotificationState.notificationId(accountEmail, uid), openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUriStr = ThemeStore.getNotificationSoundUri(context)
        val soundUri = if (soundUriStr.isNotBlank()) Uri.parse(soundUriStr) else null

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(from)
            .setContentText(subject)
            .setSubText(accountEmail)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Прочитано", actionIntent(ACTION_MARK_READ))
            .addAction(0, "Спам", actionIntent(ACTION_SPAM))
            .addAction(0, "Удалить", actionIntent(ACTION_DELETE))

        if (soundUri != null) {
            builder.setSound(soundUri)
        }

        val notification = builder.build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return try {
            manager.notify(notificationId, notification)
            true
        } catch (e: Exception) {
            Log.e("NotificationHelper", "Failed to dispatch notification for $accountEmail", e)
            false
        }
    }
}
