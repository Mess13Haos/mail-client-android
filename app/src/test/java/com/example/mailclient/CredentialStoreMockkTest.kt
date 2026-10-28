package com.example.mailclient

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CredentialStoreMockkTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var dummyContext: Context

    class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, String?>()

        override fun getString(key: String, defValue: String?): String? = map[key] ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = false
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class FakeEditor(private val target: MutableMap<String, String?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) target.clear()
                pending.forEach { (k, v) ->
                    if (v == null) target.remove(k) else target[k] = v
                }
            }
        }
    }

    @BeforeEach
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        dummyContext = TestContext()

        CredentialStore.prefsFactory = { fakePrefs }
    }

    @AfterEach
    fun tearDown() {
        CredentialStore.prefsFactory = null
    }

    @Test
    fun `loadAccounts succeeds when stored data is valid`() {
        fakePrefs.map["accounts_json"] = JSONArray().apply {
            put(JSONObject().apply {
                put("presetLabel", "Work")
                put("email", "user@example.com")
                put("password", "super-secret")
                put("lastNotifiedUid", 77L)
            })
        }.toString()

        val accounts = CredentialStore.loadAccounts(dummyContext)

        assertEquals(1, accounts.size)
        assertEquals("Work", accounts.first().presetLabel)
        assertEquals("user@example.com", accounts.first().email)
        assertEquals("super-secret", accounts.first().password)
        assertEquals(77L, accounts.first().lastNotifiedUid)
    }

    @Test
    fun `loadAccounts returns empty list for missing data and throws for malformed JSON`() {
        fakePrefs.map["accounts_json"] = null
        assertTrue(CredentialStore.loadAccounts(dummyContext).isEmpty())

        fakePrefs.map["accounts_json"] = "{not valid json"
        assertThrows(org.json.JSONException::class.java) {
            CredentialStore.loadAccounts(dummyContext)
        }
    }

    @Test
    fun `addOrUpdateAccount persists a new account and activates it`() {
        fakePrefs.map["accounts_json"] = JSONArray().toString()
        fakePrefs.map["active_email"] = ""

        CredentialStore.addOrUpdateAccount(dummyContext, "Primary", "user@example.com", "p@ssw0rd")

        assertEquals("user@example.com", fakePrefs.map["active_email"])
        assertNotNull(fakePrefs.map["accounts_json"])
        assertTrue(fakePrefs.map["accounts_json"]!!.contains("user@example.com"))
        assertTrue(fakePrefs.map["accounts_json"]!!.contains("Primary"))
    }

    @Test
    fun `removeAccount clears active email when the currently active account is removed`() {
        fakePrefs.map["accounts_json"] = JSONArray().apply {
            put(JSONObject().apply {
                put("presetLabel", "Old")
                put("email", "old@example.com")
                put("password", "old-pass")
            })
            put(JSONObject().apply {
                put("presetLabel", "New")
                put("email", "new@example.com")
                put("password", "new-pass")
            })
        }.toString()
        fakePrefs.map["active_email"] = "old@example.com"

        CredentialStore.removeAccount(dummyContext, "old@example.com")

        assertEquals("new@example.com", fakePrefs.map["active_email"])
        assertTrue(fakePrefs.map["accounts_json"]!!.contains("new@example.com"))
        assertTrue(!fakePrefs.map["accounts_json"]!!.contains("old@example.com"))
    }

    @Test
    fun `getActiveAccount returns null for blank or missing account entries`() {
        fakePrefs.map["active_email"] = ""
        fakePrefs.map["accounts_json"] = JSONArray().toString()
        assertNull(CredentialStore.getActiveAccount(dummyContext))

        fakePrefs.map["active_email"] = "missing@example.com"
        fakePrefs.map["accounts_json"] = JSONArray().toString()
        assertNull(CredentialStore.getActiveAccount(dummyContext))
    }

    @Test
    fun `decodeMimeWords handles valid encoded values without static Base64 mocking`() {
        assertEquals("Hello", ImapConnector.decodeMimeWords("=?UTF-8?B?SGVsbG8=?="))
        assertEquals("Hello World", ImapConnector.decodeMimeWords("=?UTF-8?Q?Hello_World?="))
        assertEquals("plain text", ImapConnector.decodeMimeWords("plain text"))
    }
}
