package com.example.mailclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap

/**
 * Фоновый мониторинг новых писем через IMAP IDLE.
 *
 * Вместо только периодического опроса (WorkManager раз в 15 мин, который на Samsung API 25
 * в режиме Doze/App Standby сильно откладывается) сервис держит IDLE-соединение к каждому
 * ящику и при приходе письма запускает немедленную проверку, после которой уведомление
 * формируется за считанные секунды.
 *
 * Сервис запускается на переднем плане, но его карточка намеренно максимально невидимая
 * (IMPORTANCE_MIN, VISIBILITY_SECRET, без звука/вибрации) — пользователю она не предназначена
 * (см. задачу J в DEV_PROMPT.md).
 */
class ImapIdleService : Service() {

    private val accountJobs = ConcurrentHashMap<String, Job>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var monitoringStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            val action = intent?.action
            if (ACTION_STOP == action) {
                stopMonitoring()
                START_NOT_STICKY
            } else {
                startMonitoring()
                START_NOT_STICKY
            }
        } catch (e: Exception) {
            Log.e("ImapIdleService", "Foreground service start failed", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureIdleForegroundChannel()
            val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.idle_foreground_content_text))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            val accounts = CredentialStore.loadAccounts(applicationContext)
            if (accounts.isEmpty()) {
                stopMonitoring()
                return@launch
            }
            accounts.forEach { account ->
                val job = serviceScope.launch {
                    while (isActive) {
                        try {
                            ImapConnector.listenIdleForAccount(
                                host = resolveHost(account),
                                port = resolvePort(account),
                                email = account.email,
                                password = account.password,
                                onNewMailArrived = { uidValidity ->
                                    Log.i(
                                        TAG,
                                        "IDLE: new mail signal for ${account.email} (uidValidity=$uidValidity)"
                                    )
                                    triggerImmediateCheck(applicationContext)
                                }
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) break
                            Log.e(TAG, "IDLE listener failed for ${account.email}", e)
                        }
                        if (!isActive) break
                        delay(IDLE_RESTART_DELAY_MS)
                    }
                    accountJobs.remove(account.email)
                }
                accountJobs[account.email] = job
            }
        }
    }

    private fun stopMonitoring() {
        accountJobs.values.forEach { it.cancel() }
        accountJobs.clear()
        monitoringStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun resolveHost(account: CredentialStore.SavedAccount): String {
        val preset = ImapConnector.presets.firstOrNull {
            it.label.equals(account.presetLabel, ignoreCase = true) ||
                account.presetLabel.startsWith(it.label, ignoreCase = true) ||
                it.label.startsWith(account.presetLabel, ignoreCase = true)
        } ?: ImapConnector.presets.firstOrNull { it.label.startsWith("Свой сервер", ignoreCase = true) }
            ?: ImapConnector.presets.firstOrNull()
        return if (account.host.isNotBlank()) account.host else preset?.host ?: ""
    }

    private fun resolvePort(account: CredentialStore.SavedAccount): Int {
        val preset = ImapConnector.presets.firstOrNull {
            it.label.equals(account.presetLabel, ignoreCase = true) ||
                account.presetLabel.startsWith(it.label, ignoreCase = true) ||
                it.label.startsWith(account.presetLabel, ignoreCase = true)
        } ?: ImapConnector.presets.firstOrNull { it.label.startsWith("Свой сервер", ignoreCase = true) }
            ?: ImapConnector.presets.firstOrNull()
        return if (account.port > 0) account.port else preset?.port ?: 993
    }

    private fun triggerImmediateCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<MailCheckWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IDLE_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun ensureIdleForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(FOREGROUND_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            getString(R.string.idle_foreground_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.idle_foreground_channel_description)
            lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ImapIdleService"
        internal const val ACTION_STOP = "com.example.mailclient.ACTION_STOP_IDLE"

        private const val FOREGROUND_CHANNEL_ID = "mail_idle_foreground"
        private const val FOREGROUND_NOTIFICATION_ID = 1000
        private const val IDLE_RESTART_DELAY_MS = 5_000L
        internal const val IDLE_SYNC_WORK_NAME = "idle_mail_check_worker"

        fun start(context: Context) {
            val intent = Intent(context, ImapIdleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("mail_check_worker")
            context.startService(Intent(context, ImapIdleService::class.java).apply { action = ACTION_STOP })
        }
    }
}
