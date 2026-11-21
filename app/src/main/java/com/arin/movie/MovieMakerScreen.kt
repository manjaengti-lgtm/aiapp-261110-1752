package com.arin.movie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel

/**
 * "Movie Maker" tab: paste a script, your own model directs it into scenes, and free tools render a
 * narrated video that stitches together inside Arin's Linux.
 */
@Composable
fun MovieMakerScreen(vm: ChatViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshMovie() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Movie Maker", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Describe a scene or paste a whole script. Your own model breaks it into scenes and writes the " +
                    "narration; free tools make an image and voice for each, and ffmpeg stitches them into one video. " +
                    "Longer scripts make longer films.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var script by remember { mutableStateOf("") }
            OutlinedTextField(
                value = script,
                onValueChange = { script = it },
                placeholder = { Text("e.g. A short film about a lighthouse keeper who befriends a storm…") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
            Button(onClick = { vm.makeMovie(script) }, enabled = !vm.movieBusy && script.isNotBlank()) {
                Text(if (vm.movieBusy) "Making…" else "Make movie")
            }

            if (vm.movieBusy || vm.movieProgress.isNotEmpty() || vm.movieLog.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (vm.movieBusy) { CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text("Progress", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        vm.movieLog.takeLast(8).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (vm.movieProgress.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(vm.movieProgress, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (vm.movieReady) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Your movie is ready 🎬", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = { vm.playMovie() }) { Text("Play") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { vm.shareMovie() }) { Text("Share / Save") }
                        }
                    }
                }
            }

            Text(
                "Note: this is a narrated film built from AI images with motion — not a consistent-character live-action " +
                    "movie, which no free tool can do on a phone. Rendering happens on your phone, so long films can take " +
                    "several minutes; keep Arin open while it works.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
