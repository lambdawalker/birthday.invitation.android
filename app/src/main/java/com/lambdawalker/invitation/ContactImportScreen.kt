package com.lambdawalker.invitation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ContactImportScreen(
    api: Api,
    existingPhones: List<String>,
    onDone: (String) -> Unit,
    onUnauthorized: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var drafts by remember { mutableStateOf<List<ContactDraft>>(emptyList()) }
    var review by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("+52") }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Map<String, ContactImportResult>>(emptyMap()) }
    var completed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it
        denied = !it
    }
    val known = remember(existingPhones) { existingPhones.mapNotNull { ContactNumbers.normalize(it) }.toSet() }

    LaunchedEffect(permission, reload) {
        if (!permission) return@LaunchedEffect
        loading = true
        try {
            contacts = PhoneContacts.load(context.contentResolver)
            note = if (contacts.isEmpty()) "No contacts with phone numbers were found." else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note = "Unable to read contacts. Check permission in Settings, then try again."
        } finally {
            loading = false
        }
    }
    fun summary(): String {
        val added = results.values.count { it.status == "added" }
        val existing = results.values.count { it.status == "existing" }
        val unresolved = drafts.size - results.values.count { it.status in listOf("added", "existing") }
        return "$added families added · $existing existing numbers skipped · $unresolved not confirmed. Review the roster before sending."
    }
    fun close() { onDone(if (results.isNotEmpty()) summary() else "Contact selection closed") }
    BackHandler(enabled = true) { if (!saving) { if (review && !completed) review = false else close() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(18.dp)) {
        Text(if (review) "Review selected contacts" else "Add from contacts", style = MaterialTheme.typography.headlineSmall)
        Text("Each selected person represents a family. Only selected names, numbers and optional emails are added to the shared roster.",
            style = MaterialTheme.typography.bodySmall)
        if (!permission) {
            Spacer(Modifier.height(20.dp))
            Text("Allow Contacts access to choose several people at once. You can keep using the app without this permission.")
            if (denied) Text("Permission was not granted. You can enable Contacts in App settings.")
            Button(onClick = { permissionRequest.launch(Manifest.permission.READ_CONTACTS) }) { Text("Allow Contacts access") }
            if (denied) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                }) { Text("Open App settings") }
                TextButton(onClick = {
                    permission = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                    reload++
                }) { Text("Check permission again") }
            }
            TextButton(onClick = { close() }) { Text("Back to roster") }
            return@Column
        }
        if (note.isNotEmpty()) Text(note, Modifier.padding(vertical = 8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!review) {
            OutlinedTextField(query, { query = it }, label = { Text("Search name or number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(selected.size.toString() + " selected (maximum 100)", style = MaterialTheme.typography.bodySmall)
            val visible = contacts.filter { it.name.contains(query, true) || it.phones.any { n -> n.contains(query) } }
            Row {
                TextButton(onClick = { selected = (selected + visible.map { it.id }).take(100).toSet() }, enabled = !loading) { Text("Select shown") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                TextButton(onClick = { reload++ }, enabled = !loading) { Text("Reload") }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.id }) { contact ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp)) {
                            Checkbox(checked = contact.id in selected,
                                enabled = contact.id in selected || selected.size < 100,
                                onCheckedChange = { checked -> selected = if (checked) selected + contact.id else selected - contact.id })
                            Column {
                                Text(contact.name.ifBlank { "Unnamed contact" })
                                Text(contact.phones.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                if (contact.phones.any { ContactNumbers.normalize(it) in known })
                                    Text("A number is already in the roster", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Row {
                TextButton(onClick = { close() }) { Text("Cancel") }
                Button(onClick = {
                    val previous = drafts.associateBy { it.id }
                    drafts = contacts.filter { it.id in selected }.map {
                        previous[it.id] ?: ContactDraft(it.id, it.name, if (it.phones.size == 1) it.phones.first() else "")
                    }
                    results = emptyMap()
                    review = true
                    completed = false
                    note = ""
                }, enabled = selected.isNotEmpty() && !loading) { Text("Review " + selected.size + " contacts") }
            }
        } else {
            val duplicates = ContactNumbers.duplicatePhones(drafts, countryCode)
            val valid = drafts.isNotEmpty() && drafts.all {
                ContactNumbers.validName(it.name) && ContactNumbers.normalize(it.phone, countryCode) != null &&
                    (it.email.isBlank() || android.util.Patterns.EMAIL_ADDRESS.matcher(it.email.trim()).matches())
            }
            OutlinedTextField(countryCode, { countryCode = it.trim() }, label = { Text("Country code for local 10-digit numbers") },
                supportingText = { Text("Mexico: +52 · US/Canada: +1. Explicit + numbers keep their country code.") },
                enabled = !saving && results.isEmpty(), singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(drafts, key = { it.id }) { draft ->
                    val result = results[draft.id]
                    val editable = !saving && result?.status !in listOf("added", "existing")
                    val normalized = ContactNumbers.normalize(draft.phone, countryCode)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            OutlinedTextField(draft.name, { value -> drafts = drafts.map { if (it.id == draft.id) it.copy(name = value) else it } },
                                label = { Text("Head of family") }, enabled = editable,
                                isError = !ContactNumbers.validName(draft.name), modifier = Modifier.fillMaxWidth())
                            val phoneChoices = contacts.find { it.id == draft.id }?.phones.orEmpty()
                            if (phoneChoices.size > 1 && editable) {
                                Text("Choose one phone number:")
                                phoneChoices.forEach { phone ->
                                    Row {
                                        RadioButton(selected = draft.phone == phone, onClick = {
                                            drafts = drafts.map { if (it.id == draft.id) it.copy(phone = phone) else it }
                                        })
                                        Text(phone, Modifier.padding(top = 12.dp))
                                    }
                                }
                            }
                            OutlinedTextField(draft.phone, { value -> drafts = drafts.map { if (it.id == draft.id) it.copy(phone = value) else it } },
                                label = { Text("Phone number") }, enabled = editable, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                isError = normalized == null, modifier = Modifier.fillMaxWidth())
                            Text(normalized ?: "Enter a valid number with country code. Extensions are not supported.",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(draft.email, { value -> drafts = drafts.map { if (it.id == draft.id) it.copy(email = value) else it } },
                                label = { Text("Email (optional)") }, enabled = editable, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth())
                            if (normalized in known) Text("Already in roster — will be skipped")
                            else if (normalized in duplicates) Text("Repeated selected number — only one family will be added")
                            if (result != null) Text(when (result.status) {
                                "added" -> "Added and assigned to you"
                                "existing" -> "Already in roster — preserved"
                                else -> result.message.ifBlank { "Not confirmed. Retry this contact." }
                            })
                            if (editable) TextButton(onClick = { drafts = drafts.filter { it.id != draft.id }; results = results - draft.id }) { Text("Remove") }
                        }
                    }
                }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { if (completed) close() else review = false }, enabled = !saving) { Text(if (completed) "Done" else "Back") }
                Button(enabled = !saving && valid && drafts.any { results[it.id]?.status !in listOf("added", "existing") },
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                val remaining = drafts.filter { results[it.id]?.status !in listOf("added", "existing") }
                                for (chunk in remaining.chunked(20)) {
                                    val payload = chunk.map { it.copy(phone = ContactNumbers.normalize(it.phone, countryCode)!!) }
                                    val response = api.importContacts(payload)
                                    for (r in response) {
                                        if (r.index in chunk.indices) results = results + (chunk[r.index].id to r)
                                    }
                                }
                                completed = true
                                note = summary()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: ApiException) {
                                if (e.status == 401) onUnauthorized()
                                else note = "Import interrupted. Some contacts may already be added. Retry safely; existing numbers will be skipped."
                            } catch (e: Exception) {
                                note = "Connection interrupted. Retry safely; existing numbers will be skipped."
                            } finally {
                                saving = false
                            }
                        }
                    }) { Text(if (saving) "Adding…" else if (results.isEmpty()) "Add to roster" else "Retry remaining") }
            }
        }
    }
}
