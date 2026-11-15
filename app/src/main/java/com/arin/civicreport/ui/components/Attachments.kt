package com.arin.civicreport.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.civicreport.model.Attachment

/** Mail apps usually refuse more than about 25 MB in one message. */
const val MAIL_LIMIT_BYTES = 20L * 1024 * 1024

fun openAttachment(context: Context, attachment: Attachment) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(attachment.uri), attachment.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open ${attachment.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun iconFor(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Filled.Image
    mime.startsWith("video/") -> Icons.Filled.Movie
    mime.startsWith("audio/") -> Icons.Filled.MusicNote
    mime == "application/pdf" -> Icons.Filled.PictureAsPdf
    else -> Icons.Filled.Description
}

/**
 * A card that lists attached files and lets the person add more of any type and size
 * (documents, PDFs, audio recordings, long videos, archives...).
 */
@Composable
fun AttachmentsCard(
    attachments: List<Attachment>,
    onAdd: ((List<Uri>) -> Unit)?,
    onRemove: ((Attachment) -> Unit)?,
    title: String = "Attached files",
    totalBytes: Long = attachments.sumOf { it.sizeBytes.coerceAtLeast(0) }
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onAdd?.invoke(uris)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (attachments.isEmpty()) title else "$title (${attachments.size})",
                style = MaterialTheme.typography.titleMedium
            )
            if (attachments.isEmpty() && onAdd != null) {
                Text(
                    "Add documents, PDFs, audio, long videos, spreadsheets or any other file as evidence. There is no size or type limit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            attachments.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(iconFor(a.mimeType), contentDescription = null)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${a.sizeLabel} · ${a.mimeType}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { openAttachment(context, a) }) {
                        Icon(Icons.Filled.Visibility, contentDescription = "Open ${a.name}")
                    }
                    if (onRemove != null) {
                        IconButton(onClick = { onRemove(a) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${a.name}")
                        }
                    }
                }
            }
            if (totalBytes > MAIL_LIMIT_BYTES) {
                Text(
                    "These files add up to more than 20 MB, which most mail apps will not send in one message. " +
                        "Use \"Share via any app\" to send them through Drive, WhatsApp or Telegram, or upload them on the CPGRAMS portal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (onAdd != null) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Attach files (any type, any size)")
                }
            }
        }
    }
}
