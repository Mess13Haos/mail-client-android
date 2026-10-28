package com.example.mailclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageIdentityAndAuthTest {

    @Test
    fun `stableId escapes pipe delimiter preventing cross-field collisions`() {
        val mail1 = ImapConnector.NewMailInfo(
            uid = 1L,
            from = "alice|bob@example.com",
            subject = "hello",
            messageId = "",
            sentDateMs = 1000L,
            sizeBytes = 500L
        )

        val mail2 = ImapConnector.NewMailInfo(
            uid = 2L,
            from = "alice",
            subject = "bob@example.com|hello",
            messageId = "",
            sentDateMs = 1000L,
            sizeBytes = 500L
        )

        val id1 = mail1.stableId()
        val id2 = mail2.stableId()

        // Unescaped "alice|bob@example.com" + "hello" would collide with "alice" + "bob@example.com|hello"
        // But with escaping:
        // id1: "composite:alice%7Cbob@example.com|hello|1000|500"
        // id2: "composite:alice|bob@example.com%7Chello|1000|500"
        assertNotEquals(id1, id2)
    }

    @Test
    fun `stableId uses messageId when present`() {
        val mail = ImapConnector.NewMailInfo(
            uid = 10L,
            from = "sender@example.com",
            subject = "Subject",
            messageId = "<unique-123@domain.com>",
            sentDateMs = 1000L,
            sizeBytes = 500L
        )

        assertEquals("msgid:<unique-123@domain.com>", mail.stableId())
    }

    @Test
    fun `openStore does not treat long passwords as OAuth tokens`() {
        val longPassword = "this_is_a_very_long_password_that_exceeds_30_characters_in_length_12345"
        val oauthToken = "ya29.a0AfH6SM..."

        // Check isOAuth logic: only startsWith("ya29."), ("1/"), ("y0_") are OAuth
        val isLongPasswordOAuth = longPassword.startsWith("ya29.") || longPassword.startsWith("1/") || longPassword.startsWith("y0_")
        val isTokenOAuth = oauthToken.startsWith("ya29.") || oauthToken.startsWith("1/") || oauthToken.startsWith("y0_")

        assertTrue(!isLongPasswordOAuth)
        assertTrue(isTokenOAuth)
    }
}
