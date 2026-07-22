package com.example.catlifepet.server.auth

import com.example.catlifepet.server.config.SmtpSettings
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.Properties

data class LoginCodeEmail(
    val recipient: String,
    val code: String,
    val expiresAt: Instant
)

fun interface EmailSender {
    suspend fun sendLoginCode(email: LoginCodeEmail)
}

/**
 * Development-only mailbox. It keeps verification codes out of logs and writes them
 * under the ignored build directory for manual local testing.
 */
class DevelopmentMailboxEmailSender(
    private val mailboxDirectory: Path
) : EmailSender {
    override suspend fun sendLoginCode(email: LoginCodeEmail) = withContext(Dispatchers.IO) {
        Files.createDirectories(mailboxDirectory)
        val message = buildString {
            appendLine("To: ${email.recipient}")
            appendLine("Subject: CatLifePet login code")
            appendLine()
            appendLine("Your CatLifePet verification code is ${email.code}.")
            appendLine("It expires at ${email.expiresAt}.")
        }
        Files.writeString(
            mailboxDirectory.resolve("login-${UUID.randomUUID()}.txt"),
            message,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        Unit
    }
}

class SmtpEmailSender(
    private val settings: SmtpSettings
) : EmailSender {
    override suspend fun sendLoginCode(email: LoginCodeEmail) = withContext(Dispatchers.IO) {
        val properties = Properties().apply {
            setProperty("mail.smtp.host", settings.host)
            setProperty("mail.smtp.port", settings.port.toString())
            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.starttls.enable", settings.startTls.toString())
            setProperty("mail.smtp.starttls.required", settings.startTls.toString())
            setProperty("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MILLIS)
            setProperty("mail.smtp.timeout", SMTP_TIMEOUT_MILLIS)
            setProperty("mail.smtp.writetimeout", SMTP_TIMEOUT_MILLIS)
        }
        val session = Session.getInstance(
            properties,
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(settings.username, settings.password)
                }
            }
        )
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.fromAddress, "CatLifePet"))
            setRecipient(Message.RecipientType.TO, InternetAddress(email.recipient, true))
            subject = "Your CatLifePet login code"
            setText(
                "Your CatLifePet verification code is ${email.code}.\n" +
                    "It expires at ${email.expiresAt}.",
                Charsets.UTF_8.name()
            )
        }
        Transport.send(message)
    }

    private companion object {
        const val SMTP_TIMEOUT_MILLIS = "10000"
    }
}
