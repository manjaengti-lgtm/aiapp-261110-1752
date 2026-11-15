package com.arin.civicreport.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arin.civicreport.model.Authority
import com.arin.civicreport.model.AuthorityLevel
import com.arin.civicreport.ui.AppScaffold
import com.arin.civicreport.ui.ErrorState
import com.arin.civicreport.viewmodel.ReportViewModel

@Composable
fun AuthoritiesScreen(vm: ReportViewModel, onBack: () -> Unit, onContinue: () -> Unit) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    AppScaffold(screenTitle = "Who will receive it", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Place of the report", style = MaterialTheme.typography.titleMedium)
                            Text(draft.location.oneLine, style = MaterialTheme.typography.bodyMedium)
                            val matched = listOfNotNull(draft.matchedDistrict, draft.matchedState)
                            Text(
                                if (matched.isNotEmpty()) "Offices matched for: ${matched.joinToString(", ")}"
                                else "No district matched – showing state and national offices.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Problems: ${draft.types.joinToString(", ") { it.shortLabel }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (draft.directoryDate.isNotBlank()) {
                                Text(
                                    "Contacts taken from official sites, checked ${draft.directoryDate}.",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (draft.searchingAuthorities) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp))
                            Spacer(Modifier.padding(6.dp))
                            Text("Matching the responsible offices…")
                        }
                    }
                }

                draft.loadError?.let { message ->
                    item { ErrorState(message) { vm.searchAuthorities() } }
                }

                if (draft.authorities.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${draft.selectedAuthorityIds.size} of ${draft.authorities.size} selected",
                                style = MaterialTheme.typography.labelLarge
                            )
                            TextButton(onClick = {
                                vm.selectAllAuthorities(
                                    draft.selectedAuthorityIds.size != draft.authorities.size
                                )
                            }) {
                                Text(
                                    if (draft.selectedAuthorityIds.size == draft.authorities.size) "Clear all"
                                    else "Select all"
                                )
                            }
                        }
                    }
                }

                AuthorityLevel.entries.sortedBy { it.order }.forEach { level ->
                    val group = draft.authorities.filter { it.level == level }
                    if (group.isNotEmpty()) {
                        item(key = "h-${level.name}") {
                            Column(Modifier.padding(top = 6.dp)) {
                                Text(
                                    level.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (level == AuthorityLevel.JUDICIARY) {
                                    Text(
                                        "Courts take petitions, not ordinary complaints, so these start unticked. Tick them to send a letter petition with your evidence.",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        items(group, key = { it.id }) { authority ->
                            AuthorityCard(
                                authority = authority,
                                checked = authority.id in draft.selectedAuthorityIds,
                                onToggle = { vm.toggleAuthority(authority.id) },
                                onEmail = { vm.sendHelper.openUrl("mailto:$it") },
                                onCall = { vm.sendHelper.dial(it) },
                                onOpen = { vm.sendHelper.openUrl(it) }
                            )
                        }
                    }
                }

                item {
                    OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.padding(4.dp))
                        Text("Add an office that is not listed")
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            Column(Modifier.padding(16.dp)) {
                Button(
                    onClick = onContinue,
                    enabled = draft.selectedAuthorityIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Compose and send")
                }
                TextButton(onClick = { vm.searchAuthorities() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Search again")
                }
                Text(
                    "Sending opens your mail or SMS app with everything filled in. Offices without email are reached through their portal, phone or post.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAdd) {
        AddOfficeDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, email, phone, level ->
                vm.addCustomAuthority(name, email, phone, level)
                showAdd = false
            }
        )
    }
}

@Composable
private fun AuthorityCard(
    authority: Authority,
    checked: Boolean,
    onToggle: () -> Unit,
    onEmail: (String) -> Unit,
    onCall: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    OutlinedCard(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(authority.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    authority.category,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                authority.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                val phones = listOfNotNull(authority.phone) + authority.otherPhones
                if (phones.isNotEmpty()) {
                    Text("Phone: ${phones.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
                }
                authority.fax?.let { Text("Fax: $it", style = MaterialTheme.typography.bodyMedium) }
                authority.address?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                authority.note?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column {
                authority.email?.let { mail ->
                    IconButton(onClick = { onEmail(mail) }) { Icon(Icons.Filled.Email, contentDescription = "Email") }
                }
                (authority.phone ?: authority.otherPhones.firstOrNull())?.let { phone ->
                    IconButton(onClick = { onCall(phone) }) { Icon(Icons.Filled.Phone, contentDescription = "Call") }
                }
                authority.url?.let { url ->
                    IconButton(onClick = { onOpen(url) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open website or portal")
                    }
                }
            }
        }
    }
}

@Composable
private fun AddOfficeDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, email: String, phone: String, level: AuthorityLevel) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(AuthorityLevel.LOCAL) }
    val valid = name.isNotBlank() && (email.contains('@') || phone.count { it.isDigit() } >= 3)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an office") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Office name") }, singleLine = true)
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Text("Level", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AuthorityLevel.LOCAL, AuthorityLevel.DISTRICT, AuthorityLevel.STATE).forEach { l ->
                        FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, email, phone, level) }, enabled = valid) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
