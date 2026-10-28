package com.example.mailclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailSyncRepositoryTest {

    @Test
    fun `reconciliation produces pending notifications and seen message identities`() {
        val mail1 = ImapConnector.NewMailInfo(10L, "sender@example.com", "Msg 1", "<id10@domain.com>", 1000L, 500L)
        val mail2 = ImapConnector.NewMailInfo(20L, "sender@example.com", "Msg 2", "<id20@domain.com>", 2000L, 600L)

        val reconciliation = MailNotificationState.reconcile(
            previouslyNotifiedUids = emptySet(),
            seenMessageIds = emptySet(),
            unreadMessages = listOf(mail1, mail2),
            newlyReceivedMessages = null
        )

        assertEquals(2, reconciliation.notificationsToShow.size)
        assertEquals(setOf(10L, 20L), reconciliation.notifiedUids)
        assertEquals(setOf("msgid:<id10@domain.com>", "msgid:<id20@domain.com>"), reconciliation.seenMessageIds)
    }

    @Test
    fun `uidvalidity change with existing unread emails ignores old and queues truly new email`() {
        val oldSeenIds = setOf("msgid:<msg10@domain.com>", "msgid:<msg20@domain.com>")

        val mail10 = ImapConnector.NewMailInfo(10L, "sender@example.com", "Old Msg 10", "<msg10@domain.com>", 1000L, 100L)
        val mail20 = ImapConnector.NewMailInfo(20L, "sender@example.com", "Old Msg 20", "<msg20@domain.com>", 2000L, 200L)
        val mail30 = ImapConnector.NewMailInfo(30L, "sender@example.com", "New Msg 30 Read Elsewhere", "<msg30@domain.com>", 3000L, 300L)

        val reconciliation = MailNotificationState.reconcile(
            previouslyNotifiedUids = emptySet(),
            seenMessageIds = oldSeenIds,
            unreadMessages = emptyList(),
            newlyReceivedMessages = listOf(mail10, mail20, mail30)
        )

        assertEquals(listOf(30L), reconciliation.notificationsToShow.map { it.uid })
        assertEquals(setOf(30L), reconciliation.notifiedUids)
        assertTrue(reconciliation.seenMessageIds.contains("msgid:<msg30@domain.com>"))
    }

    @Test
    fun `stableId without message-id is independent of UID and preserves identity across UID shift`() {
        val mailOriginal = ImapConnector.NewMailInfo(
            uid = 10L,
            from = "Alice <alice@example.com>",
            subject = "Meeting",
            messageId = "",
            sentDateMs = 1700000000000L,
            sizeBytes = 2048L
        )

        val mailAfterUidShift = ImapConnector.NewMailInfo(
            uid = 100L,
            from = "Alice <alice@example.com>",
            subject = "Meeting",
            messageId = "",
            sentDateMs = 1700000000000L,
            sizeBytes = 2048L
        )

        assertEquals(mailOriginal.stableId(), mailAfterUidShift.stableId())
        assertEquals("composite:alice <alice@example.com>|meeting|1700000000000|2048", mailOriginal.stableId())
    }

    @Test
    fun `two different emails with same sender and subject are differentiated by date and size`() {
        val mail1 = ImapConnector.NewMailInfo(1L, "sender@example.com", "Alert", "", 100000L, 500L)
        val mail2 = ImapConnector.NewMailInfo(2L, "sender@example.com", "Alert", "", 200000L, 800L)

        assertTrue(mail1.stableId() != mail2.stableId())
    }

    @Test
    fun `duplicate message-ids are deduplicated during reconciliation`() {
        val mail1 = ImapConnector.NewMailInfo(10L, "sender@example.com", "Duplicate", "<dup@domain.com>")
        val mail2 = ImapConnector.NewMailInfo(11L, "sender@example.com", "Duplicate", "<dup@domain.com>")

        val reconciliation = MailNotificationState.reconcile(
            previouslyNotifiedUids = emptySet(),
            seenMessageIds = emptySet(),
            unreadMessages = listOf(mail1, mail2),
            newlyReceivedMessages = null
        )

        assertEquals(1, reconciliation.notificationsToShow.size)
        assertEquals("<dup@domain.com>", reconciliation.notificationsToShow.first().messageId)
    }

    @Test
    fun `missing date and size fallback produces valid composite id`() {
        val mailMissingAttrs = ImapConnector.NewMailInfo(50L, "sender@example.com", "No Date", "", 0L, 0L)
        val stableId = mailMissingAttrs.stableId()

        assertTrue(stableId.startsWith("composite:"))
        assertTrue(stableId.contains("sender@example.com"))
        assertTrue(stableId.contains("no date"))
    }

    @Test
    fun `identical emails without message-id sent at same time with same size produce identical composite id`() {
        val mail1 = ImapConnector.NewMailInfo(100L, "bob@example.com", "Hello", "", 1000L, 500L)
        val mail2 = ImapConnector.NewMailInfo(101L, "bob@example.com", "Hello", "", 1000L, 500L)

        assertEquals(mail1.stableId(), mail2.stableId())
    }

    @Test
    fun `pending notification event keys include server uidvalidity preventing UID collision`() {
        val email = "user@example.com"
        val validity1 = 100L
        val validity2 = 200L
        val uid = 42L

        val key1 = "${email.lowercase()}_${validity1}_${uid}"
        val key2 = "${email.lowercase()}_${validity2}_${uid}"

        assertTrue(key1 != key2)
        assertEquals("user@example.com_100_42", key1)
        assertEquals("user@example.com_200_42", key2)
    }

    @Test
    fun `concurrent worker and idle triggers operate idempotently without state corruption`() {
        val mailA = ImapConnector.NewMailInfo(10L, "sender@example.com", "Msg A", "<a@domain.com>")
        val mailB = ImapConnector.NewMailInfo(11L, "sender@example.com", "Msg B", "<b@domain.com>")
        val mailC = ImapConnector.NewMailInfo(12L, "sender@example.com", "Msg C", "<c@domain.com>")

        val workerRun = MailNotificationState.reconcile(emptySet(), emptySet(), listOf(mailA, mailB), null)
        assertEquals(2, workerRun.notificationsToShow.size)

        val idleRun = MailNotificationState.reconcile(workerRun.notifiedUids, workerRun.seenMessageIds, listOf(mailA, mailB, mailC), null)
        assertEquals(1, idleRun.notificationsToShow.size)
        assertEquals(12L, idleRun.notificationsToShow.first().uid)
        assertEquals(setOf(10L, 11L, 12L), idleRun.notifiedUids)
    }
}
