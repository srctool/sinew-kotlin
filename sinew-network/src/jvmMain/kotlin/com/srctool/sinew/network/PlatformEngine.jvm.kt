package com.srctool.sinew.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun platformEngine(): HttpClientEngineFactory<*> = OkHttp
