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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.srctool.sinew.devtools.DevTools
import com.srctool.sinew.devtools.NetworkRecord
import com.srctool.sinew.devtools.RecordedBody
import io.ktor.http.Url
import kotlinx.coroutines.launch

/** Methods that only read: a replay of one runs on tap. Anything else asks first. */
private val SafeMethods = setOf("GET", "HEAD", "OPTIONS")

/** The Network Monitor: the redacted records, newest first. */
@Composable
internal fun NetworkMonitorPage() {
    val records by DevTools.network.records.collectAsState()
    val navigator = LocalNavigator.current
    if (records.isEmpty()) return EmptyText("No requests yet")
    LazyColumn(Modifier.fillMaxSize().testTag("network-list")) {
        items(records, key = { it.id }) { record ->
            ListItem(
                headlineContent = { Text("${record.method} ${record.pathForList()}", maxLines = 1) },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(record.summary(), style = MaterialTheme.typography.bodySmall)
                        if (record.isReplay) { Gap(); Chip("replay") }
                        record.cacheSource?.let { Gap(); Chip("cache · $it") }
                    }
                },
                modifier = Modifier
                    .clickable { navigator.push(Screen("${record.method} ${record.pathForList()}") { NetworkDetailPage(record) }) }
                    .testTag("network-record:${record.id}"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
internal fun NetworkMonitorActions() {
    TextButton(onClick = { DevTools.network.clear() }, modifier = Modifier.testTag("network-clear")) { Text("Clear") }
}

/** One record: the redacted exchange, Copy, Copy as cURL, and Replay. */
@Composable
internal fun NetworkDetailPage(record: NetworkRecord) {
    val clipboard = rememberClipboard()
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val canReplay = DevTools.canReplay(record)

    fun replay() {
        scope.launch {
            note = try {
                DevTools.replay(record)
                "Replayed: the result is at the top of the list"
            } catch (e: IllegalStateException) {
                e.message
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ButtonRow {
            OutlinedButton(onClick = { clipboard.copy(record.asText()); note = "Copied" }, modifier = Modifier.testTag("network-copy")) {
                Text("Copy")
            }
            Gap()
            OutlinedButton(onClick = { clipboard.copy(record.asCurl()); note = "Copied as cURL" }, modifier = Modifier.testTag("network-curl")) {
                Text("Copy as cURL")
            }
            Gap()
            OutlinedButton(
                onClick = { if (record.method in SafeMethods) replay() else asking = true },
                enabled = canReplay,
                modifier = Modifier.testTag("network-replay"),
            ) { Text("Replay") }
        }
        if (!canReplay) {
            Text(
                "Can't replay: ${record.whyNotReplayable()}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("network-replay-reason"),
            )
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).testTag("network-note"))
        }
        Mono(record.asText(), Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).testTag("network-text"))
    }
    if (asking) {
        ConfirmDialog(
            title = "Send ${record.method} again?",
            text = "Send ${record.method} ${record.pathForList()} again? It may repeat what it did.",
            confirm = "Send",
            onConfirm = { asking = false; replay() },
            onDismiss = { asking = false },
        )
    }
}

private fun NetworkRecord.pathForList(): String = runCatching { Url(url).encodedPath }.getOrDefault(url).ifEmpty { url }

private fun NetworkRecord.summary(): String = listOfNotNull(
    status?.toString() ?: errorType ?: "no response",
    duration?.let { "${it.inWholeMilliseconds} ms" },
    runCatching { Url(url).host }.getOrNull()?.takeIf { it.isNotEmpty() },
).joinToString(" · ")

/** Why a record can't be sent again, in the page's words. */
internal fun NetworkRecord.whyNotReplayable(): String = when {
    fromCache -> "it was served from the cache"
    !replayable && (requestBody is RecordedBody.Binary || (requestBody as? RecordedBody.Text)?.truncated == true) ->
        "its body wasn't kept whole"
    !replayable -> "a secret in it was hidden"
    else -> "its client is closed, or the record was cleared"
}
