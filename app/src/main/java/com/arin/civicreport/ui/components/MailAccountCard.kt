// === ABOUT: One-time setup of the person's own mail account so the app can send complaints by itself ===
package com.arin.civicreport.ui.components

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import com.arin.civicreport.data.GoogleMailer
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arin.civicreport.data.MailAccount
import com.arin.civicreport.data.MailProvider
import com.arin.civicreport.viewmodel.ReportViewModel
import kotlinx.coroutines.launch

@Composable
fun MailAccountCard(vm: ReportViewModel, startOpen: Boolean = false) {
    val saved by vm.mailAccount.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(startOpen || !saved.isSet) }
    var provider by remember { mutableStateOf(saved.provider) }
    var email by remember { mutableStateOf(saved.email) }
    var password by remember { mutableStateOf(saved.password) }
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var ssl by remember { mutableStateOf(saved.ssl) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var googleBusy by remember { mutableStateOf(false) }
    var showOther by remember { mutableStateOf(saved.isSet && !saved.google) }
    val googleApproval = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            val auth = if (res.resultCode == android.app.Activity.RESULT_OK) vm.googleResult(res.data)
            else GoogleMailer.Auth.Failed("Sign-in was cancelled.")
            status = vm.finishGoogleSignIn(auth)?.let { "Not signed in: $it" }
            googleBusy = false
            if (status == null) editing = false
        }
    }

    fun current() = MailAccount(provider = provider, email = email.trim(), password = password.replace(" ", ""), host = host.trim(), port = port.toIntOrNull() ?: 587, ssl = ssl)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your email account for sending", style = MaterialTheme.typography.titleMedium)
            if (!editing && saved.isSet) {
                Text(
                    (if (saved.google) "Signed in with Google. " else "") +
                        "Complaints are sent automatically from ${saved.email}. Replies from offices come to this inbox.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { editing = true }) { Text("Change") }
                return@Column
            }
            Text(
                "Set this up once. After that the app emails every office by itself — no other app opens.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                enabled = !googleBusy,
                onClick = {
                    googleBusy = true; status = null
                    scope.launch {
                        when (val auth = vm.startGoogleSignIn()) {
                            is GoogleMailer.Auth.NeedsApproval ->
                                googleApproval.launch(IntentSenderRequest.Builder(auth.intent.intentSender).build())
                            else -> {
                                status = vm.finishGoogleSignIn(auth)?.let { "Not signed in: $it" }
                                googleBusy = false
                                if (status == null) editing = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (googleBusy) CircularProgressIndicator(Modifier.size(16.dp)) else Text("Continue with Google")
            }
            Text(
                "Pick the Gmail account already on this phone and tap Allow. No password needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            status?.let {
                Text(it, color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            TextButton(onClick = { showOther = !showOther }) {
                Text(if (showOther) "Hide other email options" else "Use Yahoo, Zoho, Rediff or another email instead")
            }
            if (!showOther) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MailProvider.entries.take(3).forEach { p ->
                    FilterChip(selected = provider == p, onClick = {
                        provider = p; if (p != MailProvider.CUSTOM) { host = p.host; port = p.port.toString(); ssl = p.ssl }
                    }, label = { Text(p.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MailProvider.entries.drop(3).forEach { p ->
                    FilterChip(selected = provider == p, onClick = {
                        provider = p; if (p != MailProvider.CUSTOM) { host = p.host; port = p.port.toString(); ssl = p.ssl }
                    }, label = { Text(p.label) })
                }
            }
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    val g = MailProvider.guess(it.trim())
                    if (g != MailProvider.CUSTOM && g != provider) {
                        provider = g; host = g.host; port = g.port.toString(); ssl = g.ssl
                    }
                },
                label = { Text("Your email address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (provider == MailProvider.REDIFF || provider == MailProvider.CUSTOM) "Password" else "App password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Text(provider.help, style = MaterialTheme.typography.bodySmall)
            if (provider.helpUrl.isNotBlank()) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.helpUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Create the app password (one time)") }
            }
            if (provider == MailProvider.CUSTOM) {
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("SMTP server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("SSL (465)")
                    Switch(checked = ssl, onCheckedChange = { ssl = it })
                }
            }
            val valid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() && password.isNotBlank() && host.isNotBlank()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = valid && !busy,
                    onClick = {
                        busy = true; status = null
                        scope.launch {
                            val err = vm.testMailAccount(current())
                            busy = false
                            if (err == null) {
                                vm.saveMailAccount(current())
                                status = "Connected and saved."
                                editing = false
                            } else status = err
                        }
                    }
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp)) else Text("Check and save")
                }
                if (saved.isSet) {
                    TextButton(onClick = { vm.clearMailAccount(); email = ""; password = ""; editing = true }) { Text("Remove") }
                }
            }
        }
    }
}
