// === ABOUT: "Saved photos & videos" — take geotagged evidence now, write the complaint later, or delete it ===
package com.arin.civicreport.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arin.civicreport.data.EvidenceStore
import com.arin.civicreport.ui.AppScaffold
import com.arin.civicreport.ui.components.CameraHandle
import com.arin.civicreport.ui.components.CameraPane
import com.arin.civicreport.ui.components.DeleteEvidenceDialog
import com.arin.civicreport.ui.components.EvidenceRow
import com.arin.civicreport.ui.components.openMediaFile
import com.arin.civicreport.viewmodel.ReportViewModel
import kotlinx.coroutines.launch

@Composable
fun EvidenceScreen(vm: ReportViewModel, onBack: () -> Unit, onStartComplaint: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val saved by vm.evidenceItems.collectAsStateWithLifecycle()
    val fix by vm.quickFix.collectAsStateWithLifecycle()
    val locating by vm.quickLocating.collectAsStateWithLifecycle()
    val camera = remember { CameraHandle() }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var cameraOn by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var audioGranted by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var locationGranted by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var recordStart by remember { mutableStateOf(0L) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        cameraGranted = r[Manifest.permission.CAMERA] ?: cameraGranted
        audioGranted = r[Manifest.permission.RECORD_AUDIO] ?: audioGranted
        locationGranted = r[Manifest.permission.ACCESS_FINE_LOCATION] ?: locationGranted
        if (locationGranted) vm.locateForCapture()
        if (cameraGranted) cameraOn = true
    }

    LaunchedEffect(Unit) { if (locationGranted && !fix.hasFix) vm.locateForCapture() }

    AppScaffold(screenTitle = "Saved photos & videos", onBack = onBack, snackbarHostState = snackbar) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Take photos and videos at the spot now and write the complaint later. Each one keeps the place and time it was taken.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Current location", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    !locationGranted -> "Location permission is off"
                                    fix.hasFix -> fix.oneLine + "  ±%.0f m".format(fix.accuracy)
                                    locating -> "Finding your position…"
                                    else -> "No fix yet"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (locating) CircularProgressIndicator(Modifier.size(20.dp))
                        else IconButton(onClick = {
                            if (locationGranted) vm.locateForCapture()
                            else perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                        }) { Icon(Icons.Filled.MyLocation, "Refresh location") }
                    }
                }
            }

            item {
                if (!cameraOn) {
                    Button(
                        onClick = {
                            val need = (listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION) +
                                if (android.os.Build.VERSION.SDK_INT < 29) listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList())
                                .filterNot { granted(it) }
                            if (need.isEmpty()) cameraOn = true else perms.launch(need.toTypedArray())
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.size(8.dp)); Text("Open camera")
                    }
                } else if (cameraGranted) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CameraPane(handle = camera, onError = { m -> scope.launch { snackbar.showSnackbar(m) } })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = !camera.isRecording,
                                onClick = {
                                    if (!fix.hasFix) scope.launch { snackbar.showSnackbar("No location yet — the photo will be saved without it.") }
                                    val loc = fix
                                    camera.takePhoto(
                                        context = context,
                                        output = vm.mediaStore.newPhotoFile(),
                                        onSaved = { f ->
                                            scope.launch {
                                                vm.saveCapturedPhoto(f, loc)
                                                snackbar.showSnackbar("Photo saved with location and time.")
                                            }
                                        },
                                        onError = { m -> scope.launch { snackbar.showSnackbar(m) } }
                                    )
                                }
                            ) { Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.size(6.dp)); Text("Photo") }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    if (camera.isRecording) camera.stopRecording()
                                    else {
                                        val loc = fix
                                        val file = vm.mediaStore.newVideoFile()
                                        recordStart = System.currentTimeMillis()
                                        camera.startRecording(
                                            context = context,
                                            output = file,
                                            withAudio = audioGranted,
                                            location = EvidenceStore.androidLocation(loc),
                                            onFinished = { ok ->
                                                scope.launch {
                                                    if (ok) {
                                                        vm.saveCapturedVideo(file, loc, recordStart)
                                                        snackbar.showSnackbar("Video saved with location and time.")
                                                    } else {
                                                        file.delete(); snackbar.showSnackbar("Video recording failed.")
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            ) {
                                Icon(if (camera.isRecording) Icons.Filled.Stop else Icons.Filled.Videocam, null)
                                Spacer(Modifier.size(6.dp))
                                Text(if (camera.isRecording) "Stop" else "Video")
                            }
                        }
                        TextButton(onClick = { if (!camera.isRecording) cameraOn = false }) { Text("Close camera") }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved (${saved.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (saved.isNotEmpty()) TextButton(onClick = {
                        selected = if (selected.size == saved.size) emptySet() else saved.map { it.id }.toSet()
                    }) { Text(if (selected.size == saved.size) "Clear selection" else "Select all") }
                }
            }

            if (selected.isNotEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = {
                        vm.clearDraft()
                        vm.useEvidence(saved.filter { it.id in selected })
                        selected = emptySet()
                        onStartComplaint()
                    }) { Text("Make complaint (${selected.size})") }
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(4.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (saved.isEmpty()) item {
                Text(
                    "Nothing saved yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(saved, key = { it.id }) { item ->
                EvidenceRow(
                    item = item,
                    selected = item.id in selected,
                    onToggle = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                    onOpen = { openMediaFile(context, item.path) }
                )
            }
        }
    }

    if (confirmDelete) {
        DeleteEvidenceDialog(
            count = selected.size,
            onDismiss = { confirmDelete = false },
            onDelete = { fromGallery ->
                vm.deleteEvidence(saved.filter { it.id in selected }, fromGallery)
                val n = selected.size
                selected = emptySet(); confirmDelete = false
                scope.launch { snackbar.showSnackbar(if (n == 1) "Deleted." else "$n items deleted.") }
            }
        )
    }
}
