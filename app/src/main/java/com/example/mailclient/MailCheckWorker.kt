package com.example.mailclient

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

class MailCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = MailSyncRepository(applicationContext)
        val accounts = CredentialStore.loadAccounts(applicationContext)
        var hasTransientFailure = false

        accounts.forEach { account ->
            val lock = AccountSyncLocks.getLock(account.email)
            try {
                lock.withLock {
                    val preset = ImapConnector.presets.firstOrNull {
                        it.label.equals(account.presetLabel, ignoreCase = true) ||
                        account.presetLabel.startsWith(it.label, ignoreCase = true) ||
                        it.label.startsWith(account.presetLabel, ignoreCase = true)
                    } ?: ImapConnector.presets.firstOrNull { it.label.startsWith("Свой сервер") } ?: ImapConnector.presets[0]

                    val unreadResult = ImapConnector.fetchUnreadInboxMessages(
                        host = preset.host,
                        port = preset.port,
                        email = account.email,
                        password = account.password
                    )

                    if (unreadResult.isFailure) {
                        val ex = unreadResult.exceptionOrNull()
                        if (ex is CancellationException) throw ex
                        Log.e("MailCheckWorker", "Failed to fetch unread messages for ${account.email}", ex)
                        if (ex == null || !isAuthError(ex)) {
                            hasTransientFailure = true
                        }
                        return@withLock
                    }

                    val unreadData = unreadResult.getOrThrow()

                    val savedValidity = repository.dbHelper.getSavedUidValidity(
                        repository.dbHelper.readableDatabase,
                        account.email.trim().lowercase(),
                        "INBOX"
                    )
                    val isUidValidityChanged = savedValidity > 0L && savedValidity != unreadData.uidValidity

                    val recoveryScanResult = if (isUidValidityChanged) {
                        ImapConnector.fetchFolderMessagesFullScan(
                            host = preset.host,
                            port = preset.port,
                            email = account.email,
                            password = account.password,
                            folderName = "INBOX"
                        )
                    } else null

                    if (isUidValidityChanged && recoveryScanResult?.isFailure == true) {
                        val ex = recoveryScanResult.exceptionOrNull()
                        if (ex is CancellationException) throw ex
                        Log.e("MailCheckWorker", "Recovery scan failed for ${account.email}", ex)
                        hasTransientFailure = true
                        return@withLock
                    }

                    val authoritativeLastUid = repository.getAuthoritativeLastNotifiedUid(account.email, "INBOX")

                    val newlyReceivedResult = if (!isUidValidityChanged) {
                        ImapConnector.fetchNewInboxMail(
                            host = preset.host,
                            port = preset.port,
                            email = account.email,
                            password = account.password,
                            sinceUid = authoritativeLastUid
                        )
                    } else null

                    if (!isUidValidityChanged && newlyReceivedResult != null && newlyReceivedResult.isFailure) {
                        val ex = newlyReceivedResult.exceptionOrNull()
                        if (ex is CancellationException) throw ex
                        Log.e("MailCheckWorker", "Failed to fetch new inbox mail for ${account.email}", ex)
                        if (ex == null || !isAuthError(ex)) {
                            hasTransientFailure = true
                        }
                        return@withLock
                    }

                    val newlyReceivedMessages = recoveryScanResult?.getOrNull()?.unseenMessages ?: newlyReceivedResult?.getOrNull()?.first
                    val highestUid = recoveryScanResult?.getOrNull()?.unseenMessages?.maxOfOrNull { it.uid } ?: newlyReceivedResult?.getOrNull()?.second ?: 0L

                    val syncResult = repository.processSyncAtomic(
                        accountEmail = account.email,
                        presetLabel = account.presetLabel,
                        folderName = "INBOX",
                        serverUidValidity = unreadData.uidValidity,
                        unreadMessages = unreadData.unseenMessages,
                        newlyReceivedMessages = newlyReceivedMessages,
                        newHighestUid = highestUid
                    )

                    repository.dismissNotifications(applicationContext, account.email, syncResult.uidsToDismiss)
                    repository.dispatchPendingNotifications(applicationContext, account.email, account.presetLabel)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("MailCheckWorker", "Error checking account ${account.email}", e)
                if (!isAuthError(e)) {
                    hasTransientFailure = true
                }
            }
        }

        return if (hasTransientFailure) Result.retry() else Result.success()
    }

    private fun isAuthError(e: Throwable): Boolean {
        if (e is javax.mail.AuthenticationFailedException) return true
        val msg = e.message?.uppercase() ?: ""
        return msg.contains("AUTHENTICATIONFAILED") || msg.contains("INVALID CREDENTIALS") || msg.contains("AUTH_FAILED")
    }
}
