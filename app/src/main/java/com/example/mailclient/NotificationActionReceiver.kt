package com.example.mailclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val email = intent.getStringExtra(NotificationHelper.EXTRA_EMAIL) ?: return
        val presetLabel = intent.getStringExtra(NotificationHelper.EXTRA_PRESET_LABEL) ?: return
        val uid = intent.getLongExtra(NotificationHelper.EXTRA_UID, -1L)
        if (uid < 0) return

        val account = CredentialStore.loadAccounts(context).firstOrNull { it.email.equals(email, ignoreCase = true) }
            ?: return
        val preset = ImapConnector.presets.firstOrNull {
            it.label.equals(presetLabel, ignoreCase = true) ||
            presetLabel.startsWith(it.label, ignoreCase = true) ||
            it.label.startsWith(presetLabel, ignoreCase = true)
        } ?: ImapConnector.presets.firstOrNull { it.label.startsWith("Свой сервер") } ?: ImapConnector.presets[0]

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = when (intent.action) {
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
                    else -> null
                }
                if (result?.isSuccess == true) {
                    NotificationHelper.dismissNotification(context, account.email, uid)
                } else if (result?.isFailure == true) {
                    val exception = result.exceptionOrNull()
                    val message = exception?.message ?: ""
                    val isNotFound = listOf("не найден", "не найдено", "not found", "message not found")
                        .any { message.lowercase().contains(it) }
                    if (isNotFound) {
                        // Письма уже нет на сервере (удалено/перемещено с другого устройства),
                        // но карточку всё равно убираем из шторки и очищаем локальное состояние.
                        NotificationHelper.dismissNotification(context, account.email, uid)
                    } else {
                        Log.e(
                            "NotificationActionReceiver",
                            "Failed to process ${intent.action} for ${account.email}, UID $uid",
                            exception
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
