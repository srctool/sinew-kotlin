@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.testing

import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.errorOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ControlledFetchTest {
    @Test
    fun aCallWaitsUntilTheTestAnswersIt() = runTest {
        val fetch = ControlledFetch<String>()
        val result = async { fetch(1, "q") }
        runCurrent()
        assertEquals(1, fetch.calls.single().key)
        assertEquals("q", fetch.calls.single().query)
        assertTrue(result.isActive)
        fetch.succeed(0, listOf("a"))
        assertEquals(Result.Success(PagingDomain(listOf("a"))), result.await())
    }

    @Test
    fun callsAreAnsweredByIndexInAnyOrder() = runTest {
        val fetch = ControlledFetch<String>()
        val first = async { fetch(1, "") }
        val second = async { fetch(2, "") }
        runCurrent()
        val offline = NoInternetConnectionException("ORD", ExceptionLayer.Repository, "GO")
        fetch.fail(1, offline)
        fetch.succeed(0, listOf("a"))
        assertEquals(offline, second.await().errorOrNull)
        assertEquals(Result.Success(PagingDomain(listOf("a"))), first.await())
    }

    @Test
    fun aPendingCallFailsTheTest() = runTest {
        val fetch = ControlledFetch<String>()
        val call = launch { fetch(1, "") }
        runCurrent()
        assertFailsWith<AssertionError> { fetch.assertAllCompleted() }
        call.cancel()
    }

    @Test
    fun aCancelledCallIsRecordedAndDoesNotCountAsPending() = runTest {
        val fetch = ControlledFetch<String>()
        val call = launch { fetch(1, "") }
        runCurrent()
        call.cancel(); runCurrent()
        assertTrue(fetch.calls.single().cancelled)
        fetch.assertAllCompleted()
    }

    @Test
    fun answeringTwiceOrAMissingCallIsAnError() = runTest {
        val fetch = ControlledFetch<String>()
        assertFailsWith<IllegalStateException> { fetch.succeed(0, emptyList()) }
        val call = async { fetch(1, "") }
        runCurrent()
        fetch.succeed(0, emptyList())
        assertFailsWith<IllegalStateException> { fetch.succeed(0, emptyList()) }
        call.await()
    }
}
