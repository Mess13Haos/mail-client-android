package com.example.mailclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialStoreNotificationCleanupTest {

    private val dummyContext = CredentialStoreMockkTest.TestContext()

    @Test
    fun `removePendingNotification removes only entries for the given uid`() {
        val fakePrefs = CredentialStoreMockkTest.FakeSharedPreferences().apply {
            map["pending_notifs_user@example.com"] = """[{"email":"user@example.com","uid":10,"from":"a@x.com","subject":"S1","messageId":"<m1>"},{"email":"user@example.com","uid":20,"from":"b@x.com","subject":"S2","messageId":"<m2>"}]"""
        }
        CredentialStore.prefsFactory = { fakePrefs }
        try {
            CredentialStore.removePendingNotification(dummyContext, "user@example.com", 10L)
            val remaining = CredentialStore.getPendingNotifications(dummyContext, "user@example.com")
            assertEquals(1, remaining.size)
            assertEquals(20L, remaining.first().uid)
        } finally {
            CredentialStore.prefsFactory = null
        }
    }

    @Test
    fun `removePendingNotification ignores missing email and unknown uid`() {
        val fakePrefs = CredentialStoreMockkTest.FakeSharedPreferences().apply {
            map["pending_notifs_user@example.com"] = """[{"email":"user@example.com","uid":10,"from":"a@x.com","subject":"S1","messageId":"<m1>"}]"""
        }
        CredentialStore.prefsFactory = { fakePrefs }
        try {
            CredentialStore.removePendingNotification(dummyContext, "other@example.com", 99L)
            val remaining = CredentialStore.getPendingNotifications(dummyContext, "user@example.com")
            assertEquals(1, remaining.size)
        } finally {
            CredentialStore.prefsFactory = null
        }
    }

    @Test
    fun `removeNotifiedUid removes only the given uid`() {
        val fakePrefs = CredentialStoreMockkTest.FakeSharedPreferences().apply {
            map["notified_uids_user@example.com"] = "[10,20,30]"
        }
        CredentialStore.prefsFactory = { fakePrefs }
        try {
            CredentialStore.removeNotifiedUid(dummyContext, "user@example.com", 20L)
            val remaining = CredentialStore.getNotifiedUids(dummyContext, "user@example.com")
            assertEquals(setOf(10L, 30L), remaining)
        } finally {
            CredentialStore.prefsFactory = null
        }
    }

    @Test
    fun `removeNotifiedUid ignores missing email and unknown uid`() {
        val fakePrefs = CredentialStoreMockkTest.FakeSharedPreferences().apply {
            map["notified_uids_user@example.com"] = "[10,20,30]"
        }
        CredentialStore.prefsFactory = { fakePrefs }
        try {
            CredentialStore.removeNotifiedUid(dummyContext, "other@example.com", 99L)
            val remaining = CredentialStore.getNotifiedUids(dummyContext, "user@example.com")
            assertEquals(setOf(10L, 20L, 30L), remaining)
        } finally {
            CredentialStore.prefsFactory = null
        }
    }
}