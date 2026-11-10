package com.srctool.sinew.docs

import com.srctool.sinew.testing.FakeKeyValueStore
import com.srctool.sinew.testing.FakeTokenSource
import com.srctool.sinew.testing.mockHttp
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderTests {
    // region mock-http
    @Test
    fun ordersLoad() = runTest {
        val http = mockHttp {
            on(HttpMethod.Get, "/v1/orders").respond(200, """{"data":{"id":7,"title":"Two coffees"}}""")
        }
        http.client.get("orders")
        assertEquals("/v1/orders", http.requests.single().url.encodedPath)
    }
    // endregion

    // region fakes
    @Test
    fun aFailedReadIsAStorageFailure() = runTest {
        val prefs = FakeKeyValueStore()
        prefs.failNextRead()                      // the next read throws, once
        val tokens = FakeTokenSource()            // counts refreshes and rotates the token
        assertEquals("token-1", tokens.accessToken())
    }
    // endregion
}
