package com.srctool.sinew.devtools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.srctool.sinew.devtools.DevTools
import com.srctool.sinew.devtools.ExceptionRecord
import com.srctool.sinew.devtools.FaultRule
import com.srctool.sinew.devtools.Redaction
import com.srctool.sinew.storage.InspectableStore
import com.srctool.sinew.storage.KeyValueStore
import com.srctool.sinew.storage.SecureStore
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

// Exception Log

@Composable
internal fun ExceptionLogPage() {
    val rows by DevTools.exceptions.records.collectAsState()
    val navigator = LocalNavigator.current
    if (rows.isEmpty()) return EmptyText("No exceptions yet")
    LazyColumn(Modifier.fillMaxSize().testTag("exception-list")) {
        items(rows, key = { it.id }) { row ->
            ListItem(
                headlineContent = { Text(row.type + if (row.count > 1) " ×${row.count}" else "") },
                supportingContent = { Text(listOfNotNull(row.code, row.message.takeIf { it.isNotEmpty() }).joinToString(" · "), maxLines = 2) },
                trailingContent = { Text(row.source.name, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.clickable { navigator.push(Screen(row.type) { ExceptionDetailPage(row) }) }
                    .testTag("exception-row:${row.id}"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
internal fun ExceptionLogActions() {
    TextButton(onClick = { DevTools.exceptions.clear() }, modifier = Modifier.testTag("exception-clear")) { Text("Clear") }
}

@Composable
private fun ExceptionDetailPage(row: ExceptionRecord) {
    val clipboard = rememberClipboard()
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ButtonRow {
            OutlinedButton(onClick = { clipboard.copy(row.asText()); copied = true }, modifier = Modifier.testTag("exception-copy")) { Text("Copy") }
            if (copied) { Gap(); Text("Copied", style = MaterialTheme.typography.bodySmall) }
        }
        Mono(row.asText(), Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp))
    }
}

// Stores

/** A key and its value as the page shows it. */
private class Row(val key: String, val value: String?)

/** The Key-Value Store page: values shown; editing and deleting ask first. */
@Composable
internal fun KeyValueStorePage(store: KeyValueStore) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<Row>?>(null) }
    var editing by remember { mutableStateOf<Row?>(null) }
    var deleting by remember { mutableStateOf<Row?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store, version) { rows = store.keys().map { Row(it, store.read(it)) } }

    StoreList(rows, empty = "${store.name} is empty") { row ->
        ListItem(
            headlineContent = { Text(row.key) },
            supportingContent = { Mono(row.value ?: "null") },
            trailingContent = {
                TextButton(onClick = { deleting = row }, modifier = Modifier.testTag("kv-delete:${row.key}")) { Text("Delete") }
            },
            modifier = Modifier.clickable { editing = row; error = null }.testTag("kv-row:${row.key}"),
        )
    }
    editing?.let { row ->
        var text by remember(row) { mutableStateOf(row.value.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit ${row.key}") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.testTag("kv-edit-field"))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            error = store.writeAs(row.key, text)
                            if (error == null) { editing = null; version++ }
                        }
                    },
                    modifier = Modifier.testTag(CONFIRM_TAG),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }, modifier = Modifier.testTag(DISMISS_TAG)) { Text("Cancel") } },
        )
    }
    deleting?.let { row ->
        ConfirmDialog(
            title = "Delete ${row.key}?",
            text = "This removes it from ${store.name}. The app may behave as if it were never set.",
            confirm = "Delete",
            onConfirm = { deleting = null; scope.launch { store.remove(row.key); version++ } },
            onDismiss = { deleting = null },
        )
    }
}

/** Writes [text] to [key] as the type it already has, or returns why it can't. */
private suspend fun KeyValueStore.writeAs(key: String, text: String): String? {
    when {
        getInt(key) != null -> setInt(key, text.toIntOrNull() ?: return "Not a whole number")
        getBool(key) != null -> setBool(key, text.toBooleanStrictOrNull() ?: return "Not true or false")
        getDouble(key) != null -> setDouble(key, text.toDoubleOrNull() ?: return "Not a number")
        else -> setString(key, text)
    }
    return null
}

/**
 * The Secure Store page: every value **masked** to its last 4 characters and its length, and never copyable, since QA
 * devices get shared and screens get recorded. "Clear all" asks first.
 */
@Composable
internal fun SecureStorePage(store: SecureStore) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<Row>?>(null) }
    LaunchedEffect(store, version) { rows = store.keys().map { Row(it, store.read(it)?.let(Redaction::maskSecret)) } }
    var clearing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ButtonRow {
            Text("Masked: last 4 characters and length. Never copyable.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { clearing = true }, enabled = rows?.isNotEmpty() == true, modifier = Modifier.testTag("secure-clear")) { Text("Clear all") }
        }
        Column(Modifier.weight(1f)) {
            StoreList(rows, empty = "${store.name} is empty") { row ->
                ListItem(
                    headlineContent = { Text(row.key) },
                    supportingContent = { Mono(row.value ?: "null", Modifier.testTag("secure-value:${row.key}")) },
                    modifier = Modifier.testTag("secure-row:${row.key}"),
                )
            }
        }
    }
    if (clearing) {
        ConfirmDialog(
            title = "Clear ${store.name}?",
            text = "This deletes every value in it, the session included. It can't be undone.",
            confirm = "Clear all",
            onConfirm = { clearing = false; scope.launch { store.clearUserData(); version++ } },
            onDismiss = { clearing = false },
        )
    }
}

/** A store the app registered for the Local DB group: read-only. */
@Composable
internal fun InspectableStorePage(store: InspectableStore) {
    var rows by remember { mutableStateOf<List<Row>?>(null) }
    LaunchedEffect(store) { rows = store.keys().map { Row(it, store.read(it)) } }
    StoreList(rows, empty = "${store.name} is empty") { row ->
        ListItem(headlineContent = { Text(row.key) }, supportingContent = { Mono(row.value ?: "null") })
    }
}

@Composable
private fun StoreList(rows: List<Row>?, empty: String, row: @Composable (Row) -> Unit) {
    when {
        rows == null -> EmptyText("Loading…")
        rows.isEmpty() -> EmptyText(empty)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.key }) { row(it); HorizontalDivider() }
        }
    }
}

// Fault Injection

private enum class FaultKind(val label: String) { Status("Status"), Drop("Drop"), Delay("Delay only") }

/** The active rules, each removable, and a form to add one. Rules live in memory only. */
@Composable
internal fun FaultInjectionPage() {
    val rules by DevTools.faults.active.collectAsState()
    var method by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(FaultKind.Status) }
    var status by remember { mutableStateOf("503") }
    var delay by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Rules live in memory only and are gone after a restart.", style = MaterialTheme.typography.bodySmall)
        if (rules.isEmpty()) Text("No rules", modifier = Modifier.padding(vertical = 8.dp))
        rules.forEach { rule ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("fault-rule:${rule.pathPattern}")) {
                Text(rule.describe(), modifier = Modifier.weight(1f))
                TextButton(onClick = { DevTools.faults.remove(rule) }, modifier = Modifier.testTag("fault-remove:${rule.pathPattern}")) {
                    Text("Remove")
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Add a rule", style = MaterialTheme.typography.titleSmall)
        Row {
            listOf(null, "GET", "POST", "PUT", "PATCH", "DELETE").forEach { m ->
                FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m ?: "Any") }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        OutlinedTextField(path, { path = it }, label = { Text("Path, such as /orders or /orders/*") }, modifier = Modifier.fillMaxWidth().testTag("fault-path"))
        Row {
            FaultKind.entries.forEach { k ->
                FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }, modifier = Modifier.padding(end = 4.dp).testTag("fault-kind:${k.name}"))
            }
        }
        if (kind == FaultKind.Status) {
            OutlinedTextField(status, { status = it }, label = { Text("Status") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("fault-status"))
            OutlinedTextField(body, { body = it }, label = { Text("Body (optional)") }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(delay, { delay = it }, label = { Text("Delay in ms (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("fault-delay"))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("fault-error")) }
        OutlinedButton(
            onClick = {
                error = runCatching {
                    DevTools.faults.add(
                        FaultRule(
                            method = method,
                            pathPattern = path.trim(),
                            status = if (kind == FaultKind.Status) status.trim().toIntOrNull() ?: error("Status must be a number") else null,
                            delay = delay.trim().takeIf { it.isNotEmpty() }?.let { (it.toLongOrNull() ?: error("Delay must be a number")).milliseconds },
                            drop = kind == FaultKind.Drop,
                            body = body.takeIf { kind == FaultKind.Status && it.isNotBlank() },
                        ),
                    )
                }.exceptionOrNull()?.message
                if (error == null) path = ""
            },
            modifier = Modifier.padding(top = 8.dp).testTag("fault-add"),
        ) { Text("Add rule") }
    }
}

private fun FaultRule.describe(): String = buildString {
    append(method ?: "Any")
    append(' ')
    append(pathPattern)
    append(" → ")
    append(
        listOfNotNull(
            status?.toString(),
            "drop".takeIf { drop },
            delay?.let { "after ${it.inWholeMilliseconds} ms" },
        ).joinToString(", "),
    )
}

// API Host

/** The app's hosts, one chosen at a time, with the app's own as the reset. Remembered across restarts. */
@Composable
internal fun ApiHostPage() {
    val hosts by DevTools.hosts.hosts.collectAsState()
    val current by DevTools.hosts.current.collectAsState()
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize()) {
        items(listOf<String?>(null) + hosts, key = { it ?: "" }) { host ->
            ListItem(
                headlineContent = { Text(host ?: "The app's own") },
                supportingContent = { if (host == null) Text("Each client's base URL from its config") },
                leadingContent = { RadioButton(selected = current == host, onClick = null) },
                modifier = Modifier
                    .selectable(selected = current == host, onClick = { scope.launch { DevTools.hosts.select(host) } })
                    .testTag("host:${host ?: "own"}"),
            )
            HorizontalDivider()
        }
    }
}

// Changelog

@Composable
internal fun ChangelogPage(text: String) {
    Text(text, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("changelog"))
}
