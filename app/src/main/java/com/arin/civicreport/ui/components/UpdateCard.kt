// === ABOUT: "Update available" card with a button that fills up as the new version downloads ===
package com.arin.civicreport.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.arin.civicreport.viewmodel.ReportViewModel
import com.arin.civicreport.viewmodel.ReportViewModel.UpdateState

private fun mb(bytes: Long) = if (bytes <= 0) "" else "%.1f MB".format(bytes / 1_048_576.0)

/**
 * A wide button that shows the download filling from left to right with the percentage on it.
 */
@Composable
fun ProgressButton(label: String, progress: Float?, enabled: Boolean = true, onClick: () -> Unit) {
    val shown by animateFloatAsState(progress ?: 0f, label = "progress")
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(
                if (progress != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                else if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (progress != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(shown.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(
            label,
            color = if (progress != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Shows only when there is something to say about updates, unless [alwaysShow] is set
 * (the Settings screen, where people can also check by hand).
 */
@Composable
fun UpdateCard(vm: ReportViewModel, alwaysShow: Boolean = false) {
    val state by vm.update.collectAsState()

    // Coming back from the "allow installs" settings page continues the install by itself.
    LifecycleResumeEffect(state) {
        if (state is UpdateState.NeedsPermission && vm.updater.canInstall()) vm.installUpdate()
        onPauseOrDispose { }
    }

    val visible = alwaysShow || state is UpdateState.Available || state is UpdateState.Downloading ||
        state is UpdateState.Ready || state is UpdateState.NeedsPermission ||
        (state is UpdateState.Failed && (state as UpdateState.Failed).info != null)
    if (!visible) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    when (val st = state) {
                        is UpdateState.Available -> "New version available (build ${st.info.build})"
                        is UpdateState.Downloading -> "Downloading build ${st.info.build}"
                        is UpdateState.Ready -> "Build ${st.info.build} is ready to install"
                        is UpdateState.NeedsPermission -> "Allow installing updates"
                        is UpdateState.Failed -> "Update problem"
                        UpdateState.Checking -> "Checking for updates…"
                        UpdateState.UpToDate -> "You have the latest version"
                        UpdateState.Idle -> "App updates"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                "This phone has build ${vm.updater.currentBuild}" +
                    (vm.updater.currentVersionName.takeIf { it.isNotBlank() }?.let { " (version $it)" } ?: "") + ".",
                style = MaterialTheme.typography.bodySmall
            )

            when (val st = state) {
                is UpdateState.Available -> {
                    if (st.info.notes.isNotBlank()) Text(
                        "What's new: " + st.info.notes.lineSequence().first().take(160),
                        style = MaterialTheme.typography.bodySmall
                    )
                    ProgressButton("Update now  ${mb(st.info.sizeBytes)}", progress = null) { vm.startUpdate() }
                }
                is UpdateState.Downloading -> {
                    ProgressButton("${(st.progress * 100).toInt()}%  of ${mb(st.info.sizeBytes)}", progress = st.progress, enabled = false) {}
                    TextButton(onClick = { vm.cancelUpdate() }) { Text("Cancel") }
                }
                is UpdateState.Ready -> {
                    ProgressButton("Install update", progress = null) { vm.installUpdate() }
                    Text(
                        "Android will ask you to confirm. Your complaints, saved photos and settings are kept.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is UpdateState.NeedsPermission -> {
                    Text(
                        "Android needs your permission once: turn on \"Allow from this source\" for Duarbagori Reports, then come back.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    ProgressButton("Open setting", progress = null) { vm.installUpdate() }
                }
                is UpdateState.Failed -> {
                    Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (st.info != null) ProgressButton("Try again", progress = null) { vm.startUpdate() }
                    else OutlinedButton(onClick = { vm.checkForUpdate(silent = false) }) { Text("Check again") }
                }
                UpdateState.Checking -> CircularProgressIndicator(Modifier.size(22.dp))
                UpdateState.UpToDate, UpdateState.Idle ->
                    OutlinedButton(onClick = { vm.checkForUpdate(silent = false) }) { Text("Check for updates") }
            }
        }
    }
}
