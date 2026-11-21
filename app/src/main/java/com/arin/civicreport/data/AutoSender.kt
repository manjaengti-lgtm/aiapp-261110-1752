// === ABOUT: Sends complaints by itself — email over the person's own mail account (SMTP) and SMS through the SIM ===
package com.arin.civicreport.data

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.SmsManager
import android.util.Base64
import androidx.core.content.ContextCompat
import com.arin.civicreport.model.AuthorityTier
import com.arin.civicreport.model.Report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.DataSource
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.mail.AuthenticationFailedException
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlin.coroutines.resume

/** Mail services people in India commonly use. Each needs an "app password", not the normal password. */
enum class MailProvider(
    val label: String,
    val host: String,
    val port: Int,
    val ssl: Boolean,
    val help: String,
    val helpUrl: String
) {
    GMAIL(
        "Gmail", "smtp.gmail.com", 465, true,
        "Turn on 2-Step Verification on your Google account, then create an app password and paste the 16 letters here.",
        "https://myaccount.google.com/apppasswords"
    ),
    YAHOO(
        "Yahoo Mail", "smtp.mail.yahoo.com", 465, true,
        "Yahoo account → Account security → Generate app password.",
        "https://login.yahoo.com/account/security"
    ),
    ZOHO(
        "Zoho Mail", "smtp.zoho.in", 465, true,
        "Zoho → My Account → Security → App passwords.",
        "https://accounts.zoho.in/home#security/app_password"
    ),
    REDIFF(
        "Rediffmail", "smtp.rediffmail.com", 587, false,
        "Use your Rediffmail password.",
        "https://mail.rediff.com"
    ),
    CUSTOM("Other (enter server)", "", 587, false, "Enter your provider's SMTP server and port.", "");

    companion object {
        fun guess(email: String): MailProvider = when (email.substringAfter('@', "").lowercase()) {
            "gmail.com", "googlemail.com" -> GMAIL
            "yahoo.com", "yahoo.in", "ymail.com" -> YAHOO
            "zoho.com", "zoho.in", "zohomail.in" -> ZOHO
            "rediffmail.com" -> REDIFF
            else -> CUSTOM
        }
    }
}

data class MailAccount(
    /** True when the person signed in with the Google account already on the phone. */
    val google: Boolean = false,
    val provider: MailProvider = MailProvider.GMAIL,
    val email: String = "",
    val password: String = "",
    val host: String = MailProvider.GMAIL.host,
    val port: Int = MailProvider.GMAIL.port,
    val ssl: Boolean = true
) {
    val isSet: Boolean get() = email.isNotBlank() && (google || (password.isNotBlank() && host.isNotBlank()))
}

/** Keeps the mail password encrypted with a key that never leaves the phone's keystore. */
class MailAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("mail_account", Context.MODE_PRIVATE)
    private val alias = "duarbagori_mail_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String = runCatching {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        String(c.doFinal(all.copyOfRange(12, all.size)))
    }.getOrDefault("")

    fun load(): MailAccount {
        val provider = runCatching { MailProvider.valueOf(prefs.getString("provider", "GMAIL")!!) }
            .getOrDefault(MailProvider.GMAIL)
        return MailAccount(
            google = prefs.getBoolean("google", false),
            provider = provider,
            email = prefs.getString("email", "").orEmpty(),
            password = prefs.getString("password", null)?.let { decrypt(it) }.orEmpty(),
            host = prefs.getString("host", provider.host).orEmpty(),
            port = prefs.getInt("port", provider.port),
            ssl = prefs.getBoolean("ssl", provider.ssl)
        )
    }

    fun saveGoogle(email: String) {
        prefs.edit().clear().putBoolean("google", true).putString("email", email).apply()
    }

    fun save(a: MailAccount) {
        prefs.edit()
            .putBoolean("google", false)
            .putString("provider", a.provider.name)
            .putString("email", a.email.trim())
            .putString("password", encrypt(a.password.replace(" ", "")))
            .putString("host", a.host.trim())
            .putInt("port", a.port)
            .putBoolean("ssl", a.ssl)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

class AutoSender(private val context: Context, private val helper: SendHelper) {

    val accounts = MailAccountStore(context)
    val google = GoogleMailer(context)

    data class Outcome(
        val emailedTo: List<String> = emptyList(),
        val emailParts: Int = 0,
        val skippedFiles: List<String> = emptyList(),
        val smsSentTo: List<String> = emptyList(),
        val smsFailed: List<String> = emptyList(),
        val error: String? = null
    ) {
        val anySent: Boolean get() = emailedTo.isNotEmpty() || smsSentTo.isNotEmpty()
    }

    private fun session(a: MailAccount): Session {
        val p = Properties().apply {
            put("mail.smtp.host", a.host)
            put("mail.smtp.port", a.port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "60000")
            put("mail.smtp.writetimeout", "120000")
            put("mail.smtp.sendpartial", "true")
            if (a.ssl) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        }
        return Session.getInstance(p, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(a.email, a.password)
        })
    }

    /** Logs in to the mail server without sending anything, so the settings can be checked. */
    suspend fun test(a: MailAccount): String? = withContext(Dispatchers.IO) {
        runCatching {
            session(a).getTransport("smtp").use { it.connect(a.host, a.port, a.email, a.password) }
            null
        }.getOrElse { friendly(it) }
    }

    private fun friendly(e: Throwable): String = when {
        e is AuthenticationFailedException ->
            "The mail server refused the login. Use an app password (not your normal password)."
        e.message?.contains("UnknownHost", true) == true || e is java.net.UnknownHostException ->
            "No internet connection, or the mail server name is wrong."
        e.message?.contains("timed out", true) == true -> "The mail server did not answer in time. Check your internet."
        else -> e.message ?: e.javaClass.simpleName
    }

    private class UriSource(
        private val ctx: Context,
        private val uri: Uri,
        private val fileName: String,
        private val mime: String
    ) : DataSource {
        override fun getInputStream(): InputStream =
            ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open $fileName")
        override fun getOutputStream(): OutputStream = throw java.io.IOException("read only")
        override fun getContentType() = mime
        override fun getName() = fileName
    }

    private data class Part(val uri: Uri, val name: String, val mime: String, val size: Long)

    private fun parts(report: Report): List<Part> {
        val list = mutableListOf<Part>()
        (report.mediaUris + listOfNotNull(report.videoUri)).forEach { path ->
            val f = File(path)
            if (f.exists()) {
                val mime = when (f.extension.lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "mp4" -> "video/mp4"
                    else -> "application/octet-stream"
                }
                list += Part(Uri.fromFile(f), f.name, mime, f.length())
            }
        }
        report.attachments.forEach { a ->
            val uri = Uri.parse(a.uri)
            val size = if (a.sizeBytes > 0) a.sizeBytes else querySize(uri)
            list += Part(uri, a.name, a.mimeType.ifBlank { "application/octet-stream" }, size)
        }
        return list
    }

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    /**
     * Sends the complaint from the person's own mailbox. Offices who must act go in To, the
     * rest in Cc. Files are split over several emails so each stays under the 25 MB limit
     * most offices' servers accept; a single file larger than that is listed but not sent.
     */
    suspend fun sendEmail(report: Report, subject: String, body: String, senderName: String, onProgress: (String) -> Unit): Outcome =
        withContext(Dispatchers.IO) {
            val a = accounts.load()
            if (!a.isSet) return@withContext Outcome(error = "Set up your email account first.")
            val to = report.authorities.filter { it.tier == AuthorityTier.RESPONSIBLE }
                .mapNotNull { it.email?.trim()?.takeIf(String::isNotBlank) }.distinct()
            val all = helper.emailsOf(report.authorities)
            val primary = to.ifEmpty { all }
            val cc = all - primary.toSet()
            if (primary.isEmpty()) return@withContext Outcome(error = "None of the selected offices has an email address.")

            val limit = 20L * 1024 * 1024
            val (sendable, tooBig) = parts(report).partition { it.size in 0..limit || it.size < 0 }
            val batches = mutableListOf<MutableList<Part>>()
            sendable.forEach { p ->
                val last = batches.lastOrNull()
                val used = last?.sumOf { it.size.coerceAtLeast(0) } ?: Long.MAX_VALUE
                if (last == null || used + p.size.coerceAtLeast(0) > limit) batches += mutableListOf(p) else last += p
            }
            if (batches.isEmpty()) batches += mutableListOf<Part>()

            val total = batches.size
            val session = if (a.google) Session.getInstance(Properties()) else session(a)

            fun build(i: Int, batch: List<Part>): MimeMessage = MimeMessage(session).apply {
                setFrom(InternetAddress(a.email, senderName.ifBlank { a.email }, "UTF-8"))
                setRecipients(Message.RecipientType.TO, primary.joinToString(","))
                if (cc.isNotEmpty()) setRecipients(Message.RecipientType.CC, cc.joinToString(","))
                replyTo = arrayOf(InternetAddress(a.email))
                setSubject(if (total > 1) "$subject (part ${i + 1} of $total)" else subject, "UTF-8")
                sentDate = Date()
                val text = buildString {
                    if (i > 0) appendLine("Further evidence for the complaint below (part ${i + 1} of $total).\n")
                    append(body)
                    if (tooBig.isNotEmpty()) {
                        appendLine("\n")
                        appendLine("These files are too large to email and are available on request:")
                        tooBig.forEach { appendLine("- ${it.name}") }
                    }
                }
                val mp = MimeMultipart()
                mp.addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
                batch.forEach { p ->
                    mp.addBodyPart(MimeBodyPart().apply {
                        dataHandler = DataHandler(UriSource(context, p.uri, p.name, p.mime))
                        fileName = p.name
                    })
                }
                setContent(mp)
                saveChanges()
            }

            runCatching {
                if (a.google) {
                    onProgress("Connecting to Gmail…")
                    val token = google.token()
                        ?: return@withContext Outcome(error = "Google sign-in has expired. Tap \"Continue with Google\" again.")
                    batches.forEachIndexed { i, batch ->
                        onProgress(if (total > 1) "Sending email ${i + 1} of $total…" else "Sending email…")
                        google.send(token, build(i, batch))
                    }
                } else {
                    session.getTransport("smtp").use { transport ->
                        onProgress("Connecting to ${a.host}…")
                        transport.connect(a.host, a.port, a.email, a.password)
                        batches.forEachIndexed { i, batch ->
                            onProgress(if (total > 1) "Sending email ${i + 1} of $total…" else "Sending email…")
                            val msg = build(i, batch)
                            transport.sendMessage(msg, msg.allRecipients)
                        }
                    }
                }
                Outcome(emailedTo = primary + cc, emailParts = total, skippedFiles = tooBig.map { it.name })
            }.getOrElse { Outcome(error = friendly(it)) }
        }

    fun canSendSms(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)

    /** Short text for SMS, since the full complaint goes by email. */
    fun smsText(report: Report, emailed: Boolean, from: String): String = buildString {
        append("Complaint: ${report.title} at ${report.location.oneLine}")
        if (report.location.hasFix) append(" (https://maps.google.com/?q=%.6f,%.6f)".format(report.location.latitude, report.location.longitude))
        append(". Please inspect and act.")
        if (emailed && from.isNotBlank()) append(" Full complaint with evidence emailed from $from.")
        append(" - Duarbagori Reports")
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /** Sends the SMS straight from the SIM and waits for the network to confirm each one. */
    suspend fun sendSms(report: Report, text: String, onProgress: (String) -> Unit): Outcome {
        val numbers = helper.smsNumbersOf(report.authorities)
        if (numbers.isEmpty()) return Outcome()
        if (!canSendSms()) return Outcome(smsFailed = numbers, error = "SMS permission not given.")
        val sm = smsManager()
        val ok = mutableListOf<String>()
        val bad = mutableListOf<String>()
        numbers.forEachIndexed { i, n ->
            onProgress("Sending SMS ${i + 1} of ${numbers.size}…")
            if (sendOne(sm, n, text)) ok += n else bad += n
        }
        return Outcome(smsSentTo = ok, smsFailed = bad)
    }

    private suspend fun sendOne(sm: SmsManager, number: String, text: String): Boolean {
        val action = "com.arin.civicreport.SMS_SENT." + System.nanoTime()
        val parts = sm.divideMessage(text)
        return withTimeoutOrNull(45_000) {
            suspendCancellableCoroutine { cont ->
                var remaining = parts.size
                var failed = false
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (resultCode != android.app.Activity.RESULT_OK) failed = true
                        remaining--
                        if (remaining <= 0 && cont.isActive) {
                            runCatching { context.unregisterReceiver(this) }
                            cont.resume(!failed)
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val sent = ArrayList(parts.indices.map {
                    PendingIntent.getBroadcast(
                        context, it, Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    )
                })
                val r = runCatching { sm.sendMultipartTextMessage(number, null, parts, sent, null) }
                if (r.isFailure && cont.isActive) {
                    runCatching { context.unregisterReceiver(receiver) }
                    cont.resume(false)
                }
            }
        } ?: false
    }
}
