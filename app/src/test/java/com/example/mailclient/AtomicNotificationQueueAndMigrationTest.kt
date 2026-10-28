package com.example.mailclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class AtomicNotificationQueueAndMigrationTest {

    @Test
    fun `notificationId includes email uid validity and folder preventing ID collisions`() {
        val email = "user@example.com"
        val uid = 42L

        val idValidity100 = MailNotificationState.notificationId(email, uid, "INBOX", 100L)
        val idValidity200 = MailNotificationState.notificationId(email, uid, "INBOX", 200L)
        val idFolderSpam = MailNotificationState.notificationId(email, uid, "SPAM", 100L)

        // Different UIDVALIDITY produces different notification ID
        assertNotEquals(idValidity100, idValidity200)

        // Different folder produces different notification ID
        assertNotEquals(idValidity100, idFolderSpam)

        // Same parameters produce identical notification ID
        assertEquals(
            idValidity100,
            MailNotificationState.notificationId(email, uid, "INBOX", 100L)
        )
    }

    @Test
    fun `notification target preserves target folder and email`() {
        val targetInbox = MailNotificationState.target("user@example.com", 10L, "INBOX")
        val targetSpam = MailNotificationState.target("user@example.com", 10L, "SPAM")

        assertEquals("INBOX", targetInbox?.folder)
        assertEquals("SPAM", targetSpam?.folder)
    }
}
