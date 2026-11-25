package com.arin.analyst

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The **Talk to Claude** switch. It sits directly under "Activate reverse engineering" in the chat's ⋮
 * menu, is per chat, and starts off again on a new chat.
 *
 * It is independent of reverse engineering: REA on with this off is a valid state (the tools without
 * the phone line), and neither turns the other on.
 */
@Composable
fun TalkToClaudeRow(chatId: String?, reaOn: Boolean, onRepair: () -> Unit = {}) {
    val context = LocalContext.current
    val on = AnalystLane.enabled(chatId)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = chatId != null) { AnalystLane.set(context, chatId, !on) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Talk to Claude", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        on && reaOn -> "Claude can be asked for a second opinion, on top of the tools."
                        on -> "Claude can be asked for a second opinion in this chat."
                        reaOn -> "The tools answer alone; Claude isn't phoned."
                        else -> "Off. This chat answers alone and says so."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = { AnalystLane.set(context, chatId, it) }, enabled = chatId != null)
        }
        if (on) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "One question per call, no memory between calls.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    AnalystSetup.restartAsync(context)
                    onRepair()
                }) { Text("Reconnect") }
            }
        }
        HorizontalDivider()
    }
}

/** Shown in Settings: put the Claude connection file back the way it was and lift the line again. */
@Composable
fun AnalystRepairRow() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Claude connection", style = MaterialTheme.typography.bodyLarge)
        Text(
            "If other tabs stop reaching Claude, restore the saved connection file and reconnect.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = { AnalystSetup.restoreAsync(context) }) { Text("Restore saved connection") }
            TextButton(onClick = { AnalystSetup.restartAsync(context) }) { Text("Reconnect") }
        }
    }
}
