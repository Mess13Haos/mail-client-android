package com.example.mailclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailNotificationStateTest {

    @Test
    fun `notification target preserves email uid and folder`() {
        assertEquals(
            MailNotificationTarget("person@example.com", 42L, "INBOX"),
            MailNotificationState.target("person@example.com", 42L, "INBOX")
        )
    }

    @Test
    fun `notification target defaults missing folder and rejects invalid target`() {
        assertEquals(
            MailNotificationTarget("person@example.com", 42L, "INBOX"),
            MailNotificationState.target("person@example.com", 42L, null)
        )
        assertNull(MailNotificationState.target("", 42L, "INBOX"))
        assertNull(MailNotificationState.target("person@example.com", -1L, "INBOX"))
    }

    @Test
    fun `read notifications are removed while unread notifications remain tracked`() {
        val notified = setOf(10L, 11L, 12L)
        val unread = setOf(11L, 12L, 13L)

        assertEquals(setOf(10L), MailNotificationState.uidsReadSinceLastCheck(notified, unread))
        assertEquals(setOf(11L, 12L), MailNotificationState.stillUnread(notified, unread))
    }

    @Test
    fun `notification id is stable for the same account and uid`() {
        val expected = ("person@example.com|inbox|0|42").hashCode()
        assertEquals(expected, MailNotificationState.notificationId("person@example.com", 42L))
    }

    @Test
    fun `newly received messages are notified even when already read`() {
        val alreadyRead = ImapConnector.NewMailInfo(43L, "sender@example.com", "Read elsewhere", "<id43@domain.com>")
        val unread = ImapConnector.NewMailInfo(44L, "sender@example.com", "Still unread", "<id44@domain.com>")

        val reconciliation = MailNotificationState.reconcile(
            previouslyNotifiedUids = emptySet(),
            seenMessageIds = emptySet(),
            unreadMessages = listOf(unread),
            newlyReceivedMessages = listOf(alreadyRead, unread)
        )

        assertEquals(setOf(43L, 44L), reconciliation.notificationsToShow.map { it.uid }.toSet())
        assertEquals(setOf(43L, 44L), reconciliation.notifiedUids)
        assertTrue(reconciliation.uidsToDismiss.isEmpty())
    }

    @Test
    fun `failed unread lookup does not discard received messages or existing notification state`() {
        val newlyReceived = ImapConnector.NewMailInfo(45L, "sender@example.com", "Received", "<id45@domain.com>")

        val reconciliation = MailNotificationState.reconcile(
            previouslyNotifiedUids = setOf(40L),
            seenMessageIds = setOf("<id40@domain.com>"),
            unreadMessages = null,
            newlyReceivedMessages = listOf(newlyReceived)
        )

        assertEquals(listOf(newlyReceived), reconciliation.notificationsToShow)
        assertTrue(reconciliation.uidsToDismiss.isEmpty())
        assertEquals(setOf(40L, 45L), reconciliation.notifiedUids)
    }

    @Test
    fun `notification state and ids are isolated by account`() {
        val mail = ImapConnector.NewMailInfo(42L, "sender@example.com", "Same UID", "<id42@domain.com>")
        val firstAccount = MailNotificationState.reconcile(emptySet(), emptySet(), listOf(mail), emptyList())
        val secondAccount = MailNotificationState.reconcile(emptySet(), emptySet(), listOf(mail), emptyList())

        assertEquals(setOf(42L), firstAccount.notifiedUids)
        assertEquals(setOf(42L), secondAccount.notifiedUids)
        assertTrue(
            MailNotificationState.notificationId("first@example.com", 42L) !=
                MailNotificationState.notificationId("second@example.com", 42L)
        )
    }

    @Test
    fun `read notifications are dismissed without re-notifying old mail`() {
        val unread = ImapConnector.NewMailInfo(12L, "sender@example.com", "Still unread", "<id12@domain.com>")

        val reconciliation = MailNotificationState.reconcile(
            previouslyNotifiedUids = setOf(10L, 11L, 12L),
            seenMessageIds = setOf("<id10@domain.com>", "<id11@domain.com>", "<id12@domain.com>"),
            unreadMessages = listOf(unread),
            newlyReceivedMessages = emptyList()
        )

        assertEquals(setOf(10L, 11L), reconciliation.uidsToDismiss)
        assertTrue(reconciliation.notificationsToShow.isEmpty())
        assertEquals(setOf(12L), reconciliation.notifiedUids)
    }

    @Test
    fun `polling uses WorkManager minimum periodic interval`() {
        assertTrue(MailNotificationState.CHECK_INTERVAL_MINUTES >= 15L)
        assertEquals(15L, MailNotificationState.CHECK_INTERVAL_MINUTES)
    }

    @Test
    fun `reconciliation is idempotent for duplicate triggers`() {
        val mail = ImapConnector.NewMailInfo(100L, "sender@example.com", "Subject", "<id100@domain.com>")
        val firstRun = MailNotificationState.reconcile(emptySet(), emptySet(), listOf(mail), listOf(mail))

        assertEquals(1, firstRun.notificationsToShow.size)

        val secondRun = MailNotificationState.reconcile(
            previouslyNotifiedUids = firstRun.notifiedUids,
            seenMessageIds = firstRun.seenMessageIds,
            unreadMessages = listOf(mail),
            newlyReceivedMessages = listOf(mail)
        )
        assertTrue(secondRun.notificationsToShow.isEmpty())
        assertTrue(secondRun.uidsToDismiss.isEmpty())
        assertEquals(firstRun.notifiedUids, secondRun.notifiedUids)
        assertEquals(firstRun.seenMessageIds, secondRun.seenMessageIds)
    }

    @Test
    fun `uidvalidity change scenario preserves seen message ids and prevents old email notifications`() {
        val oldMail10 = ImapConnector.NewMailInfo(10L, "sender@example.com", "Old Msg 1", "<msg10@domain.com>")
        val oldMail20 = ImapConnector.NewMailInfo(20L, "sender@example.com", "Old Msg 2", "<msg20@domain.com>")
        val firstRun = MailNotificationState.reconcile(
            previouslyNotifiedUids = emptySet(),
            seenMessageIds = emptySet(),
            unreadMessages = listOf(oldMail10, oldMail20),
            newlyReceivedMessages = null
        )

        assertEquals(setOf(10L, 20L), firstRun.notifiedUids)
        assertEquals(setOf("msgid:<msg10@domain.com>", "msgid:<msg20@domain.com>"), firstRun.seenMessageIds)

        val newMail10 = ImapConnector.NewMailInfo(10L, "sender@example.com", "Old Msg 1", "<msg10@domain.com>")
        val newMail20 = ImapConnector.NewMailInfo(20L, "sender@example.com", "Old Msg 2", "<msg20@domain.com>")
        val newMail30 = ImapConnector.NewMailInfo(30L, "sender@example.com", "Truly New Msg 3", "<msg30@domain.com>")

        val afterUidValidityChange = MailNotificationState.reconcile(
            previouslyNotifiedUids = emptySet(),
            seenMessageIds = firstRun.seenMessageIds,
            unreadMessages = listOf(newMail10, newMail20, newMail30),
            newlyReceivedMessages = null
        )

        assertEquals(listOf(30L), afterUidValidityChange.notificationsToShow.map { it.uid })
        assertEquals(setOf(30L), afterUidValidityChange.notifiedUids)
        assertTrue(afterUidValidityChange.seenMessageIds.contains("msgid:<msg30@domain.com>"))
    }

    @Test
    fun `missing message-id falls back to stable synthetic identifier`() {
        val mailWithoutMessageId = ImapConnector.NewMailInfo(50L, "sender@example.com", "Subject", "")
        val stableId = mailWithoutMessageId.stableId()

        assertTrue(stableId.isNotBlank())
        assertTrue(stableId.contains("sender@example.com"))
        assertTrue(stableId.contains("subject"))
    }
}
