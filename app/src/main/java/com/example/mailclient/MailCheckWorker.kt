package com.example.mailclient

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

class MailCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = MailSyncRepository(applicationContext)
        val accounts = CredentialStore.loadAccounts(applicationContext)
        var hasTransientFailure = false

        accounts.forEach { account ->
            Log.i("MailCheckWorker", "[MailCheckWorker] Processing account start: ${account.email}")
            try {
                kotlinx.coroutines.withTimeout(30000L) {
                    val preset = ImapConnector.presets.firstOrNull {
                        it.label.equals(account.presetLabel, ignoreCase = true) ||
                        account.presetLabel.startsWith(it.label, ignoreCase = true) ||
                        it.label.startsWith(account.presetLabel, ignoreCase = true)
                    } ?: ImapConnector.presets.firstOrNull { it.label.startsWith("Свой сервер") } ?: ImapConnector.presets[0]

                    val host = if (account.host.isNotBlank()) account.host else preset.host
                    val port = if (account.port > 0) account.port else preset.port

                    val unreadResult = ImapConnector.fetchUnreadInboxMessages(
                        host = host,
                        port = port,
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
                        return@withTimeout
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
                            host = host,
                            port = port,
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
                        return@withTimeout
                    }

                    val authoritativeLastUid = repository.getAuthoritativeLastNotifiedUid(account.email, "INBOX")

                    val newlyReceivedResult = if (!isUidValidityChanged) {
                        ImapConnector.fetchNewInboxMail(
                            host = host,
                            port = port,
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
                        return@withTimeout
                    }

                    val newlyReceivedPair = newlyReceivedResult?.getOrNull()
                    val newlyReceivedMessages = recoveryScanResult?.getOrNull()?.unseenMessages ?: newlyReceivedPair?.first
                    val newlyReceivedCursor = newlyReceivedPair?.second ?: 0L
                    val highestUid = maxOf(
                        recoveryScanResult?.getOrNull()?.unseenMessages?.maxOfOrNull { it.uid } ?: 0L,
                        newlyReceivedMessages?.maxOfOrNull { it.uid } ?: 0L,
                        newlyReceivedCursor
                    )

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
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e("MailCheckWorker", "Timeout (30s) checking account ${account.email}", e)
                hasTransientFailure = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("MailCheckWorker", "Error checking account ${account.email}", e)
                if (!isAuthError(e)) {
                    hasTransientFailure = true
                }
            } finally {
                Log.i("MailCheckWorker", "[MailCheckWorker] Processing account finish: ${account.email}")
            }
        }

        if (accounts.isNotEmpty()) {
            scheduleNextFastSync(applicationContext)
        }

        return if (hasTransientFailure) Result.retry() else Result.success()
    }

    private fun scheduleNextFastSync(context: Context) {
        try {
            val nextWork = androidx.work.OneTimeWorkRequestBuilder<MailCheckWorker>()
                .setInitialDelay(1, java.util.concurrent.TimeUnit.MINUTES)
                .build()
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                FAST_SYNC_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                nextWork
            )
        } catch (e: Exception) {
            Log.w("MailCheckWorker", "Failed to schedule fast sync work", e)
        }
    }

    private fun isAuthError(e: Throwable): Boolean {
        if (e is javax.mail.AuthenticationFailedException) return true
        val msg = e.message?.uppercase() ?: ""
        return msg.contains("AUTHENTICATIONFAILED") || msg.contains("INVALID CREDENTIALS") || msg.contains("AUTH_FAILED")
    }

    companion object {
        const val FAST_SYNC_WORK_NAME = "fast_mail_check_worker"
    }
}
