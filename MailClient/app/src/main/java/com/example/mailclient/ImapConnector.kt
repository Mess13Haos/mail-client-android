package com.example.mailclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store

object ImapConnector {

    data class ServerPreset(val label: String, val host: String, val port: Int)

    val presets = listOf(
        ServerPreset("Yandex", "imap.yandex.ru", 993),
        ServerPreset("Mail.ru", "imap.mail.ru", 993),
        ServerPreset("Gmail", "imap.gmail.com", 993),
        ServerPreset("Yahoo", "imap.mail.yahoo.com", 993),
        ServerPreset("Свой сервер...", "", 993)
    )

    data class MailHeader(
        val subject: String,
        val from: String,
        val date: String
    )

    data class MailPage(
        val messages: List<MailHeader>,
        val totalCount: Int
    )

    private fun openStore(host: String, port: Int, email: String, password: String): Store {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(host, port, email, password)
        return store
    }

    suspend fun fetchMessages(
        host: String,
        port: Int,
        email: String,
        password: String,
        offset: Int,
        limit: Int = 20,
        folderName: String = "INBOX"
    ): Result<MailPage> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)

            val total = folder.messageCount
            if (total == 0) {
                return@withContext Result.success(MailPage(emptyList(), 0))
            }

            val highIndex = total - offset
            if (highIndex < 1) {
                return@withContext Result.success(MailPage(emptyList(), total))
            }
            val lowIndex = maxOf(1, highIndex - limit + 1)

            val rawMessages = folder.getMessages(lowIndex, highIndex)

            val fetchProfile = FetchProfile()
            fetchProfile.add(FetchProfile.Item.ENVELOPE)
            folder.fetch(rawMessages, fetchProfile)

            val headers = rawMessages.map { msg ->
                MailHeader(
                    subject = msg.subject ?: "(без темы)",
                    from = msg.from?.joinToString(", ") { it.toString() } ?: "(неизвестно)",
                    date = msg.sentDate?.toString() ?: ""
                )
            }.reversed()

            Result.success(MailPage(headers, total))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { folder?.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }
}