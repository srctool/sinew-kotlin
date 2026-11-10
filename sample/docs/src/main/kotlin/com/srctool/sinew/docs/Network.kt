package com.srctool.sinew.docs

import com.srctool.sinew.models.Result
import com.srctool.sinew.network.SinewConfig
import com.srctool.sinew.network.SinewHttp
import com.srctool.sinew.network.TokenSource
import com.srctool.sinew.network.processApiCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post

// region client
fun ordersClient(tokenSource: TokenSource): HttpClient {
    val config = SinewConfig(
        baseUrl = "https://api.example.com/v1/",
        locale = { "en" },
        anonymousPaths = setOf("/auth/login"),
    )
    return SinewHttp.client(config, tokenSource)
}
// endregion

// region api-call
// Paths are relative to baseUrl, and every failure comes back as a typed SinewException.
suspend fun cancel(client: HttpClient, id: String): Result<Order> =
    processApiCall("ORD", "CANCEL") { client.post("orders/$id/cancel").body<OrderEnvelope>() }
// endregion
