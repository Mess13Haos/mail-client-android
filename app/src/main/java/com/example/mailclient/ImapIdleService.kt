package com.example.mailclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ImapIdleService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val activeMonitors = ConcurrentHashMap<String, ActiveMonitor>()

    private data class ActiveMonitor(
        val signature: String,
        val job: Job
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundNotification()
        }
        serviceScope.launch {
            while (isActive) {
                updateMonitoringJobs(CredentialStore.loadAccounts(this@ImapIdleService))
                delay(30000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop monitoring action received from notification")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val accounts = CredentialStore.loadAccounts(this)
        if (accounts.isEmpty()) {
            Log.i(TAG, "No active accounts remaining. Stopping ImapIdleService.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        updateMonitoringJobs(accounts)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        activeMonitors.values.forEach { it.job.cancel() }
        activeMonitors.clear()
        serviceScope.cancel()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "Foreground Service execution timed out by Android system (fgsType=$fgsType)")
        try {
            val oneTimeRequest = androidx.work.OneTimeWorkRequestBuilder<MailCheckWorker>().build()
            androidx.work.WorkManager.getInstance(applicationContext).enqueue(oneTimeRequest)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = getString(R.string.monitoring_service_channel_name)
            val channel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                channelName,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val title = getString(R.string.monitoring_service_title)
        val text = getString(R.string.monitoring_service_text)

        val stopIntent = Intent(this, ImapIdleService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setShowWhen(false)
            .setSound(null)
            .addAction(0, "Остановить мониторинг", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun updateMonitoringJobs(accounts: List<CredentialStore.SavedAccount>) {
        val currentSignatures = accounts.associate { acc ->
            acc.email.lowercase() to "${acc.presetLabel}|${acc.email}|${acc.password}"
        }

        activeMonitors.entries.removeIf { (email, monitor) ->
            val newSig = currentSignatures[email]
            if (newSig == null || newSig != monitor.signature) {
                monitor.job.cancel()
                true
            } else false
        }

        accounts.forEach { account ->
            val key = account.email.lowercase()
            val sig = "${account.presetLabel}|${account.email}|${account.password}"
            if (activeMonitors[key]?.job?.isActive != true) {
                launchAccountMonitor(account, sig)
            }
        }
    }

    private fun launchAccountMonitor(account: CredentialStore.SavedAccount, signature: String) {
        val key = account.email.lowercase()
        val job = serviceScope.launch {
            var attempt = 1
            while (isActive) {
                val isAccountActive = CredentialStore.loadAccounts(this@ImapIdleService)
                    .any { it.email.equals(account.email, ignoreCase = true) }
                if (!isAccountActive) break

                val preset = ImapConnector.presets.firstOrNull {
                    it.label.equals(account.presetLabel, ignoreCase = true) ||
                    account.presetLabel.startsWith(it.label, ignoreCase = true) ||
                    it.label.startsWith(account.presetLabel, ignoreCase = true)
                } ?: ImapConnector.presets.firstOrNull { it.label.startsWith("Свой сервер") } ?: ImapConnector.presets[0]

                var isAuthFailed = false
                try {
                    val unreadResult = ImapConnector.fetchUnreadInboxMessages(
                        host = preset.host, port = preset.port,
                        email = account.email, password = account.password
                    )
                    if (unreadResult.isSuccess) {
                        val unreadData = unreadResult.getOrThrow()
                        val repository = MailSyncRepository(this@ImapIdleService)
                        val savedValidity = repository.dbHelper.getSavedUidValidity(repository.dbHelper.readableDatabase, account.email.trim().lowercase(), "INBOX")
                        val isUidValidityChanged = savedValidity > 0L && savedValidity != unreadData.uidValidity

                        if (isUidValidityChanged) {
                            val scanResult = ImapConnector.fetchFolderMessagesFullScan(
                                host = preset.host, port = preset.port,
                                email = account.email, password = account.password,
                                folderName = "INBOX"
                            )
                            if (scanResult.isSuccess) {
                                val scannedMails = scanResult.getOrThrow().unseenMessages
                                reconcileAndNotify(account.email, account.presetLabel, unreadData.unseenMessages, scannedMails, unreadData.uidValidity)
                            } else {
                                Log.e(TAG, "Recovery scan failed for ${account.email}, skipping state commit", scanResult.exceptionOrNull())
                            }
                        } else {
                            val authoritativeLastUid = repository.getAuthoritativeLastNotifiedUid(account.email, "INBOX")
                            val newlyReceivedResult = ImapConnector.fetchNewInboxMail(
                                host = preset.host, port = preset.port,
                                email = account.email, password = account.password,
                                sinceUid = authoritativeLastUid
                            )
                            val newlyReceivedPair = newlyReceivedResult.getOrNull()
                            val newlyReceivedMessages = newlyReceivedPair?.first
                            val newlyReceivedCursor = newlyReceivedPair?.second ?: 0L
                            reconcileAndNotify(account.email, account.presetLabel, unreadData.unseenMessages, newlyReceivedMessages, unreadData.uidValidity, newlyReceivedCursor)
                        }
                    } else {
                        val e = unreadResult.exceptionOrNull()
                        if (e != null && isAuthError(e)) {
                            Log.w(TAG, "Authentication failed for ${account.email}")
                            isAuthFailed = true
                        }
                    }
                } catch (e: Exception) {
                    if (isAuthError(e)) {
                        Log.w(TAG, "Authentication failed for ${account.email}")
                        isAuthFailed = true
                    } else {
                        Log.e(TAG, "Reconciliation error for ${account.email}", e)
                    }
                }

                if (isAuthFailed) {
                    delay(3600000L)
                    continue
                }

                if (!isActive) break

                try {
                    val idleWorked = ImapConnector.listenIdleForAccount(
                        host = preset.host,
                        port = preset.port,
                        email = account.email,
                        password = account.password,
                        onNewMailArrived = { validity ->
                            val repository = MailSyncRepository(this@ImapIdleService)
                            val unreadRes = ImapConnector.fetchUnreadInboxMessages(
                                host = preset.host, port = preset.port,
                                email = account.email, password = account.password
                            )
                            unreadRes.onSuccess { unreadData ->
                                val authoritativeLastUid = repository.getAuthoritativeLastNotifiedUid(account.email, "INBOX")
                                val newlyReceivedRes = ImapConnector.fetchNewInboxMail(
                                    host = preset.host, port = preset.port,
                                    email = account.email, password = account.password,
                                    sinceUid = authoritativeLastUid
                                )
                                val newlyReceivedPair = newlyReceivedRes.getOrNull()
                                val newlyReceivedMessages = newlyReceivedPair?.first
                                val newlyReceivedCursor = newlyReceivedPair?.second ?: 0L
                                reconcileAndNotify(account.email, account.presetLabel, unreadData.unseenMessages, newlyReceivedMessages, unreadData.uidValidity, newlyReceivedCursor)
                            }
                        }
                    )
                    if (idleWorked) {
                        attempt = 1
                    }
                } catch (e: Exception) {
                    if (isAuthError(e)) {
                        Log.w(TAG, "Authentication failed during IDLE for ${account.email}")
                        delay(3600000L)
                        continue
                    } else {
                        Log.e(TAG, "IDLE session error for ${account.email}", e)
                    }
                }

                if (!isActive) break

                val delayMs = calculateBackoffMillis(attempt)
                attempt++
                delay(delayMs)
            }
        }
        activeMonitors[key] = ActiveMonitor(signature, job)
    }

    private suspend fun reconcileAndNotify(
        accountEmail: String,
        presetLabel: String,
        unreadMessages: List<ImapConnector.NewMailInfo>?,
        newlyReceivedMessages: List<ImapConnector.NewMailInfo>?,
        uidValidity: Long?,
        newHighestUid: Long = 0L
    ) {
        val repository = MailSyncRepository(this)
        val highestUid = maxOf(
            (unreadMessages.orEmpty() + newlyReceivedMessages.orEmpty()).maxOfOrNull { it.uid } ?: 0L,
            newHighestUid
        )
        val syncResult = repository.processSyncAtomic(
            accountEmail = accountEmail,
            presetLabel = presetLabel,
            folderName = "INBOX",
            serverUidValidity = uidValidity ?: 0L,
            unreadMessages = unreadMessages,
            newlyReceivedMessages = newlyReceivedMessages,
            newHighestUid = highestUid
        )

        repository.dismissNotifications(this, accountEmail, syncResult.uidsToDismiss)
        repository.dispatchPendingNotifications(this, accountEmail, presetLabel)
    }

    private fun isAuthError(e: Throwable): Boolean {
        if (e is javax.mail.AuthenticationFailedException) return true
        val msg = e.message?.uppercase() ?: ""
        return msg.contains("AUTHENTICATIONFAILED") || msg.contains("INVALID CREDENTIALS") || msg.contains("AUTH_FAILED")
    }

    private fun calculateBackoffMillis(attempt: Int): Long {
        val initialDelayMs = 5000L
        val maxDelayMs = 300000L
        val calculated = (initialDelayMs * Math.pow(2.0, (attempt - 1).toDouble())).toLong()
        val clamped = minOf(calculated, maxDelayMs)
        val jitter = if (clamped > 1000L) (0..(clamped / 4).toInt()).random() else 0
        return clamped + jitter
    }

    companion object {
        private const val TAG = "ImapIdleService"
        const val CHANNEL_SERVICE_ID = "imap_monitoring_channel"
        const val ACTION_STOP = "com.example.mailclient.ACTION_STOP_MONITORING"
        private const val SERVICE_NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ImapIdleService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Foreground service start not allowed from background context", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ImapIdleService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping foreground service", e)
            }
        }
    }
}
