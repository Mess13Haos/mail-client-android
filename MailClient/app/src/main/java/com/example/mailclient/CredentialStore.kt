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

    data class SavedAccount(val presetLabel: String, val email: String, val password: String, val lastNotifiedUid: Long = 0L)

    private fun getPrefs(context: Context) = run {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun loadAccounts(context: Context): List<SavedAccount> {
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

    private fun saveAccounts(context: Context, accounts: List<SavedAccount>) {
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

    fun addOrUpdateAccount(context: Context, presetLabel: String, email: String, password: String) {
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

    fun removeAccount(context: Context, email: String) {
        val accounts = loadAccounts(context).filterNot { it.email.equals(email, ignoreCase = true) }
        saveAccounts(context, accounts)
        if (getActiveEmail(context).equals(email, ignoreCase = true)) {
            setActiveEmail(context, accounts.firstOrNull()?.email ?: "")
        }
    }

    fun setActiveEmail(context: Context, email: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_EMAIL, email).apply()
    }

    fun getActiveEmail(context: Context): String {
        return getPrefs(context).getString(KEY_ACTIVE_EMAIL, "") ?: ""
    }

    fun getActiveAccount(context: Context): SavedAccount? {
        val activeEmail = getActiveEmail(context)
        if (activeEmail.isBlank()) return null
        return loadAccounts(context).firstOrNull { it.email.equals(activeEmail, ignoreCase = true) }
    }

    fun updateLastNotifiedUid(context: Context, email: String, uid: Long) {
        val accounts = loadAccounts(context).map { acc ->
            if (acc.email.equals(email, ignoreCase = true)) acc.copy(lastNotifiedUid = uid) else acc
        }
        saveAccounts(context, accounts)
    }
}