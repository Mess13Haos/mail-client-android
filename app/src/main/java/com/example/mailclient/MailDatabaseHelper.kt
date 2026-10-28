package com.example.mailclient

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MailDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    data class PendingNotificationEvent(
        val eventKey: String,
        val accountEmail: String,
        val presetLabel: String,
        val uid: Long,
        val sender: String,
        val subject: String,
        val messageIdentity: String,
        val createdAt: Long = System.currentTimeMillis(),
        val dispatchStatus: Int = 0
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE seen_messages (
                account_email TEXT NOT NULL,
                message_identity TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (account_email, message_identity)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE pending_notifications (
                event_key TEXT PRIMARY KEY,
                account_email TEXT NOT NULL,
                preset_label TEXT NOT NULL,
                uid INTEGER NOT NULL,
                sender TEXT NOT NULL,
                subject TEXT NOT NULL,
                message_identity TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                dispatch_status INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE folder_state (
                account_email TEXT NOT NULL,
                folder_name TEXT NOT NULL,
                uid_validity INTEGER NOT NULL,
                last_notified_uid INTEGER NOT NULL,
                PRIMARY KEY (account_email, folder_name)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE notified_uids (
                account_email TEXT NOT NULL,
                uid INTEGER NOT NULL,
                PRIMARY KEY (account_email, uid)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            if (!hasColumn(db, "pending_notifications", "dispatch_status")) {
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN dispatch_status INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private fun hasColumn(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        val cursor = db.rawQuery("PRAGMA table_info($tableName)", null)
        cursor.use {
            while (it.moveToNext()) {
                val nameIndex = it.getColumnIndex("name")
                if (nameIndex != -1 && it.getString(nameIndex) == columnName) {
                    return true
                }
            }
        }
        return false
    }

    fun isMessageSeen(db: SQLiteDatabase, email: String, messageIdentity: String): Boolean {
        val cursor = db.query(
            "seen_messages",
            arrayOf("message_identity"),
            "account_email = ? AND message_identity = ?",
            arrayOf(email.lowercase(), messageIdentity),
            null, null, null
        )
        return cursor.use { it.count > 0 }
    }

    fun markMessageSeen(db: SQLiteDatabase, email: String, messageIdentity: String) {
        val cv = ContentValues().apply {
            put("account_email", email.lowercase())
            put("message_identity", messageIdentity)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("seen_messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getPendingNotifications(db: SQLiteDatabase, email: String): List<PendingNotificationEvent> {
        val cursor = db.query(
            "pending_notifications",
            arrayOf("event_key", "account_email", "preset_label", "uid", "sender", "subject", "message_identity", "created_at", "dispatch_status"),
            "account_email = ?",
            arrayOf(email.lowercase()),
            null, null, "created_at ASC"
        )
        val list = mutableListOf<PendingNotificationEvent>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    PendingNotificationEvent(
                        eventKey = it.getString(0),
                        accountEmail = it.getString(1),
                        presetLabel = it.getString(2),
                        uid = it.getLong(3),
                        sender = it.getString(4),
                        subject = it.getString(5),
                        messageIdentity = it.getString(6),
                        createdAt = it.getLong(7),
                        dispatchStatus = it.getInt(8)
                    )
                )
            }
        }
        return list
    }

    fun getPendingNotificationsByStatus(db: SQLiteDatabase, email: String, status: Int): List<PendingNotificationEvent> {
        val cursor = db.query(
            "pending_notifications",
            arrayOf("event_key", "account_email", "preset_label", "uid", "sender", "subject", "message_identity", "created_at", "dispatch_status"),
            "account_email = ? AND dispatch_status = ?",
            arrayOf(email.lowercase(), status.toString()),
            null, null, "created_at ASC"
        )
        val list = mutableListOf<PendingNotificationEvent>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    PendingNotificationEvent(
                        eventKey = it.getString(0),
                        accountEmail = it.getString(1),
                        presetLabel = it.getString(2),
                        uid = it.getLong(3),
                        sender = it.getString(4),
                        subject = it.getString(5),
                        messageIdentity = it.getString(6),
                        createdAt = it.getLong(7),
                        dispatchStatus = it.getInt(8)
                    )
                )
            }
        }
        return list
    }

    fun reservePendingNotifications(db: SQLiteDatabase, email: String, timeoutMs: Long = 300000L): List<PendingNotificationEvent> {
        val emailKey = email.trim().lowercase()
        val now = System.currentTimeMillis()
        val staleThreshold = now - timeoutMs

        val cvReset = ContentValues().apply { put("dispatch_status", 0) }
        db.update(
            "pending_notifications",
            cvReset,
            "account_email = ? AND dispatch_status = 1 AND created_at < ?",
            arrayOf(emailKey, staleThreshold.toString())
        )

        val pendingList = getPendingNotificationsByStatus(db, emailKey, status = 0)
        if (pendingList.isEmpty()) return emptyList()

        val reservedList = mutableListOf<PendingNotificationEvent>()
        db.beginTransaction()
        try {
            pendingList.forEach { event ->
                val cv = ContentValues().apply { put("dispatch_status", 1) }
                val rows = db.update(
                    "pending_notifications",
                    cv,
                    "event_key = ? AND dispatch_status = 0",
                    arrayOf(event.eventKey)
                )
                if (rows > 0) {
                    reservedList.add(event.copy(dispatchStatus = 1))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return reservedList
    }

    fun unreservePendingNotification(db: SQLiteDatabase, eventKey: String) {
        val cv = ContentValues().apply { put("dispatch_status", 0) }
        db.update("pending_notifications", cv, "event_key = ?", arrayOf(eventKey))
    }

    fun addPendingNotification(db: SQLiteDatabase, event: PendingNotificationEvent) {
        val cv = ContentValues().apply {
            put("event_key", event.eventKey)
            put("account_email", event.accountEmail.lowercase())
            put("preset_label", event.presetLabel)
            put("uid", event.uid)
            put("sender", event.sender)
            put("subject", event.subject)
            put("message_identity", event.messageIdentity)
            put("created_at", event.createdAt)
            put("dispatch_status", event.dispatchStatus)
        }
        db.insertWithOnConflict("pending_notifications", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removePendingNotification(db: SQLiteDatabase, eventKey: String) {
        db.delete("pending_notifications", "event_key = ?", arrayOf(eventKey))
    }

    fun getSavedUidValidity(db: SQLiteDatabase, email: String, folderName: String): Long {
        val cursor = db.query(
            "folder_state",
            arrayOf("uid_validity"),
            "account_email = ? AND folder_name = ?",
            arrayOf(email.lowercase(), folderName),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    fun saveUidValidity(db: SQLiteDatabase, email: String, folderName: String, uidValidity: Long) {
        val currentLastUid = getLastNotifiedUid(db, email, folderName)
        val cv = ContentValues().apply {
            put("account_email", email.lowercase())
            put("folder_name", folderName)
            put("uid_validity", uidValidity)
            put("last_notified_uid", currentLastUid)
        }
        db.insertWithOnConflict("folder_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getLastNotifiedUid(db: SQLiteDatabase, email: String, folderName: String): Long {
        val cursor = db.query(
            "folder_state",
            arrayOf("last_notified_uid"),
            "account_email = ? AND folder_name = ?",
            arrayOf(email.lowercase(), folderName),
            null, null, null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    fun saveLastNotifiedUid(db: SQLiteDatabase, email: String, folderName: String, lastNotifiedUid: Long) {
        val currentValidity = getSavedUidValidity(db, email, folderName)
        val cv = ContentValues().apply {
            put("account_email", email.lowercase())
            put("folder_name", folderName)
            put("uid_validity", currentValidity)
            put("last_notified_uid", lastNotifiedUid)
        }
        db.insertWithOnConflict("folder_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getNotifiedUids(db: SQLiteDatabase, email: String): Set<Long> {
        val cursor = db.query(
            "notified_uids",
            arrayOf("uid"),
            "account_email = ?",
            arrayOf(email.lowercase()),
            null, null, null
        )
        val result = mutableSetOf<Long>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(it.getLong(0))
            }
        }
        return result
    }

    fun addNotifiedUid(db: SQLiteDatabase, email: String, uid: Long) {
        val cv = ContentValues().apply {
            put("account_email", email.lowercase())
            put("uid", uid)
        }
        db.insertWithOnConflict("notified_uids", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeNotifiedUid(db: SQLiteDatabase, email: String, uid: Long) {
        db.delete("notified_uids", "account_email = ? AND uid = ?", arrayOf(email.lowercase(), uid.toString()))
    }

    fun clearNotifiedUidsForAccount(db: SQLiteDatabase, email: String) {
        db.delete("notified_uids", "account_email = ?", arrayOf(email.lowercase()))
    }

    companion object {
        private const val DATABASE_NAME = "mail_sync_repository.db"
        private const val DATABASE_VERSION = 2
    }
}
