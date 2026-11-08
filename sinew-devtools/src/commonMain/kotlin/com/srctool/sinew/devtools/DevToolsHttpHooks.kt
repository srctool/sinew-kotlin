package com.srctool.sinew.devtools

import com.srctool.sinew.network.FaultDecision
import com.srctool.sinew.network.HttpExchange
import com.srctool.sinew.network.SinewHttp
import com.srctool.sinew.network.SinewHttpHooks
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

/** Marks a request devtools sends again, so its record says it's a replay. */
internal val ReplayMark = AttributeKey<Unit>("SinewDevToolsReplay")

/** What a replay needs that a redacted record doesn't keep: the client, and the URL before a host override. */
private class ReplayTarget(val client: HttpClient, val requestedUrl: String, val clientHeaders: Set<String>)

/**
 * The hooks devtools hands to `SinewHttp.client(…, hooks = DevTools.httpHooks)`: records every exchange in [recorder],
 * applies [faults] and the [hosts] override, and keeps what a replay needs. A record keeps no client reference itself,
 * and the targets are dropped with the records they belong to.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DevToolsHttpHooks(
    private val recorder: NetworkRecorder,
    private val faults: FaultRules,
    private val hosts: HostOverride,
) : SinewHttpHooks {
    private val clients = AtomicReference<List<HttpClient>>(emptyList())

    /** The clients built with these hooks and not closed yet. */
    internal val attachedClients: List<HttpClient> get() = clients.load()
    private val targets = AtomicReference<Map<Long, ReplayTarget>>(emptyMap())

    override fun attached(client: HttpClient) {
        clients.update { it + client }
    }

    override fun detached(client: HttpClient) {
        clients.update { list -> list - client }
        targets.update { map -> map.filterValues { it.client !== client } }
    }

    override fun hostOverride(): String? = hosts.current.value

    override suspend fun fault(method: String, url: Url): FaultDecision? = faults.match(method, url.encodedPath)?.decision()

    override fun record(exchange: HttpExchange) {
        val record = recorder.record(
            NetworkExchange(
                method = exchange.method,
                url = exchange.url,
                requestHeaders = exchange.requestHeaders,
                requestBody = exchange.requestBody,
                requestBodyStreamed = exchange.requestBodyStreamed,
                status = exchange.status,
                responseHeaders = exchange.responseHeaders,
                responseBody = exchange.responseBody,
                startedAt = exchange.startedAt,
                duration = exchange.duration,
                error = exchange.error,
                clientHeaders = exchange.defaultHeaderNames,
                isReplay = exchange.attributes.contains(ReplayMark),
            ),
        )
        val kept = recorder.records.value.mapTo(mutableSetOf()) { it.id }
        targets.update { map ->
            val alive = map.filterKeys { it in kept }
            if (record.replayable && exchange.client.isOpen()) {
                alive + (record.id to ReplayTarget(exchange.client, exchange.requestedUrl, exchange.defaultHeaderNames))
            } else {
                alive
            }
        }
    }

    /** Whether [record] can be sent again now: it kept everything it needs, and its client is still open. */
    fun canReplay(record: NetworkRecord): Boolean = record.replayable && target(record) != null

    /** [record]'s target, while the recorder still holds the record and its client is open. */
    private fun target(record: NetworkRecord): ReplayTarget? =
        targets.load()[record.id]?.takeIf { it.client.isOpen() && recorder.records.value.any { r -> r.id == record.id } }

    /**
     * Sends [record]'s request again through the client that sent it. The auth layer adds a fresh token and the client
     * its own headers, so nothing secret comes from the record. Faults and the host override apply as for any request,
     * and the result is recorded as a new record marked as a replay. A failure is in that record; it isn't thrown.
     */
    suspend fun replay(record: NetworkRecord) {
        require(record.replayable) { "Record ${record.id} can't be replayed: a secret in it was hidden" }
        val target = target(record)
            ?: throw IllegalStateException("Record ${record.id} can't be replayed: its client is closed or the record was cleared")
        val readded = target.clientHeaders.mapTo(mutableSetOf()) { it.lowercase() } +
            setOf("authorization", "content-type", "content-length")
        val contentType = record.requestHeaders.entries
            .firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }?.value?.firstOrNull()
        try {
            target.client.request(target.requestedUrl) {
                method = HttpMethod.parse(record.method)
                record.requestHeaders.forEach { (name, values) ->
                    if (name.lowercase() !in readded) values.forEach { headers.append(name, it) }
                }
                val body = record.requestBody
                if (body is RecordedBody.Text) {
                    setBody(TextContent(body.text, contentType?.let(ContentType::parse) ?: ContentType.Text.Plain))
                }
                attributes.put(ReplayMark, Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The failure is in the replay's own record.
        }
    }

    /** Devtools' "Expire access token": every client's next request with a token sends an invalid one. */
    fun expireAccessToken() {
        clients.load().forEach(SinewHttp::debugExpireToken)
    }

    /** Whether the client is still attached: [detached] removes it when it closes. */
    private fun HttpClient.isOpen(): Boolean = this in clients.load()
}
