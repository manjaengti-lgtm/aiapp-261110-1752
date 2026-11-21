// === ABOUT: Thumbnails, grid and picker for saved photos and videos ===
package com.arin.civicreport.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arin.civicreport.data.EvidenceItem

@Composable
fun VideoThumb(path: String, boxSize: Dp = 84.dp) {
    val frame: Bitmap? = remember(path) {
        runCatching {
            MediaMetadataRetriever().run {
                setDataSource(path)
                val b = getFrameAtTime(500_000)
                release()
                b?.let { Bitmap.createScaledBitmap(it, 256, (256f * it.height / it.width).toInt().coerceAtLeast(1), true) }
            }
        }.getOrNull()
    }
    Box(
        modifier = Modifier.size(boxSize).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        frame?.let {
            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(boxSize))
        }
        Icon(Icons.Filled.PlayCircle, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun EvidenceThumb(item: EvidenceItem, boxSize: Dp = 84.dp) {
    if (item.isVideo) VideoThumb(item.path, boxSize) else PhotoThumb(item.path, boxSize)
}

/** One row: picture, when and where it was taken, and a tick box. */
@Composable
fun EvidenceRow(
    item: EvidenceItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onToggle)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.clickable(onClick = onOpen)) {
            EvidenceThumb(item, 72.dp)
            if (selected) Icon(
                Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp)
                    .background(Color.White, RoundedCornerShape(10.dp))
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (item.isVideo) "Video · " else "Photo · ") + formatDate(item.takenAt),
                style = MaterialTheme.typography.titleSmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item.hasLocation) Icons.Filled.LocationOn else Icons.Filled.LocationOff, null,
                    modifier = Modifier.size(14.dp),
                    tint = if (item.hasLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.size(4.dp))
                Text(item.locationLine, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (item.hasLocation) Text(
                "%.6f, %.6f".format(item.latitude, item.longitude),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
    }
}

/** Lets the person choose saved photos and videos to add to the complaint they are writing. */
@Composable
fun EvidencePickerDialog(
    saved: List<EvidenceItem>,
    alreadyAdded: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (List<EvidenceItem>) -> Unit
) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved photos & videos") },
        text = {
            if (saved.isEmpty()) {
                Text("Nothing saved yet. Photos and videos you take in the app are kept here with their location.")
            } else {
                Column {
                    Text(
                        "The complaint will use the place where the first chosen item was taken.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.size(8.dp))
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(saved.filter { it.path !in alreadyAdded }, key = { it.id }) { item ->
                            EvidenceRow(
                                item = item,
                                selected = item.id in chosen,
                                onToggle = { chosen = if (item.id in chosen) chosen - item.id else chosen + item.id },
                                onOpen = { openMediaFile(context, item.path) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onAdd(saved.filter { it.id in chosen }) }) {
                Text(if (chosen.isEmpty()) "Add" else "Add ${chosen.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Confirms deleting, and whether the copy in the phone gallery goes too. */
@Composable
fun DeleteEvidenceDialog(count: Int, onDismiss: () -> Unit, onDelete: (alsoFromGallery: Boolean) -> Unit) {
    var fromGallery by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete this item?" else "Delete $count items?") },
        text = {
            Column {
                Text("It will be removed from this app and from any unsent complaint. Complaints already sent are not affected.")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { fromGallery = !fromGallery }) {
                    Checkbox(checked = fromGallery, onCheckedChange = { fromGallery = it })
                    Text("Also delete the copy in the phone gallery")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDelete(fromGallery) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
