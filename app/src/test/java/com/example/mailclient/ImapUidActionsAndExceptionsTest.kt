package com.example.mailclient

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import javax.mail.AuthenticationFailedException

class ImapUidActionsAndExceptionsTest {

    class FakeFolderHandle(
        private val messages: MutableList<ImapConnector.MailMessageInfo> = mutableListOf(),
        private val validity: Long = 1000L,
        private val throwException: Exception? = null
    ) : ImapConnector.FolderHandle {

        override fun getUIDValidity(): Long {
            if (throwException != null) throw throwException
            return validity
        }

        override fun getMessageCount(): Int {
            if (throwException != null) throw throwException
            return messages.size
        }

        override fun getMessages(lowIndex: Int, highIndex: Int): List<ImapConnector.MailMessageInfo> {
            if (throwException != null) throw throwException
            return messages
        }

        override fun getMessagesByUID(sinceUid: Long): Pair<List<ImapConnector.MailMessageInfo>, Long> {
            if (throwException != null) throw throwException
            val highest = messages.maxOfOrNull { it.uid } ?: 0L
            val newMsgs = messages.filter { it.uid > sinceUid }
            return Pair(newMsgs, maxOf(highest, sinceUid))
        }

        override fun deleteMessageByUID(uid: Long): Boolean {
            if (throwException != null) throw throwException
            return messages.any { it.uid == uid }
        }

        override fun markAsReadByUID(uid: Long): Boolean {
            if (throwException != null) throw throwException
            return messages.any { it.uid == uid }
        }

        override fun moveMessageByUID(uid: Long, toFolder: String): Boolean {
            if (throwException != null) throw throwException
            return messages.any { it.uid == uid }
        }

        override fun close() {}
    }

    class TestFolderAdapter(
        val messages: MutableList<ImapConnector.MailMessageInfo> = mutableListOf(),
        val validity: Long = 1000L,
        val throwException: Exception? = null,
        val availableFolders: List<String> = listOf("INBOX", "Spam", "Trash")
    ) : ImapConnector.ImapFolderAdapter {

        override fun listFolders(host: String, port: Int, email: String, password: String): List<String> {
            if (throwException != null) throw throwException
            return availableFolders
        }

        override fun openFolder(
            host: String,
            port: Int,
            email: String,
            password: String,
            folderName: String,
            mode: Int
        ): ImapConnector.FolderHandle {
            if (throwException != null) throw throwException
            if (!availableFolders.contains(folderName) && !folderName.equals("INBOX", ignoreCase = true)) {
                throw Exception("Folder $folderName not found")
            }
            return FakeFolderHandle(messages, validity, throwException)
        }
    }

    @BeforeEach
    fun setUp() {
    }

    @AfterEach
    fun tearDown() {
        ImapConnector.folderAdapter = ImapConnector.JavaMailFolderAdapter()
        CredentialStore.prefsFactory = null
    }

    @Test
    fun `deleteMessageByUid succeeds when message exists`() = runBlocking {
        val msg = ImapConnector.MailMessageInfo(10L, "sender@ex.com", "Subj", "<msg10@domain.com>")
        ImapConnector.folderAdapter = TestFolderAdapter(mutableListOf(msg))

        val result = ImapConnector.deleteMessageByUid("host", 993, "user@ex.com", "pass", 10L, "INBOX")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `deleteMessageByUid fails when message does not exist`() = runBlocking {
        ImapConnector.folderAdapter = TestFolderAdapter(mutableListOf())

        val result = ImapConnector.deleteMessageByUid("host", 993, "user@ex.com", "pass", 999L, "INBOX")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Письмо") == true)
    }

    @Test
    fun `deleteMessageByUid preserves original IOException on network error`() = runBlocking {
        val ioException = IOException("Network socket reset during delete")
        ImapConnector.folderAdapter = TestFolderAdapter(throwException = ioException)

        val result = ImapConnector.deleteMessageByUid("host", 993, "user@ex.com", "pass", 10L, "INBOX")

        assertTrue(result.isFailure)
        assertEquals(ioException, result.exceptionOrNull())
    }

    @Test
    fun `deleteMessageByUid preserves original AuthenticationFailedException`() = runBlocking {
        val authException = AuthenticationFailedException("AUTHENTICATIONFAILED Incorrect credentials")
        ImapConnector.folderAdapter = TestFolderAdapter(throwException = authException)

        val result = ImapConnector.deleteMessageByUid("host", 993, "user@ex.com", "badpass", 10L, "INBOX")

        assertTrue(result.isFailure)
        assertEquals(authException, result.exceptionOrNull())
    }

    @Test
    fun `markAsReadByUid succeeds and preserves original exception on failure`() = runBlocking {
        val msg = ImapConnector.MailMessageInfo(20L, "s@ex.com", "Subj", "<id20@domain.com>")
        ImapConnector.folderAdapter = TestFolderAdapter(mutableListOf(msg))

        val successRes = ImapConnector.markAsReadByUid("host", 993, "user@ex.com", "pass", 20L, "INBOX")
        assertTrue(successRes.isSuccess)

        val ioEx = IOException("Connection closed by peer")
        ImapConnector.folderAdapter = TestFolderAdapter(throwException = ioEx)

        val failRes = ImapConnector.markAsReadByUid("host", 993, "user@ex.com", "pass", 20L, "INBOX")
        assertTrue(failRes.isFailure)
        assertEquals(ioEx, failRes.exceptionOrNull())
    }

    @Test
    fun `moveToSpamByUid succeeds when spam folder exists and message is present`() = runBlocking {
        val msg = ImapConnector.MailMessageInfo(30L, "s@ex.com", "Spam Candidate", "<id30@domain.com>")
        ImapConnector.folderAdapter = TestFolderAdapter(mutableListOf(msg), availableFolders = listOf("INBOX", "Spam", "Trash"))

        val result = ImapConnector.moveToSpamByUid("host", 993, "user@ex.com", "pass", 30L, "INBOX")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `moveToSpamByUid fails when spam folder is missing or network error occurs`() = runBlocking {
        val ioEx = IOException("Connection timeout")
        ImapConnector.folderAdapter = TestFolderAdapter(throwException = ioEx)

        val result = ImapConnector.moveToSpamByUid("host", 993, "user@ex.com", "pass", 30L, "INBOX")

        assertTrue(result.isFailure)
        assertEquals(ioEx, result.exceptionOrNull())
    }

    @Test
    fun `fetchNewInboxMail with sinceUid equal to 0 returns all messages and sets cursor to highest UID`() = runBlocking {
        val msg1 = ImapConnector.MailMessageInfo(10L, "s1@ex.com", "Subj1", "<id10@domain.com>")
        val msg2 = ImapConnector.MailMessageInfo(20L, "s2@ex.com", "Subj2", "<id20@domain.com>")
        ImapConnector.folderAdapter = TestFolderAdapter(mutableListOf(msg1, msg2))

        val result = ImapConnector.fetchNewInboxMail("host", 993, "user@ex.com", "pass", 0L)

        assertTrue(result.isSuccess)
        val (messages, cursor) = result.getOrThrow()
        assertEquals(2, messages.size)
        assertEquals(20L, cursor)
    }

    @Test
    fun `restoreFromTrashByUid succeeds and preserves original exception on failure`() = runBlocking {
        val msg = ImapConnector.MailMessageInfo(40L, "s@ex.com", "Subj", "<id40@domain.com>")
        ImapConnector.folderAdapter = TestFolderAdapter(mutableListOf(msg), availableFolders = listOf("INBOX", "Trash"))

        val successRes = ImapConnector.restoreFromTrashByUid("host", 993, "user@ex.com", "pass", 40L, "Trash")
        assertTrue(successRes.isSuccess)

        val authEx = AuthenticationFailedException("Auth failure")
        ImapConnector.folderAdapter = TestFolderAdapter(throwException = authEx)

        val failRes = ImapConnector.restoreFromTrashByUid("host", 993, "user@ex.com", "pass", 40L, "Trash")
        assertTrue(failRes.isFailure)
        assertEquals(authEx, failRes.exceptionOrNull())
    }

    @Test
    fun `moveMessageByUid fails when target folder does not exist or message is missing`() = runBlocking {
        ImapConnector.folderAdapter = TestFolderAdapter(availableFolders = listOf("INBOX"))

        val result = ImapConnector.moveMessageByUid("host", 993, "user@ex.com", "pass", 50L, "INBOX", "NonExistentFolder")

        assertTrue(result.isFailure)
    }

    @Test
    fun `concurrent worker and idle reconcile calls produce idempotent reconciliation state`() = runBlocking {
        val mail1 = ImapConnector.NewMailInfo(100L, "sender@ex.com", "Subject", "<msg100@domain.com>")

        val state = MailNotificationState.Reconciliation(emptyList(), emptySet(), emptySet(), emptySet())

        // Run 5 parallel reconciliation steps
        val jobs = (1..5).map {
            async {
                MailNotificationState.reconcile(
                    previouslyNotifiedUids = state.notifiedUids,
                    seenMessageIds = state.seenMessageIds,
                    unreadMessages = listOf(mail1),
                    newlyReceivedMessages = listOf(mail1)
                )
            }
        }

        val results = jobs.awaitAll()
        // Every parallel run identifies the exact same unique candidate
        results.forEach { run ->
            assertEquals(setOf(100L), run.notifiedUids)
            assertEquals(setOf("msgid:<msg100@domain.com>"), run.seenMessageIds)
        }
    }
}
