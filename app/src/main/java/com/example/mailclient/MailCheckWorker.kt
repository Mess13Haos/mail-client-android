package com.example.mailclient

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MailCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accounts = CredentialStore.loadAccounts(applicationContext)
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        accounts.forEach { account ->
            try {
                val preset = ImapConnector.presets.firstOrNull {
                    it.label.equals(account.presetLabel, ignoreCase = true) ||
                    account.presetLabel.startsWith(it.label, ignoreCase = true) ||
                    it.label.startsWith(account.presetLabel, ignoreCase = true)
                } ?: ImapConnector.presets.firstOrNull { it.label.startsWith("Свой сервер") } ?: ImapConnector.presets[0]

                // 1. Check unread messages to auto-dismiss notifications for emails read on a PC or another device
                val unreadResult = ImapConnector.fetchUnreadUids(
                    host = preset.host, port = preset.port,
                    email = account.email, password = account.password
                )
                unreadResult.onSuccess { unreadUids ->
                    val notifiedUids = CredentialStore.getNotifiedUids(applicationContext, account.email)
                    val readUids = notifiedUids.filter { it !in unreadUids }
                    readUids.forEach { readUid ->
                        val notificationId = (account.email + readUid).hashCode()
                        notificationManager.cancel(notificationId)
                    }
                    CredentialStore.setNotifiedUids(applicationContext, account.email, notifiedUids.filter { it in unreadUids }.toSet())
                }

                // 2. Fetch new mail for notifications
                val result = ImapConnector.fetchNewInboxMail(
                    host = preset.host, port = preset.port,
                    email = account.email, password = account.password,
                    sinceUid = account.lastNotifiedUid
                )

                result.onSuccess { (newMessages, newHighestUid) ->
                    if (account.lastNotifiedUid == 0L) {
                        CredentialStore.updateLastNotifiedUid(applicationContext, account.email, newHighestUid)
                    } else {
                        val currentNotified = CredentialStore.getNotifiedUids(applicationContext, account.email).toMutableSet()
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
                            currentNotified.add(mail.uid)
                        }
                        CredentialStore.setNotifiedUids(applicationContext, account.email, currentNotified)
                        if (newHighestUid > account.lastNotifiedUid) {
                            CredentialStore.updateLastNotifiedUid(applicationContext, account.email, newHighestUid)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MailCheckWorker", "Error checking account ${account.email}", e)
            }
        }

        return Result.success()
    }
}
