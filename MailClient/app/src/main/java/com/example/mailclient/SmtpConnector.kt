package com.example.mailclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object SmtpConnector {

    suspend fun sendMail(
        smtpHost: String,
        smtpPort: Int,
        fromEmail: String,
        password: String,
        toEmail: String,
        subject: String,
        body: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.host", smtpHost)
                put("mail.smtp.port", smtpPort.toString())
            }
            val session = Session.getInstance(props)
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                setSubject(subject, "UTF-8")
                setText(body, "UTF-8")
            }

            val transport = session.getTransport("smtp")
            transport.connect(smtpHost, smtpPort, fromEmail, password)
            transport.sendMessage(message, message.allRecipients)
            transport.close()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}