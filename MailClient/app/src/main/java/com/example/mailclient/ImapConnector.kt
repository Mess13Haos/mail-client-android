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
import android.util.Base64
import javax.mail.UIDFolder

object ImapConnector {

    data class ServerPreset(
        val label: String,
        val host: String,
        val port: Int,
        val smtpHost: String,
        val smtpPort: Int
    )

    data class NewMailInfo(val uid: Long, val from: String, val subject: String)

    suspend fun fetchNewInboxMail(
        host: String,
        port: Int,
        email: String,
        password: String,
        sinceUid: Long
    ): Result<Pair<List<NewMailInfo>, Long>> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            val uidFolder = folder as UIDFolder

            val currentHighestUid = uidFolder.getUIDNext() - 1

            if (sinceUid <= 0) {
                return@withContext Result.success(Pair(emptyList(), currentHighestUid))
            }
            if (currentHighestUid <= sinceUid) {
                return@withContext Result.success(Pair(emptyList(), sinceUid))
            }

            val newMessages = uidFolder.getMessagesByUID(sinceUid + 1, UIDFolder.LASTUID)
            val fetchProfile = FetchProfile()
            fetchProfile.add(FetchProfile.Item.ENVELOPE)
            folder.fetch(newMessages, fetchProfile)

            val infos = newMessages.mapNotNull { msg ->
                val uid = uidFolder.getUID(msg)
                if (uid > sinceUid) {
                    NewMailInfo(
                        uid = uid,
                        from = decodeMimeWords(msg.from?.joinToString(", ") { it.toString() } ?: "(неизвестно)"),
                        subject = decodeMimeWords(msg.subject ?: "(без темы)")
                    )
                } else null
            }

            Result.success(Pair(infos, currentHighestUid))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { folder?.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    suspend fun markAsReadByUid(
        host: String, port: Int, email: String, password: String, uid: Long, folderName: String = "INBOX"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            folder = store.getFolder(folderName)
            folder.open(Folder.READ_WRITE)
            val message = (folder as UIDFolder).getMessageByUID(uid) ?: return@withContext Result.failure(Exception("Письмо не найдено"))
            message.setFlag(Flags.Flag.SEEN, true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { folder?.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    suspend fun markAsRead(
        host: String, port: Int, email: String, password: String, msgNum: Int, folderName: String = "INBOX"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            folder = store.getFolder(folderName)
            folder.open(Folder.READ_WRITE)
            val message = folder.getMessage(msgNum) ?: return@withContext Result.failure(Exception("Письмо не найдено"))
            message.setFlag(Flags.Flag.SEEN, true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { folder?.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    suspend fun moveToSpamByUid(
        host: String, port: Int, email: String, password: String, uid: Long, folderName: String = "INBOX"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            val folders = listFolders(host, port, email, password).getOrElse { return@withContext Result.failure(it) }
            val spamFolder = folders.firstOrNull { name -> spamFolderNames.any { it.equals(name, ignoreCase = true) } }
                ?: return@withContext Result.failure(Exception("Папка «Спам» не найдена"))

            folder = store.getFolder(folderName)
            folder.open(Folder.READ_WRITE)
            val message = (folder as UIDFolder).getMessageByUID(uid) ?: return@withContext Result.failure(Exception("Письмо не найдено"))
            val destFolder = store.getFolder(spamFolder)
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

    suspend fun moveToTrashByUid(
        host: String, port: Int, email: String, password: String, uid: Long, folderName: String = "INBOX"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            val folders = listFolders(host, port, email, password).getOrElse { return@withContext Result.failure(it) }
            val trashFolder = folders.firstOrNull { name -> trashFolderNames.any { it.equals(name, ignoreCase = true) } }
                ?: return@withContext Result.failure(Exception("Папка «Корзина» не найдена"))

            folder = store.getFolder(folderName)
            folder.open(Folder.READ_WRITE)
            val message = (folder as UIDFolder).getMessageByUID(uid) ?: return@withContext Result.failure(Exception("Письмо не найдено"))
            val destFolder = store.getFolder(trashFolder)
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
        val clean = when {
            folderName.startsWith("INBOX/", ignoreCase = true) -> folderName.substring(6)
            folderName.startsWith("INBOX.", ignoreCase = true) -> folderName.substring(6)
            else -> folderName
        }
        return when {
            clean.equals("INBOX", ignoreCase = true) -> "Входящие"
            isTrashFolder(clean) -> "Корзина"
            isSpamFolder(clean) -> "Спам"
            else -> clean
        }
    }

    fun extractEmailAddress(from: String): String {
        val match = Regex("<([^>]+)>").find(from)
        return match?.groupValues?.get(1) ?: from.trim()
    }

    private val encodedWordRegex = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")

    fun decodeMimeWords(text: String): String {
        return try {
            encodedWordRegex.replace(text) { match ->
                val charsetName = match.groupValues[1]
                val encoding = match.groupValues[2].uppercase()
                val content = match.groupValues[3]
                val charset = try {
                    java.nio.charset.Charset.forName(charsetName)
                } catch (e: Exception) {
                    Charsets.UTF_8
                }
                val decodedBytes = if (encoding == "B") {
                    Base64.decode(content, Base64.DEFAULT)
                } else {
                    val out = java.io.ByteArrayOutputStream()
                    var i = 0
                    while (i < content.length) {
                        when (val c = content[i]) {
                            '_' -> { out.write(' '.code); i++ }
                            '=' -> {
                                val hex = content.substring(i + 1, i + 3)
                                out.write(hex.toInt(16))
                                i += 3
                            }
                            else -> { out.write(c.code); i++ }
                        }
                    }
                    out.toByteArray()
                }
                String(decodedBytes, charset)
            }
        } catch (e: Exception) {
            text
        }
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
                    subject = decodeMimeWords(msg.subject ?: "(без темы)"),
                    from = decodeMimeWords(msg.from?.joinToString(", ") { it.toString() } ?: "(неизвестно)"),
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

    data class AttachmentInfo(
        val index: Int,
        val fileName: String,
        val contentType: String
    )

    data class MailBody(
        val displayHtml: String,
        val plainText: String,
        val attachments: List<AttachmentInfo> = emptyList()
    )

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
            val attachments = mutableListOf<AttachmentInfo>()
            var counter = 0
            val (plain, html) = extractParts(message, attachments) { counter++ }

            val plainText = (plain ?: html?.let { stripHtml(it) } ?: "").ifBlank { "(письмо не содержит текста)" }

            val displayHtml = if (html != null) {
                wrapHtml(html)
            } else {
                wrapHtml(escapeHtml(plainText).replace("\n", "<br>"))
            }

            Result.success(MailBody(displayHtml, plainText, attachments))
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
            val sorted = names.sortedWith(Comparator { a, b ->
                val aIsTrash = isTrashFolder(a) || isTrashFolder(displayNameFor(a))
                val bIsTrash = isTrashFolder(b) || isTrashFolder(displayNameFor(b))
                val aIsSpam = isSpamFolder(a) || isSpamFolder(displayNameFor(a))
                val bIsSpam = isSpamFolder(b) || isSpamFolder(displayNameFor(b))

                val aRank = when {
                    aIsTrash -> 3
                    aIsSpam -> 2
                    else -> 1
                }
                val bRank = when {
                    bIsTrash -> 3
                    bIsSpam -> 2
                    else -> 1
                }

                if (aRank != bRank) {
                    aRank.compareTo(bRank)
                } else {
                    displayNameFor(a).compareTo(displayNameFor(b), ignoreCase = true)
                }
            })
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

    private fun extractParts(
        part: Part,
        attachments: MutableList<AttachmentInfo>,
        nextIndex: () -> Int
    ): Pair<String?, String?> {
        val disposition = try { part.disposition } catch (_: Exception) { null }
        val rawFileName = try { part.fileName } catch (_: Exception) { null }
        val fileName = rawFileName?.let { decodeMimeWords(it) }

        val isAttachment = (disposition != null && (disposition.equals(Part.ATTACHMENT, ignoreCase = true) || disposition.equals(Part.INLINE, ignoreCase = true))) ||
                (fileName != null && fileName.isNotBlank() && !part.isMimeType("text/plain") && !part.isMimeType("text/html"))

        if (isAttachment && fileName != null) {
            val contentType = try { part.contentType ?: "application/octet-stream" } catch (_: Exception) { "application/octet-stream" }
            attachments.add(
                AttachmentInfo(
                    index = nextIndex(),
                    fileName = fileName,
                    contentType = contentType.substringBefore(';')
                )
            )
            return Pair(null, null)
        }

        return when {
            part.isMimeType("text/plain") && fileName == null -> Pair(part.content as? String, null)
            part.isMimeType("text/html") && fileName == null -> Pair(null, part.content as? String)
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                var plain: String? = null
                var html: String? = null
                for (i in 0 until mp.count) {
                    val bodyPart = mp.getBodyPart(i)
                    val (nestedPlain, nestedHtml) = extractParts(bodyPart, attachments, nextIndex)
                    if (plain == null) plain = nestedPlain
                    if (html == null) html = nestedHtml
                }
                Pair(plain, html)
            }
            else -> Pair(null, null)
        }
    }

    suspend fun downloadAttachment(
        host: String, port: Int, email: String, password: String,
        msgNum: Int, folderName: String, targetIndex: Int
    ): Result<Pair<String, ByteArray>> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var folder: Folder? = null
        try {
            store = openStore(host, port, email, password)
            folder = store.getFolder(folderName)
            folder.open(Folder.READ_ONLY)
            val message = folder.getMessage(msgNum)

            var foundResult: Pair<String, ByteArray>? = null
            var counter = 0

            fun findAndRead(p: Part) {
                if (foundResult != null) return
                val disposition = try { p.disposition } catch (_: Exception) { null }
                val rawFileName = try { p.fileName } catch (_: Exception) { null }
                val fileName = rawFileName?.let { decodeMimeWords(it) }

                val isAttachment = (disposition != null && (disposition.equals(Part.ATTACHMENT, ignoreCase = true) || disposition.equals(Part.INLINE, ignoreCase = true))) ||
                        (fileName != null && fileName.isNotBlank() && !p.isMimeType("text/plain") && !p.isMimeType("text/html"))

                if (isAttachment && fileName != null) {
                    if (counter == targetIndex) {
                        val inputStream = p.inputStream
                        val bytes = inputStream.readBytes()
                        foundResult = Pair(fileName, bytes)
                        return
                    }
                    counter++
                }

                if (p.isMimeType("multipart/*")) {
                    try {
                        val mp = p.content as Multipart
                        for (i in 0 until mp.count) {
                            findAndRead(mp.getBodyPart(i))
                            if (foundResult != null) return
                        }
                    } catch (_: Exception) {}
                }
            }

            findAndRead(message)

            if (foundResult != null) {
                Result.success(foundResult!!)
            } else {
                Result.failure(Exception("Вложение не найдено"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { folder?.close(false) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    private fun wrapHtml(innerHtml: String): String {
        return """
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: sans-serif; font-size: 15px; color: #000000; padding: 4px; word-wrap: break-word; max-width: 100%; overflow-x: hidden; }
                                        body * {
                        max-width: 100% !important;
                        box-sizing: border-box !important;
                        position: static !important;
                        float: none !important;
                        margin: 0 !important;
                        height: auto !important;
                        min-height: 0 !important;
                        line-height: 1.4 !important;
                        top: auto !important;
                        left: auto !important;
                        right: auto !important;
                        bottom: auto !important;
                        transform: none !important;
                    }
                    table, tbody, thead, tr, td, th {
                        display: inline !important;
                    }
                    td, th {
                        padding: 0 2px !important;
                    }
                    p, div {
                        display: block !important;
                        margin-bottom: 8px !important;
                    }
                    img { height: auto !important; max-width: 100% !important; }
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