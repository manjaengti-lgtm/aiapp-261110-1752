@file:OptIn(ExperimentalMaterial3Api::class)

package com.fullshare.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = WaGreen, secondary = FbBlue)) {
                Surface(Modifier.fillMaxSize()) { AppScreen(this, vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { vm.setVideo(it) }
        }
    }
}

private val WaGreen = Color(0xFF128C7E)
private val FbBlue = Color(0xFF1877F2)

@Composable
private fun AppScreen(activity: MainActivity, vm: MainViewModel) {
    val st by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(st.message) {
        st.message?.let { snackbar.showSnackbar(it); vm.showMessage(null) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setVideo(uri)
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { vm.saveParts() } else vm.showMessage("Storage permission is needed to save")
    }

    fun shareParts(pkg: String?, appName: String) = scope.launch {
        if (pkg != null && !Sharer.isInstalled(activity, pkg)) {
            vm.showMessage("$appName is not installed"); return@launch
        }
        val files = vm.getParts() ?: return@launch
        val batches = files.chunked(Sharer.MAX_PER_SHARE)
        val idx = vm.state.value.batch.coerceIn(0, batches.lastIndex)
        val direct = vm.state.value.openStatusDirectly && pkg != null && pkg.startsWith("com.whatsapp")
        if (!Sharer.share(activity, batches[idx], pkg, direct)) {
            vm.showMessage("Couldn't open $appName")
        } else if (idx < batches.lastIndex) {
            vm.setBatch(idx + 1)
            vm.showMessage("After posting, tap again to share parts ${(idx + 1) * Sharer.MAX_PER_SHARE + 1}+")
        }
    }

    fun shareFullToFacebook() = scope.launch {
        val pkg = when {
            Sharer.isInstalled(activity, Sharer.FACEBOOK) -> Sharer.FACEBOOK
            Sharer.isInstalled(activity, Sharer.FACEBOOK_LITE) -> Sharer.FACEBOOK_LITE
            else -> { vm.showMessage("Facebook is not installed"); return@launch }
        }
        val f = vm.getFull() ?: return@launch
        if (!Sharer.share(activity, listOf(f), pkg)) vm.showMessage("Couldn't open Facebook")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Full Status Share", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WaGreen, titleContentColor = Color.White),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- Video card ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (st.uri == null) {
                        Text("Share a whole long video to WhatsApp Status in one tap. It's split into back-to-back parts in original HD quality.")
                    } else {
                        st.thumbnail?.let {
                            Image(
                                it.asImageBitmap(), null,
                                Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        st.info?.let { i ->
                            Text(i.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                "${fmtDur(i.durationMs)} · ${fmtSize(i.sizeBytes)}" +
                                    (if (i.width > 0) " · ${i.width}×${i.height}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Button(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        enabled = !st.busy, modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (st.uri == null) "Choose video" else "Choose another video") }
                }
            }

            if (st.uri != null) {
                // ---- Part length ----
                Text("Part length", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(59 to "60s WhatsApp", 30 to "30s", 15 to "15s").forEach { (sec, label) ->
                        FilterChip(
                            selected = st.partSec == sec, onClick = { vm.setPartSec(sec) },
                            label = { Text(label) }, enabled = !st.busy,
                        )
                    }
                }
                if (st.info != null) {
                    Text("≈ ${st.estimatedParts} part(s), no quality loss", style = MaterialTheme.typography.bodySmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open WhatsApp “My status” directly", Modifier.weight(1f))
                    Switch(checked = st.openStatusDirectly, onCheckedChange = vm::setOpenStatusDirectly)
                }

                // ---- Batches (WhatsApp: max 30 per share) ----
                val total = if (st.parts.isNotEmpty()) st.parts.size else st.estimatedParts
                if (total > Sharer.MAX_PER_SHARE) {
                    Text(
                        "WhatsApp accepts 30 videos per share, so this one goes in batches:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val n = (total + Sharer.MAX_PER_SHARE - 1) / Sharer.MAX_PER_SHARE
                        for (b in 0 until n) {
                            val from = b * Sharer.MAX_PER_SHARE + 1
                            val to = minOf(total, (b + 1) * Sharer.MAX_PER_SHARE)
                            FilterChip(selected = st.batch == b, onClick = { vm.setBatch(b) }, label = { Text("$from–$to") })
                        }
                    }
                }

                if (st.busy) {
                    Text(st.busyLabel)
                    LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                }

                // ---- Share buttons ----
                Button(
                    onClick = { shareParts(Sharer.WHATSAPP, "WhatsApp") }, enabled = !st.busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                ) { Text("Share to WhatsApp Status", fontWeight = FontWeight.Bold) }

                OutlinedButton(
                    onClick = { shareParts(Sharer.WHATSAPP_BUSINESS, "WhatsApp Business") }, enabled = !st.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("WhatsApp Business Status") }

                Button(
                    onClick = { shareFullToFacebook() }, enabled = !st.busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FbBlue),
                ) { Text("Share full video to Facebook", fontWeight = FontWeight.Bold) }

                OutlinedButton(
                    onClick = { shareParts(Sharer.FACEBOOK, "Facebook") }, enabled = !st.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Facebook Story (in parts)") }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shareParts(null, "share") }, enabled = !st.busy, modifier = Modifier.weight(1f)) {
                        Text("Other apps")
                    }
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                            ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            else scope.launch { vm.saveParts() }
                        },
                        enabled = !st.busy, modifier = Modifier.weight(1f),
                    ) { Text("Save parts") }
                }

                Text(
                    "Tip: in WhatsApp, tap the HD button at the top before sending so it keeps full quality.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun fmtSize(b: Long): String = when {
    b <= 0 -> "?"
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    else -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
}

@Suppress("unused")
private fun File.sizeMb() = length() / (1024.0 * 1024)
