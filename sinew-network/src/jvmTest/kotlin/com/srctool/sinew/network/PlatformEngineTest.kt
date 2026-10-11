package com.srctool.sinew.network

import io.ktor.client.engine.okhttp.OkHttp
import kotlin.test.Test
import kotlin.test.assertSame

class PlatformEngineTest {
    @Test
    fun theJvmUsesOkHttp() = assertSame(OkHttp, platformEngine())
}
