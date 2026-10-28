package com.example.mailclient

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object AccountSyncLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun getLock(email: String): Mutex {
        return locks.computeIfAbsent(email.trim().lowercase()) { Mutex() }
    }
}

class MailSyncRepository(context: Context) {

    val dbHelper = MailDatabaseHelper(context.applicationContext)

    fun getAuthoritativeLastNotifiedUid(accountEmail: String, folderName: String = "INBOX"): Long {
        val db = dbHelper.readableDatabase
        return dbHelper.getLastNotifiedUid(db, accountEmail.trim().lowercase(), folderName)
    }

    data class SyncResult(
        val pendingEventsToDispatch: List<MailDatabaseHelper.PendingNotificationEvent>,
        val uidsToDismiss: List<Long>,
        val uidValidityChanged: Boolean
    )

    suspend fun processSyncAtomic(
        accountEmail: String,
        presetLabel: String,
        folderName: String = "INBOX",
        serverUidValidity: Long,
        unreadMessages: List<ImapConnector.NewMailInfo>?,
        newlyReceivedMessages: List<ImapConnector.NewMailInfo>?,
        newHighestUid: Long = 0L
    ): SyncResult {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
                val emailKey = accountEmail.trim().lowercase()

                var uidValidityChanged = false
                if (serverUidValidity > 0L) {
                    val savedValidity = dbHelper.getSavedUidValidity(db, emailKey, folderName)
                    if (savedValidity > 0L && serverUidValidity < savedValidity) {
                        Log.w("MailSyncRepository", "Ignoring stale sync result for $emailKey (serverValidity=$serverUidValidity, savedValidity=$savedValidity)")
                        db.setTransactionSuccessful()
                        return SyncResult(emptyList(), emptyList(), false)
                    }
                    if (savedValidity > 0L && savedValidity != serverUidValidity) {
                        dbHelper.clearNotifiedUidsForAccount(db, emailKey)
                        dbHelper.saveLastNotifiedUid(db, emailKey, folderName, 0L)
                        uidValidityChanged = true
                    }
                    dbHelper.saveUidValidity(db, emailKey, folderName, serverUidValidity)
                }

                val savedHighest = dbHelper.getLastNotifiedUid(db, emailKey, folderName)
                if (newHighestUid > savedHighest) {
                    dbHelper.saveLastNotifiedUid(db, emailKey, folderName, newHighestUid)
                }

                val currentNotifiedUids = dbHelper.getNotifiedUids(db, emailKey)

                val unreadUids = unreadMessages?.mapTo(mutableSetOf()) { it.uid }
                val uidsToDismiss = if (unreadUids != null) {
                    currentNotifiedUids.filter { it !in unreadUids }
                } else emptyList()

                if (uidsToDismiss.isNotEmpty()) {
                    uidsToDismiss.forEach { readUid ->
                        dbHelper.removeNotifiedUid(db, emailKey, readUid)
                    }
                }

                val candidates = (unreadMessages.orEmpty() + newlyReceivedMessages.orEmpty())
                    .distinctBy { it.stableId() }

                candidates.forEach { mail ->
                    val identity = mail.stableId()
                    val isSeen = dbHelper.isMessageSeen(db, emailKey, identity)
                    val isNotified = mail.uid in currentNotifiedUids

                    if (!isSeen && !isNotified) {
                        val eventKey = "${emailKey}_${serverUidValidity}_${mail.uid}"
                        val event = MailDatabaseHelper.PendingNotificationEvent(
                            eventKey = eventKey,
                            accountEmail = accountEmail,
                            presetLabel = presetLabel,
                            uid = mail.uid,
                            sender = mail.from,
                            subject = mail.subject,
                            messageIdentity = identity
                        )
                        dbHelper.addPendingNotification(db, event)
                        dbHelper.markMessageSeen(db, emailKey, identity)
                    }
                }

                db.setTransactionSuccessful()

                val pendingToDispatch = dbHelper.getPendingNotifications(db, emailKey)
                return SyncResult(
                    pendingEventsToDispatch = pendingToDispatch,
                    uidsToDismiss = uidsToDismiss,
                    uidValidityChanged = uidValidityChanged
                )
            } finally {
                db.endTransaction()
            }
    }

    suspend fun dispatchPendingNotifications(context: Context, accountEmail: String, presetLabel: String) {
        val db = dbHelper.writableDatabase
        val reservedEvents = dbHelper.reservePendingNotifications(db, accountEmail)
        if (reservedEvents.isEmpty()) return

        val savedValidity = dbHelper.getSavedUidValidity(db, accountEmail, "INBOX")

        reservedEvents.forEach { event ->
            val notificationId = MailNotificationState.notificationId(
                email = event.accountEmail,
                uid = event.uid,
                folderName = "INBOX",
                uidValidity = savedValidity
            )

            val success = NotificationHelper.showNewMailNotification(
                context = context,
                notificationId = notificationId,
                accountEmail = event.accountEmail,
                presetLabel = presetLabel,
                uid = event.uid,
                from = event.sender,
                subject = event.subject
            )

            if (success) {
                db.beginTransaction()
                try {
                    dbHelper.removePendingNotification(db, event.eventKey)
                    dbHelper.addNotifiedUid(db, event.accountEmail, event.uid)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } else {
                Log.w("MailSyncRepository", "Notification failed or permission denied for ${event.accountEmail} (eventKey=${event.eventKey}). Reverting reservation.")
                dbHelper.unreservePendingNotification(db, event.eventKey)
            }
        }
    }

    fun dismissNotifications(context: Context, accountEmail: String, uidsToDismiss: List<Long>) {
        val db = dbHelper.writableDatabase
        val savedValidity = dbHelper.getSavedUidValidity(db, accountEmail, "INBOX")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        uidsToDismiss.forEach { uid ->
            val notificationId = MailNotificationState.notificationId(accountEmail, uid, "INBOX", savedValidity)
            manager.cancel(notificationId)
            dbHelper.removeNotifiedUid(db, accountEmail, uid)
        }
    }
}
