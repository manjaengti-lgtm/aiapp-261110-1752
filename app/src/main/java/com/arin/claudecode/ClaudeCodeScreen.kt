package com.arin.claudecode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.design.LocalArinExtras
import com.arin.design.MarkdownText

/**
 * Claude Code as a chat. The brain is the person's own custom API (only a URL and key are entered; the
 * models are listed for them); the tool is Anthropic's Claude Code, running in Arin's Linux. This API is
 * kept separate from the chat tab's APIs.
 */
@Composable
fun ClaudeCodeScreen(vm: ChatViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshClaudeCode() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // ---- top bar: like the chat tab — title with the model underneath ----
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("Claude Code", style = MaterialTheme.typography.titleLarge)
                if (vm.ccApiConnected) ModelPicker(vm)
            }
            if (vm.ccInstalled && vm.ccApiConnected) {
                IconButton(onClick = { vm.newCcChat() }, enabled = !vm.ccRunning) { Icon(Icons.Filled.Edit, contentDescription = "New chat") }
                IconButton(onClick = { showSettings = !showSettings }) { Icon(Icons.Filled.Settings, contentDescription = "API settings") }
            }
        }

        when {
            !vm.ccInstalled -> Setup { InstallCard(vm) }
            !vm.ccApiConnected || showSettings -> Setup {
                ConnectCard(vm, onDone = { showSettings = false })
            }
            else -> {
                Conversation(vm, Modifier.weight(1f))
                Composer(vm)
            }
        }
    }
}

@Composable
private fun ModelPicker(vm: ChatViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clickable(enabled = !vm.ccRunning) { open = true }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                vm.ccModel.ifBlank { "Pick a model" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Change model", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vm.ccModels.forEach { id ->
                DropdownMenuItem(
                    text = { Text((if (id == vm.ccModel) "✓ " else "") + id) },
                    onClick = { vm.selectCcModel(id); open = false },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Setup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun InstallCard(vm: ChatViewModel) {
    Card {
        Text("Install Claude Code", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Installs Anthropic's Claude Code in Arin's built-in Linux (about 100 MB download, 400 MB space). " +
                "Your own API is the brain; Claude Code is the tool that reads, writes and runs things.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.installClaudeCode() }, enabled = !vm.ccBusy) {
            Text(if (vm.ccBusy) "Installing…" else "Install Claude Code")
        }
        if (vm.ccBusy || vm.ccLog.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            vm.ccLog.takeLast(10).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConnectCard(vm: ChatViewModel, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var base by rememberSaveable { mutableStateOf(vm.settings.ccCustomBase.removeSuffix("/anthropic")) }
    var key by rememberSaveable { mutableStateOf(vm.settings.ccCustomKey) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface)
    Card {
        Text("Connect your API (the brain)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Enter only your API's address and key. Arin finds its models for you. This is used only by Claude Code " +
                "and doesn't appear in the chat tab.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            base, { base = it }, label = { Text("API URL") }, placeholder = { Text("https://api.deepseek.com") },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            key, { key = it }, label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), colors = fieldColors, modifier = Modifier.fillMaxWidth(),
        )
        vm.ccConnectError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.connectCcApi(base, key) }, enabled = !vm.ccConnecting && base.isNotBlank() && key.isNotBlank()) {
                Text(if (vm.ccConnecting) "Connecting…" else "Connect")
            }
            if (vm.ccConnecting) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (vm.ccApiConnected) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDone) { Text("Back to chat") }
            }
        }
        if (vm.ccApiConnected) {
            Spacer(Modifier.height(6.dp))
            Text("Connected ✓ ${vm.ccModels.size} models", color = colors.primary, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.disconnectCcApi() }) { Text("Disconnect") }
        }
    }
}

@Composable
private fun Conversation(vm: ChatViewModel, modifier: Modifier) {
    val list = rememberLazyListState()
    val last = vm.ccMessages.lastOrNull()
    LaunchedEffect(vm.ccMessages.size, last?.text?.length, last?.steps?.size) {
        if (vm.ccMessages.isNotEmpty()) list.animateScrollToItem(vm.ccMessages.lastIndex)
    }
    if (vm.ccMessages.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Ask Claude Code to build, fix or explain something.\nIt works in your Linux files folder and can run commands.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(vm.ccMessages) { _, m -> if (m.fromUser) UserBubble(m.text) else AssistantMessage(m) }
    }
}

@Composable
private fun UserBubble(text: String) {
    val extras = LocalArinExtras.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = extras.userBubble, contentColor = extras.onUserBubble, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AssistantMessage(m: ChatViewModel.CcMessage) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        // The steps Claude Code took (commands, files), like a work log.
        if (m.steps.isNotEmpty()) {
            Surface(color = colors.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    m.steps.takeLast(8).forEach {
                        Text("› $it", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (m.steps.size > 8) Text("…and ${m.steps.size - 8} earlier steps", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (m.text.isNotBlank()) {
            if (m.error) Text(m.text, color = colors.error, style = MaterialTheme.typography.bodyMedium)
            else MarkdownText(m.text)
        }
        if (m.running) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (m.steps.isEmpty() && m.text.isBlank()) "Thinking…" else "Working…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** The same message box as the chat tab. */
@Composable
private fun Composer(vm: ChatViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(28.dp)) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Message Claude Code") },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (vm.ccRunning) {
                    FilledIconButton(
                        onClick = { vm.stopClaudeCode() },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.onSurface),
                    ) {
                        Surface(color = colors.surface, shape = RoundedCornerShape(2.dp), modifier = Modifier.size(12.dp)) {}
                    }
                } else {
                    FilledIconButton(
                        onClick = { vm.askClaudeCode(text); text = "" },
                        enabled = text.isNotBlank(),
                        shape = CircleShape,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
