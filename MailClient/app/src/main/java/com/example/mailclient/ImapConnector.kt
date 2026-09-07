package com.example.mailclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store

object ImapConnector {

    data class ServerPreset(
        val label: String,
        val host: String,
        val port: Int,
        val smtpHost: String,
        val smtpPort: Int
    )

    val presets = listOf(
        ServerPreset("Yandex", "imap.yandex.ru", 993, "smtp.yandex.ru", 465),
        ServerPreset("Mail.ru", "imap.mail.ru", 993, "smtp.mail.ru", 465),
        ServerPreset("Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465),
        ServerPreset("Yahoo", "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465),
        ServerPreset("Свой сервер...", "", 993, "", 465)
    )

    data class MailHeader(
        val msgNum: Int,
        val subject: String,
        val from: String,
        val date: String
    )

    data class MailPage(
        val messages: List<MailHeader>,
        val totalCount: Int
    )

    val spamFolderNames = listOf(
        "spam", "junk", "junk e-mail", "bulk mail", "bulk",
        "спам", "[gmail]/spam"
    )

    val trashFolderNames = listOf(
        "trash", "deleted", "deleted items", "deleted messages", "bin",
        "корзина", "удалённые", "удаленные", "[gmail]/trash"
    )

    fun isSpamFolder(folderName: String): Boolean {
        return spamFolderNames.any { candidate -> folderName.equals(candidate, ignoreCase = true) }
    }

    fun isTrashFolder(folderName: String): Boolean {
        return trashFolderNames.any { candidate -> folderName.equals(candidate, ignoreCase = true) }
    }

    fun displayNameFor(folderName: String): String {
        return when {
            folderName.equals("INBOX", ignoreCase = true) -> "Входящие"
            isTrashFolder(folderName) -> "Корзина"
            isSpamFolder(folderName) -> "Спам"
            else -> folderName
        }
    }

    fun extractEmailAddress(from: String): String {
        val match = Regex("<([^>]+)>").find(from)
        return match?.groupValues?.get(1) ?: from.trim()
    }

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
                    msgNum = msg.messageNumber,
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

    data class MailBody(val displayHtml: String, val plainText: String)

    suspend fun fetchMessageBody(
        host: String,
        port: Int,
        email: String,
        password: String,
        msgNum: Int,
        folderName: String = "INBOX"
    ): Result<MailBody> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)

            val message = folder.getMessage(msgNum)
            val (plain, html) = extractParts(message)

            val plainText = (plain ?: html?.let { stripHtml(it) } ?: "").ifBlank { "(письмо не содержит текста)" }

            val displayHtml = if (html != null) {
                wrapHtml(html)
            } else {
                wrapHtml(escapeHtml(plainText).replace("\n", "<br>"))
            }

            Result.success(MailBody(displayHtml, plainText))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { folder?.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    suspend fun listFolders(
        host: String,
        port: Int,
        email: String,
        password: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        var store: Store? = null
        try {
            store = openStore(host, port, email, password)
            val defaultFolder = store.defaultFolder
            val all = defaultFolder.list("*")
            val selectable = all.filter { (it.type and Folder.HOLDS_MESSAGES) != 0 }
            val names = selectable.map { it.fullName }
            val sorted = names.sortedWith(
                compareBy(
                    { if (it.equals("INBOX", ignoreCase = true)) 0 else 1 },
                    { it }
                )
            )
            Result.success(sorted)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { store?.close() } catch (_: Exception) {}
        }
    }

    suspend fun moveMessage(
        host: String,
        port: Int,
        email: String,
        password: String,
        msgNum: Int,
        fromFolder: String,
        toFolder: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            folder = store.getFolder(fromFolder)
            folder.open(Folder.READ_WRITE)

            val message = folder.getMessage(msgNum)
            val destFolder = store.getFolder(toFolder)
            folder.copyMessages(arrayOf(message), destFolder)
            message.setFlag(Flags.Flag.DELETED, true)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { folder?.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    suspend fun deleteMessage(
        host: String,
        port: Int,
        email: String,
        password: String,
        msgNum: Int,
        folderName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            folder = store.getFolder(folderName)
            folder.open(Folder.READ_WRITE)

            val message = folder.getMessage(msgNum)
            message.setFlag(Flags.Flag.DELETED, true)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { folder?.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    suspend fun moveToSpam(
        host: String,
        port: Int,
        email: String,
        password: String,
        msgNum: Int,
        fromFolder: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val foldersResult = listFolders(host, port, email, password)
        val folders = foldersResult.getOrElse { return@withContext Result.failure(it) }

        val spamFolder = folders.firstOrNull { name ->
            spamFolderNames.any { candidate -> name.equals(candidate, ignoreCase = true) }
        }

        if (spamFolder == null) {
            return@withContext Result.failure(Exception("Папка «Спам» не найдена в этом ящике"))
        }

        moveMessage(host, port, email, password, msgNum, fromFolder, spamFolder)
    }

    suspend fun moveToTrash(
        host: String,
        port: Int,
        email: String,
        password: String,
        msgNum: Int,
        fromFolder: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val foldersResult = listFolders(host, port, email, password)
        val folders = foldersResult.getOrElse { return@withContext Result.failure(it) }

        val trashFolder = folders.firstOrNull { name ->
            trashFolderNames.any { candidate -> name.equals(candidate, ignoreCase = true) }
        }

        if (trashFolder == null) {
            return@withContext Result.failure(Exception("Папка «Корзина» не найдена в этом ящике"))
        }

        moveMessage(host, port, email, password, msgNum, fromFolder, trashFolder)
    }

    suspend fun restoreFromTrash(
        host: String,
        port: Int,
        email: String,
        password: String,
        msgNum: Int,
        fromFolder: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        moveMessage(host, port, email, password, msgNum, fromFolder, "INBOX")
    }

    private fun extractParts(part: Part): Pair<String?, String?> {
        return when {
            part.isMimeType("text/plain") -> Pair(part.content as? String, null)
            part.isMimeType("text/html") -> Pair(null, part.content as? String)
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                var plain: String? = null
                var html: String? = null
                for (i in 0 until mp.count) {
                    val bodyPart = mp.getBodyPart(i)
                    when {
                        bodyPart.isMimeType("text/plain") && plain == null ->
                            plain = bodyPart.content as? String
                        bodyPart.isMimeType("text/html") && html == null ->
                            html = bodyPart.content as? String
                        bodyPart.isMimeType("multipart/*") -> {
                            val (nestedPlain, nestedHtml) = extractParts(bodyPart)
                            if (plain == null) plain = nestedPlain
                            if (html == null) html = nestedHtml
                        }
                    }
                }
                Pair(plain, html)
            }
            else -> Pair(null, null)
        }
    }

    private fun wrapHtml(innerHtml: String): String {
        return """
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: sans-serif; font-size: 15px; color: #000000; padding: 4px; word-wrap: break-word; }
                    img { max-width: 100%; height: auto; }
                    a { color: #1a73e8; }
                </style>
            </head>
            <body>$innerHtml</body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }
}