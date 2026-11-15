package com.arin.civicreport.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.arin.civicreport.model.Authority
import com.arin.civicreport.model.AuthorityLevel
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
            AuthorityLevel.entries.sortedBy { it.order }.forEach { level ->
                val group = report.authorities.filter { it.level == level }
                if (group.isNotEmpty()) {
                    lines.appendLine("${level.label}:")
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

    /** Opens a mail app with recipients, subject, body, photos, video and every attached file. */
    fun sendEmail(report: Report, subject: String, body: String, attachMedia: Boolean = true): Boolean {
        val recipients = emailsOf(report.authorities)
        val uris = if (attachMedia) allUris(report) else emptyList()
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (uris.isEmpty()) "message/rfc822" else "*/*"
            putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData.newRawUri("evidence", uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else withStreams(uris)
            // Restrict the chooser to mail apps while still carrying attachments.
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
        val chooser = Intent.createChooser(intent, "Send complaint by email").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(chooser) }.isSuccess) return true
        // Some phones reject the selector; fall back to a plain share.
        intent.selector = null
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Send complaint").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /**
     * Shares the complaint text and every file through any app (Drive, WhatsApp, Telegram,
     * Bluetooth...). Useful when files are too large for email.
     */
    fun shareAnywhere(report: Report, subject: String, body: String): Boolean {
        val uris = allUris(report)
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (uris.isEmpty()) "text/plain" else "*/*"
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

    fun sendSms(report: Report, body: String): Boolean {
        val numbers = smsNumbersOf(report.authorities)
        if (numbers.isEmpty()) return false
        var ok = true
        numbers.take(4).forEach { number ->
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${number.replace(" ", "")}")
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isFailure) ok = false
        }
        return ok
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
