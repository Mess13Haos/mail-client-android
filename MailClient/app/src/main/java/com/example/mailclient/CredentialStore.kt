package com.example.mailclient

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object CredentialStore {
    private const val PREFS_NAME = "secure_mail_prefs"
    private const val KEY_PRESET_LABEL = "preset_label"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"

    data class SavedCredentials(val presetLabel: String, val email: String, val password: String)

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

    fun save(context: Context, presetLabel: String, email: String, password: String) {
        getPrefs(context).edit()
            .putString(KEY_PRESET_LABEL, presetLabel)
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun load(context: Context): SavedCredentials? {
        val prefs = getPrefs(context)
        val presetLabel = prefs.getString(KEY_PRESET_LABEL, null) ?: return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return SavedCredentials(presetLabel, email, password)
    }

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}