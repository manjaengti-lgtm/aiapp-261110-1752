package com.arin.civicreport.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.arin.civicreport.model.Authority
import com.arin.civicreport.model.AuthorityTier
import com.arin.civicreport.model.Report
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SendHelper(private val context: Context) {

    private val stamp: String
        get() = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())

    fun buildBody(report: Report): String {
        val lines = StringBuilder()
        lines.appendLine("CIVIC / ENVIRONMENTAL COMPLAINT")
        lines.appendLine("Via Duarbagori Reports")
        lines.appendLine()
        lines.appendLine("Issues: ${report.types.joinToString(", ") { it.label }}")
        report.types.forEach { t ->
            val info = AuthorityRules.info(t)
            lines.appendLine("- ${t.label}: responsibility of ${info.handledBy} (${info.law}).")
        }
        lines.appendLine("Reported on: $stamp")
        val loc = report.location
        lines.appendLine("Location: ${loc.oneLine}")
        if (loc.hasFix) {
            lines.appendLine("Coordinates: %.6f, %.6f (±%.0f m)".format(loc.latitude, loc.longitude, loc.accuracy))
            lines.appendLine("Map: https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
        }
        if (loc.city.isNotBlank() || loc.district.isNotBlank() || loc.state.isNotBlank()) {
            lines.appendLine(
                "Jurisdiction: " + listOf(loc.city, loc.district, loc.state, loc.postcode)
                    .filter { it.isNotBlank() }.joinToString(", ")
            )
        }
        if (report.notes.isNotBlank()) {
            lines.appendLine()
            lines.appendLine("Details:")
            lines.appendLine(report.notes)
        }
        if (report.authorities.isNotEmpty()) {
            lines.appendLine()
            lines.appendLine("Addressed to:")
            AuthorityTier.entries.sortedBy { it.order }.forEach { tier ->
                val group = report.authorities.filter { it.tier == tier }
                if (group.isNotEmpty()) {
                    lines.appendLine("${tier.label}:")
                    group.forEach { lines.appendLine("- ${it.name}") }
                }
            }
        }
        val evidence = report.mediaUris.size + (if (report.videoUri != null) 1 else 0)
        if (evidence > 0 || report.attachments.isNotEmpty()) {
            lines.appendLine()
            lines.appendLine("Attached evidence:")
            if (report.mediaUris.isNotEmpty()) lines.appendLine("- ${report.mediaUris.size} geotagged photo(s)")
            if (report.videoUri != null) lines.appendLine("- 1 video")
            report.attachments.forEach { lines.appendLine("- ${it.name} (${it.sizeLabel})") }
        }
        lines.appendLine()
        lines.appendLine("I request that the above be inspected and action taken under the applicable law, and that I be informed of the action taken.")
        return lines.toString()
    }

    fun emailsOf(authorities: List<Authority>): List<String> =
        authorities.mapNotNull { it.email?.takeIf { e -> e.isNotBlank() } }.distinct()

    fun phonesOf(authorities: List<Authority>): List<String> =
        authorities.mapNotNull { it.phone?.takeIf { p -> p.isNotBlank() } }.distinct()

    /** Only Indian mobile numbers can receive an SMS; landlines and short codes are for calling. */
    fun smsNumbersOf(authorities: List<Authority>): List<String> =
        authorities.flatMap { listOfNotNull(it.phone) + it.otherPhones }
            .mapNotNull { mobileOrNull(it) }
            .distinct()

    private fun mobileOrNull(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        val ten = when {
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
        return if (ten.length == 10 && ten.first() in '6'..'9') "+91$ten" else null
    }

    /** Total size of every attachment, so the screen can warn about mail limits. */
    fun totalAttachmentBytes(report: Report): Long {
        val local = (report.mediaUris + listOfNotNull(report.videoUri)).sumOf { File(it).takeIf { f -> f.exists() }?.length() ?: 0L }
        val picked = report.attachments.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        return local + picked
    }

    private fun Intent.withStreams(uris: List<Uri>): Intent {
        if (uris.isEmpty()) return this
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        // ClipData is what actually carries the read grant to the receiving app.
        val clip = ClipData.newRawUri("evidence", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return this
    }

    /** What happened when the person pressed Send by email. */
    enum class EmailResult { OPENED, OPENED_WITHOUT_FILES, NO_EMAIL_APP, NO_RECIPIENTS }

    var lastEmailResult: EmailResult = EmailResult.OPENED
        private set

    private fun mailtoUri(recipients: List<String>, subject: String, body: String): Uri {
        val to = recipients.joinToString(",")
        val q = "subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body)
        return Uri.parse("mailto:$to?$q")
    }

    private fun buildSend(uris: List<Uri>, recipients: List<String>, subject: String, body: String): Intent =
        Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (uris.isEmpty()) "text/plain" else "*/*"
            putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData.newRawUri("evidence", uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else if (uris.size > 1) withStreams(uris)
        }

    private fun start(intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /**
     * Opens an email app with recipients, subject, body, photos, video and every attached file.
     * Finds the phone's real email apps (Gmail, Outlook, Samsung Email...) and targets them
     * directly, because the generic "email only" filter shows an empty list on many phones.
     */
    fun sendEmail(report: Report, subject: String, body: String, attachMedia: Boolean = true): Boolean {
        val recipients = emailsOf(report.authorities)
        if (recipients.isEmpty()) {
            lastEmailResult = EmailResult.NO_RECIPIENTS
            return false
        }
        val uris = if (attachMedia) allUris(report) else emptyList()
        val pm = context.packageManager
        val mailPackages = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
                .map { it.activityInfo.packageName }.distinct()
        }.getOrDefault(emptyList())

        val base = buildSend(uris, recipients, subject, body)
        val targeted = mailPackages.mapNotNull { pkg ->
            Intent(base).setPackage(pkg).takeIf {
                runCatching { pm.queryIntentActivities(it, 0).isNotEmpty() }.getOrDefault(false)
            }
        }

        when {
            targeted.size == 1 -> if (start(targeted.first())) {
                lastEmailResult = EmailResult.OPENED; return true
            }
            targeted.size > 1 -> {
                val chooser = Intent.createChooser(targeted.first(), "Send complaint by email")
                    .putExtra(Intent.EXTRA_INITIAL_INTENTS, targeted.drop(1).toTypedArray())
                if (start(chooser)) { lastEmailResult = EmailResult.OPENED; return true }
            }
        }
        // An email app exists but would not take the files: send the text by mailto.
        if (mailPackages.isNotEmpty()) {
            val mailto = Intent(Intent.ACTION_SENDTO, mailtoUri(recipients, subject, body)).apply {
                putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            if (start(mailto)) {
                lastEmailResult = if (uris.isEmpty()) EmailResult.OPENED else EmailResult.OPENED_WITHOUT_FILES
                return true
            }
        }
        lastEmailResult = EmailResult.NO_EMAIL_APP
        return false
    }

    /**
     * Shares the complaint text and every file through any app (Drive, WhatsApp, Telegram,
     * Bluetooth...). Useful when files are too large for email.
     */
    fun shareAnywhere(report: Report, subject: String, body: String): Boolean {
        val uris = allUris(report)
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (uris.isEmpty()) "text/plain" else "*/*"
            putExtra(Intent.EXTRA_EMAIL, emailsOf(report.authorities).toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData.newRawUri("evidence", uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else withStreams(uris)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Share complaint and files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** Opens the SMS app once, addressed to every mobile number of the selected offices. */
    fun sendSms(report: Report, body: String): Boolean {
        val numbers = smsNumbersOf(report.authorities)
        if (numbers.isEmpty()) return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";"))).apply {
            putExtra("sms_body", body)
        }
        if (start(intent)) return true
        // Some SMS apps take only one number at a time.
        return start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.first())).putExtra("sms_body", body))
    }

    fun openUrl(url: String): Boolean =
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess

    fun dial(phone: String): Boolean =
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.replace(" ", "")}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess

    fun smsTo(phone: String, body: String): Boolean =
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone.replace(" ", "")}")).apply {
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.isSuccess

    private fun allUris(report: Report): List<Uri> =
        mediaUris(report) + report.attachments.mapNotNull { runCatching { Uri.parse(it.uri) }.getOrNull() }

    private fun mediaUris(report: Report): List<Uri> {
        val list = mutableListOf<Uri>()
        report.videoUri?.let { path ->
            fileUri(path)?.let { list += it }
        }
        report.mediaUris.forEach { path -> fileUri(path)?.let { list += it } }
        return list
    }

    private fun fileUri(path: String): Uri? {
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
}
