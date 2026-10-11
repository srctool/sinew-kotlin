package com.srctool.sinew.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

internal actual fun platformEngine(): HttpClientEngineFactory<*> = Js
