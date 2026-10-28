package com.example.mailclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImapConnectorJunit5Test {

    @Test
    fun `displayNameFor maps common folders to user-friendly labels`() {
        assertEquals("Входящие", ImapConnector.displayNameFor("INBOX"))
        assertEquals("Subfolder", ImapConnector.displayNameFor("INBOX/Subfolder"))
        assertEquals("Спам", ImapConnector.displayNameFor("[Gmail]/Spam"))
        assertEquals("Корзина", ImapConnector.displayNameFor("[Google Mail]/Trash"))
        assertEquals("Важное", ImapConnector.displayNameFor("Important"))
        assertEquals("Отправленные", ImapConnector.displayNameFor("Sent Mail"))
    }

    @Test
    fun `displayNameFor handles edge cases and blank values`() {
        assertEquals("", ImapConnector.displayNameFor(""))
        assertEquals("", ImapConnector.displayNameFor("  ").trim())
        assertEquals("Входящие", ImapConnector.displayNameFor("Inbox"))
        assertEquals("Custom Folder", ImapConnector.displayNameFor("Custom Folder"))
    }

    @Test
    fun `extractEmailAddress handles angle bracket, plain and empty input`() {
        assertEquals("user@example.com", ImapConnector.extractEmailAddress("John Doe <user@example.com>"))
        assertEquals("sales@mailer.io", ImapConnector.extractEmailAddress("sales@mailer.io"))
        assertEquals("", ImapConnector.extractEmailAddress("   "))
        assertEquals("", ImapConnector.extractEmailAddress(""))
    }

    @Test
    fun `isSpamFolder and isTrashFolder detect spam and trash folders`() {
        assertTrue(ImapConnector.isSpamFolder("[Gmail]/Spam"))
        assertTrue(ImapConnector.isSpamFolder("junk"))
        assertFalse(ImapConnector.isSpamFolder("inbox"))
        assertFalse(ImapConnector.isSpamFolder(""))

        assertTrue(ImapConnector.isTrashFolder("Корзина"))
        assertTrue(ImapConnector.isTrashFolder("[Google Mail]/Trash"))
        assertFalse(ImapConnector.isTrashFolder("Sent"))
        assertFalse(ImapConnector.isTrashFolder("  "))
    }

    @Test
    fun `isSpamFolder and isTrashFolder handle nested and dotted folder names`() {
        assertTrue(ImapConnector.isSpamFolder("INBOX/Spam"))
        assertTrue(ImapConnector.isSpamFolder("INBOX.Spam"))
        assertTrue(ImapConnector.isSpamFolder("Parent\\Junk"))
        assertTrue(ImapConnector.isTrashFolder("INBOX/Trash"))
        assertTrue(ImapConnector.isTrashFolder("INBOX.Trash"))
        assertTrue(ImapConnector.isTrashFolder("Parent\\Deleted Items"))
        assertFalse(ImapConnector.isSpamFolder("NotSpam"))
        assertFalse(ImapConnector.isTrashFolder("NotTrash"))
    }
}
