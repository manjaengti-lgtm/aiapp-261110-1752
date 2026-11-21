package com.arin.claudecode

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel

/**
 * "Claude Code" tab: run Anthropic's Claude Code in Arin's Linux, signed in with the person's own
 * Claude Pro/Max subscription. Install → connect → ask; answers come back here.
 */
@Composable
fun ClaudeCodeScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    LaunchedEffect(Unit) { vm.refreshClaudeCode() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Claude Code", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Run Anthropic's Claude Code inside Arin's Linux, signed in with your own Claude Pro/Max account. " +
                    "No API key, no extra billing — it uses your subscription.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Step 1: install ----
            Card {
                Text("1 · Install", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (vm.ccInstalled) {
                    Text("Claude Code is installed ✓", color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(
                        "Installs Claude Code in Arin's built-in Linux (sets up Linux + Node first if needed).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.installClaudeCode() }, enabled = !vm.ccBusy) {
                        Text("Install Claude Code")
                    }
                }
            }

            // ---- Step 2: connect ----
            if (vm.ccInstalled) {
                Card {
                    Text("2 · Connect your Claude account", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    if (vm.ccConnected) {
                        Text("Connected with your Claude subscription ✓", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.ccLogout() }) { Text("Disconnect") }
                    } else {
                        Text(
                            "Sign in with the Google/Gmail account your Pro plan is on. A browser opens for the login; " +
                                "the connection usually completes on its own. If it shows a code, paste it below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.startCcLogin() }, enabled = !vm.ccBusy) { Text("Log in with Claude") }

                        vm.ccLoginUrl?.let { url ->
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { uri.openUri(url) }) { Text("Open login page") }
                            Spacer(Modifier.height(8.dp))
                            var code by remember { mutableStateOf("") }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = code,
                                    onValueChange = { code = it },
                                    placeholder = { Text("Paste code (only if shown)") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(enabled = code.isNotBlank(), onClick = { vm.pasteCcCode(code); code = "" }) { Text("Submit") }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        var manual by remember { mutableStateOf("") }
                        Text("Or paste a token from `claude setup-token`:", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = manual,
                                onValueChange = { manual = it },
                                placeholder = { Text("sk-ant-oat01-…") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(enabled = manual.isNotBlank(), onClick = { vm.saveCcToken(manual); manual = "" }) { Text("Save") }
                        }
                    }
                }
            }

            // ---- Step 3: ask ----
            if (vm.ccConnected) {
                Card {
                    Text("3 · Ask Claude Code", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    var prompt by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = { Text("Ask or tell Claude Code to do something…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.askClaudeCode(prompt) }, enabled = !vm.ccBusy && prompt.isNotBlank()) { Text("Run") }
                    if (vm.ccOutput.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(vm.ccOutput, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }

            // ---- Progress / log ----
            if (vm.ccBusy || vm.ccLog.isNotEmpty()) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.ccBusy) { CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text("Status", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    vm.ccLog.takeLast(12).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Text(
                "Note: Claude Code runs on the phone through Arin's Linux, so it can be slow, and Pro plans have usage limits. " +
                    "The one browser step is needed to sign in; everything else stays in Arin.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
