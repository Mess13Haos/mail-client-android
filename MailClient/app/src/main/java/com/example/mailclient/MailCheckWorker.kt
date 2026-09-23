package com.example.mailclient

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MailCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accounts = CredentialStore.loadAccounts(applicationContext)

        accounts.forEach { account ->
            val preset = ImapConnector.presets.firstOrNull { it.label == account.presetLabel } ?: return@forEach

            val result = ImapConnector.fetchNewInboxMail(
                host = preset.host, port = preset.port,
                email = account.email, password = account.password,
                sinceUid = account.lastNotifiedUid
            )

            result.onSuccess { (newMessages, newHighestUid) ->
                newMessages.forEach { mail ->
                    val notificationId = (account.email + mail.uid).hashCode()
                    NotificationHelper.showNewMailNotification(
                        context = applicationContext,
                        notificationId = notificationId,
                        accountEmail = account.email,
                        presetLabel = account.presetLabel,
                        uid = mail.uid,
                        from = mail.from,
                        subject = mail.subject
                    )
                }
                CredentialStore.updateLastNotifiedUid(applicationContext, account.email, newHighestUid)
            }
        }

        return Result.success()
    }
}