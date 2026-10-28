package com.example.mailclient

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FullScanRecoveryAndIntegrityTest {

    class FakeFolderHandle(
        private val messages: List<ImapConnector.MailMessageInfo>,
        private val initialValidity: Long,
        private val finalValidity: Long,
        private val throwOnLowIndex: Int? = null
    ) : ImapConnector.FolderHandle {
        private var validityCallCount = 0

        override fun getUIDValidity(): Long {
            validityCallCount++
            return if (validityCallCount == 1) initialValidity else finalValidity
        }

        override fun getMessageCount(): Int = messages.size

        override fun getMessages(lowIndex: Int, highIndex: Int): List<ImapConnector.MailMessageInfo> {
            if (throwOnLowIndex != null && lowIndex <= throwOnLowIndex) {
                throw RuntimeException("Simulated network batch failure at $lowIndex..$highIndex")
            }
            val low = maxOf(0, lowIndex - 1)
            val high = minOf(messages.size, highIndex)
            if (low >= messages.size || low > high) return emptyList()
            return messages.subList(low, high)
        }

        override fun close() {}
    }

    class TestFolderAdapter(
        private val messages: List<ImapConnector.MailMessageInfo>,
        private val initialValidity: Long = 1000L,
        private val finalValidity: Long = 1000L,
        private val throwOnLowIndex: Int? = null,
        private val openException: Exception? = null
    ) : ImapConnector.ImapFolderAdapter {
        override fun openFolder(
            host: String,
            port: Int,
            email: String,
            password: String,
            folderName: String
        ): ImapConnector.FolderHandle {
            if (openException != null) throw openException
            return FakeFolderHandle(messages, initialValidity, finalValidity, throwOnLowIndex)
        }
    }

    @BeforeEach
    fun setUp() {
    }

    @AfterEach
    fun tearDown() {
        ImapConnector.folderAdapter = ImapConnector.JavaMailFolderAdapter()
    }

    private fun generateMessages(count: Int): List<ImapConnector.MailMessageInfo> {
        return (1..count).map { i ->
            ImapConnector.MailMessageInfo(
                uid = i.toLong(),
                from = "user$i@example.com",
                subject = "Subject $i",
                messageId = "<msg$i@domain.com>",
                sentDateMs = 1000000L + i,
                sizeBytes = 500L + i
            )
        }
    }

    @Test
    fun `fetchFolderMessagesFullScan handles empty folder with 0 messages`() = runBlocking {
        ImapConnector.folderAdapter = TestFolderAdapter(emptyList(), 500L, 500L)

        val result = ImapConnector.fetchFolderMessagesFullScan("host", 993, "user@ex.com", "pass")

        assertTrue(result.isSuccess)
        val unread = result.getOrThrow()
        assertEquals(0, unread.unseenMessages.size)
        assertEquals(500L, unread.uidValidity)
    }

    @Test
    fun `fetchFolderMessagesFullScan correctly scans 50 messages`() = runBlocking {
        val msgs = generateMessages(50)
        ImapConnector.folderAdapter = TestFolderAdapter(msgs, 100L, 100L)

        val result = ImapConnector.fetchFolderMessagesFullScan("host", 993, "user@ex.com", "pass")

        assertTrue(result.isSuccess)
        assertEquals(50, result.getOrThrow().unseenMessages.size)
    }

    @Test
    fun `fetchFolderMessagesFullScan correctly scans 200 messages in 2 batches`() = runBlocking {
        val msgs = generateMessages(200)
        ImapConnector.folderAdapter = TestFolderAdapter(msgs, 100L, 100L)

        val result = ImapConnector.fetchFolderMessagesFullScan("host", 993, "user@ex.com", "pass")

        assertTrue(result.isSuccess)
        assertEquals(200, result.getOrThrow().unseenMessages.size)
    }

    @Test
    fun `fetchFolderMessagesFullScan correctly scans 201 messages in 3 batches`() = runBlocking {
        val msgs = generateMessages(201)
        ImapConnector.folderAdapter = TestFolderAdapter(msgs, 100L, 100L)

        val result = ImapConnector.fetchFolderMessagesFullScan("host", 993, "user@ex.com", "pass")

        assertTrue(result.isSuccess)
        assertEquals(201, result.getOrThrow().unseenMessages.size)
    }

    @Test
    fun `fetchFolderMessagesFullScan correctly scans 500 messages`() = runBlocking {
        val msgs = generateMessages(500)
        ImapConnector.folderAdapter = TestFolderAdapter(msgs, 100L, 100L)

        val result = ImapConnector.fetchFolderMessagesFullScan("host", 993, "user@ex.com", "pass")

        assertTrue(result.isSuccess)
        assertEquals(500, result.getOrThrow().unseenMessages.size)
    }

    @Test
    fun `fetchFolderMessagesFullScan correctly scans 5000 messages`() = runBlocking {
        val msgs = generateMessages(5000)
        ImapConnector.folderAdapter = TestFolderAdapter(msgs, 100L, 100L)

        val result = ImapConnector.fetchFolderMessagesFullScan("host", 993, "user@ex.com", "pass")

        assertTrue(result.isSuccess)
        assertEquals(5000, result.getOrThrow().unseenMessages.size)
    }

    @Test
    fun `fetchFolderMessagesFullScan fails and returns failure when a batch throws exception`() = runBlocking {
        val msgs = generateMessages(250)
        // Throw exception when fetching batch containing message index <= 100
        ImapConnector.folderAdapter = TestFolderAdapter(msgs, 100L, 100L, throwOnLowIndex = 100)

        val result = ImapConnector.fetchFolderMessagesFullScan("host", 993, "user@ex.com", "pass")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Simulated network batch failure") == true)
    }

    @Test
    fun `fetchFolderMessagesFullScan fails when UIDVALIDITY changes during scan`() = runBlocking {
        val msgs = generateMessages(150)
        ImapConnector.folderAdapter = TestFolderAdapter(msgs, initialValidity = 1000L, finalValidity = 2000L)

        val result = ImapConnector.fetchFolderMessagesFullScan("host", 993, "user@ex.com", "pass")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("UIDVALIDITY changed during full folder scan") == true)
    }
}
