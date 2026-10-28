package com.example.mailclient

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

object CredentialStore {
    private const val PREFS_NAME = "secure_mail_prefs"
    private const val KEY_ACCOUNTS = "accounts_json"
    private const val KEY_ACTIVE_EMAIL = "active_email"

    private val storeLock = Any()

    data class SavedAccount(val presetLabel: String, val email: String, val password: String, val lastNotifiedUid: Long = 0L)

    data class PendingNotification(
        val email: String,
        val uid: Long,
        val from: String,
        val subject: String,
        val messageId: String
    )

    @set:org.jetbrains.annotations.TestOnly
    var prefsFactory: ((Context) -> android.content.SharedPreferences)? = null
        set(value) {
            field = value
            cachedPrefs = null
        }

    @Volatile
    private var cachedPrefs: android.content.SharedPreferences? = null

    private fun getPrefs(context: Context): android.content.SharedPreferences {
        val custom = prefsFactory
        if (custom != null) {
            return custom(context)
        }
        val existing = cachedPrefs
        if (existing != null) {
            return existing
        }
        synchronized(storeLock) {
            val doubleCheck = cachedPrefs
            if (doubleCheck != null) {
                return doubleCheck
            }
            val prefs = try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKeyAlias,
                    context.applicationContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Throwable) {
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            cachedPrefs = prefs
            return prefs
        }
    }

    fun loadAccounts(context: Context): List<SavedAccount> {
        synchronized(storeLock) {
            val prefs = getPrefs(context)
            val json = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
            val array = JSONArray(json)
            val result = mutableListOf<SavedAccount>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    SavedAccount(
                        presetLabel = obj.getString("presetLabel"),
                        email = obj.getString("email"),
                        password = obj.getString("password"),
                        lastNotifiedUid = obj.optLong("lastNotifiedUid", 0L)
                    )
                )
            }
            return result
        }
    }

    private fun saveAccounts(context: Context, accounts: List<SavedAccount>) {
        synchronized(storeLock) {
            val array = JSONArray()
            accounts.forEach { acc ->
                val obj = JSONObject()
                obj.put("presetLabel", acc.presetLabel)
                obj.put("email", acc.email)
                obj.put("password", acc.password)
                obj.put("lastNotifiedUid", acc.lastNotifiedUid)
                array.put(obj)
            }
            getPrefs(context).edit().putString(KEY_ACCOUNTS, array.toString()).apply()
        }
    }

    fun addOrUpdateAccount(context: Context, presetLabel: String, email: String, password: String) {
        synchronized(storeLock) {
            val accounts = loadAccounts(context).toMutableList()
            val existingIndex = accounts.indexOfFirst { it.email.equals(email, ignoreCase = true) }
            val newAccount = SavedAccount(presetLabel, email, password)
            if (existingIndex >= 0) {
                accounts[existingIndex] = newAccount
            } else {
                accounts.add(newAccount)
            }
            saveAccounts(context, accounts)
            setActiveEmail(context, email)
        }
        try { ImapIdleService.start(context) } catch (_: Throwable) {}
    }

    fun removeAccount(context: Context, email: String) {
        synchronized(storeLock) {
            val accounts = loadAccounts(context).filterNot { it.email.equals(email, ignoreCase = true) }
            saveAccounts(context, accounts)
            if (getActiveEmail(context).equals(email, ignoreCase = true)) {
                setActiveEmail(context, accounts.firstOrNull()?.email ?: "")
            }
        }
        if (loadAccounts(context).isEmpty()) {
            try { ImapIdleService.stop(context) } catch (_: Throwable) {}
        } else {
            try { ImapIdleService.start(context) } catch (_: Throwable) {}
        }
    }

    fun setActiveEmail(context: Context, email: String) {
        synchronized(storeLock) {
            getPrefs(context).edit().putString(KEY_ACTIVE_EMAIL, email).apply()
        }
    }

    fun getActiveEmail(context: Context): String {
        synchronized(storeLock) {
            return getPrefs(context).getString(KEY_ACTIVE_EMAIL, "") ?: ""
        }
    }

    fun getActiveAccount(context: Context): SavedAccount? {
        val activeEmail = getActiveEmail(context)
        if (activeEmail.isBlank()) return null
        return loadAccounts(context).firstOrNull { it.email.equals(activeEmail, ignoreCase = true) }
    }

    fun updateLastNotifiedUid(context: Context, email: String, uid: Long) {
        synchronized(storeLock) {
            val accounts = loadAccounts(context).map { acc ->
                if (acc.email.equals(email, ignoreCase = true)) acc.copy(lastNotifiedUid = uid) else acc
            }
            saveAccounts(context, accounts)
        }
    }

    fun getNotifiedUids(context: Context, email: String): Set<Long> {
        synchronized(storeLock) {
            val prefs = getPrefs(context)
            val json = prefs.getString("notified_uids_${email.lowercase()}", null) ?: return emptySet()
            return try {
                val array = JSONArray(json)
                val result = mutableSetOf<Long>()
                for (i in 0 until array.length()) {
                    result.add(array.getLong(i))
                }
                result
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    fun setNotifiedUids(context: Context, email: String, uids: Set<Long>) {
        synchronized(storeLock) {
            val array = JSONArray()
            uids.forEach { array.put(it) }
            getPrefs(context).edit().putString("notified_uids_${email.lowercase()}", array.toString()).apply()
        }
    }

    fun getSeenMessageIds(context: Context, email: String): Set<String> {
        synchronized(storeLock) {
            val prefs = getPrefs(context)
            val json = prefs.getString("seen_msg_ids_${email.lowercase()}", null) ?: return emptySet()
            return try {
                val array = JSONArray(json)
                val result = mutableSetOf<String>()
                for (i in 0 until array.length()) {
                    result.add(array.getString(i))
                }
                result
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    fun setSeenMessageIds(context: Context, email: String, ids: Set<String>) {
        synchronized(storeLock) {
            val array = JSONArray()
            ids.forEach { array.put(it) }
            getPrefs(context).edit().putString("seen_msg_ids_${email.lowercase()}", array.toString()).apply()
        }
    }

    fun getPendingNotifications(context: Context, email: String): List<PendingNotification> {
        synchronized(storeLock) {
            val prefs = getPrefs(context)
            val json = prefs.getString("pending_notifs_${email.lowercase()}", null) ?: return emptyList()
            return try {
                val array = JSONArray(json)
                val result = mutableListOf<PendingNotification>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    result.add(
                        PendingNotification(
                            email = obj.getString("email"),
                            uid = obj.getLong("uid"),
                            from = obj.getString("from"),
                            subject = obj.getString("subject"),
                            messageId = obj.optString("messageId", "")
                        )
                    )
                }
                result
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun savePendingNotifications(context: Context, email: String, pending: List<PendingNotification>) {
        synchronized(storeLock) {
            val array = JSONArray()
            pending.forEach { p ->
                val obj = JSONObject().apply {
                    put("email", p.email)
                    put("uid", p.uid)
                    put("from", p.from)
                    put("subject", p.subject)
                    put("messageId", p.messageId)
                }
                array.put(obj)
            }
            getPrefs(context).edit().putString("pending_notifs_${email.lowercase()}", array.toString()).apply()
        }
    }

    fun checkAndUpdateUidValidity(context: Context, email: String, folderName: String = "INBOX", newValidity: Long): Boolean {
        if (newValidity <= 0L) return false
        synchronized(storeLock) {
            val prefs = getPrefs(context)
            val key = "uid_validity_${email.lowercase()}_$folderName"
            val oldValidity = prefs.getLong(key, 0L)
            if (oldValidity != 0L && oldValidity != newValidity) {
                setNotifiedUids(context, email, emptySet())
                updateLastNotifiedUid(context, email, 0L)
                prefs.edit().putLong(key, newValidity).apply()
                return true
            }
            if (oldValidity == 0L) {
                prefs.edit().putLong(key, newValidity).apply()
            }
            return false
        }
    }

    internal fun reconcileNotificationState(
        context: Context,
        email: String,
        unreadMessages: List<ImapConnector.NewMailInfo>?,
        newlyReceivedMessages: List<ImapConnector.NewMailInfo>?
    ): MailNotificationState.Reconciliation {
        synchronized(storeLock) {
            val notifiedUids = getNotifiedUids(context, email)
            val seenMessageIds = getSeenMessageIds(context, email)

            val reconciliation = MailNotificationState.reconcile(
                previouslyNotifiedUids = notifiedUids,
                seenMessageIds = seenMessageIds,
                unreadMessages = unreadMessages,
                newlyReceivedMessages = newlyReceivedMessages
            )

            setNotifiedUids(context, email, reconciliation.notifiedUids)
            setSeenMessageIds(context, email, reconciliation.seenMessageIds)

            val currentPending = getPendingNotifications(context, email).toMutableList()
            reconciliation.notificationsToShow.forEach { mail ->
                if (currentPending.none { it.uid == mail.uid }) {
                    currentPending.add(
                        PendingNotification(
                            email = email,
                            uid = mail.uid,
                            from = mail.from,
                            subject = mail.subject,
                            messageId = mail.messageId
                        )
                    )
                }
            }
            savePendingNotifications(context, email, currentPending)

            return reconciliation
        }
    }

    fun processPendingNotifications(context: Context, email: String, presetLabel: String) {
        synchronized(storeLock) {
            val pending = getPendingNotifications(context, email)
            if (pending.isEmpty()) return

            val remaining = mutableListOf<PendingNotification>()
            pending.forEach { p ->
                val notificationId = MailNotificationState.notificationId(p.email, p.uid)
                val success = NotificationHelper.showNewMailNotification(
                    context = context,
                    notificationId = notificationId,
                    accountEmail = p.email,
                    presetLabel = presetLabel,
                    uid = p.uid,
                    from = p.from,
                    subject = p.subject
                )
                if (!success) {
                    remaining.add(p)
                }
            }
            savePendingNotifications(context, email, remaining)
        }
    }
}
