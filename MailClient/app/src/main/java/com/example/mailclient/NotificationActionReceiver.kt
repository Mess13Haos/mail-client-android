package com.example.mailclient

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val email = intent.getStringExtra(NotificationHelper.EXTRA_EMAIL) ?: return
        val presetLabel = intent.getStringExtra(NotificationHelper.EXTRA_PRESET_LABEL) ?: return
        val uid = intent.getLongExtra(NotificationHelper.EXTRA_UID, -1L)
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, 0)
        if (uid < 0) return

        val account = CredentialStore.loadAccounts(context).firstOrNull { it.email.equals(email, ignoreCase = true) }
            ?: return
        val preset = ImapConnector.presets.firstOrNull { it.label == presetLabel } ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    NotificationHelper.ACTION_MARK_READ -> {
                        ImapConnector.markAsReadByUid(
                            preset.host, preset.port, account.email, account.password, uid
                        )
                    }
                    NotificationHelper.ACTION_SPAM -> {
                        ImapConnector.moveToSpamByUid(
                            preset.host, preset.port, account.email, account.password, uid
                        )
                    }
                    NotificationHelper.ACTION_DELETE -> {
                        ImapConnector.moveToTrashByUid(
                            preset.host, preset.port, account.email, account.password, uid
                        )
                    }
                }
            } finally {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(notificationId)
                pendingResult.finish()
            }
        }
    }
}