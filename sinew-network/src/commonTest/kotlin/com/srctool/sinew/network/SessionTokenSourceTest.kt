@file:OptIn(ExperimentalCoroutinesApi::class)

package com.srctool.sinew.network

import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.exception.UnexpectedException
import com.srctool.sinew.models.ExceptionLayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import io.ktor.client.request.get
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class SessionTokenSourceTest {
    private val offline = NoInternetConnectionException("AUTH", ExceptionLayer.Repository, "REFRESH")

    @Test
    fun startHoldsTheAccessTokenAndStoresTheRefreshToken() = runTest {
        val store = MemoryStore()
        val tokens = SessionTokenSource(store, { error("not called") })
        tokens.start("a-1", "r-1")
        assertEquals("a-1", tokens.accessToken())
        assertEquals("r-1", store.token)
    }

    @Test
    fun aRefreshStoresTheNewPair() = runTest {
        val store = MemoryStore("r-1")
        val asked = mutableListOf<String>()
        val tokens = SessionTokenSource(store, { asked += it; RefreshResult.Tokens("a-2", "r-2") })
        assertEquals(RefreshOutcome.Refreshed, tokens.refresh())
        assertEquals(listOf("r-1"), asked)
        assertEquals("a-2", tokens.accessToken())
        assertEquals("r-2", store.token)
    }

    @Test
    fun noRefreshTokenIsRejectedWithoutAskingTheBackend() = runTest {
        val tokens = SessionTokenSource(MemoryStore(), { error("not called") })
        assertEquals(RefreshOutcome.Rejected, tokens.refresh())
    }

    @Test
    fun anUnavailableBackendKeepsTheSession() = runTest {
        val store = MemoryStore("r-1")
        val tokens = SessionTokenSource(store, { RefreshResult.Unavailable(offline) })
        assertEquals(RefreshOutcome.Unavailable(offline), tokens.refresh())
        assertEquals("r-1", store.token)
    }

    @Test
    fun aThrownFailureIsUnavailableAndKeepsTheSession() = runTest {
        val store = MemoryStore("r-1")
        val tokens = SessionTokenSource(store, { throw IllegalStateException("socket closed") })
        val outcome = assertIs<RefreshOutcome.Unavailable>(tokens.refresh())
        assertIs<UnexpectedException>(outcome.cause)
        assertEquals("AUTH-R-REFRESH-GEN", outcome.cause.code)
        assertEquals("r-1", store.token)

        val thrown = SessionTokenSource(store, { throw offline }).refresh()
        assertSame(offline, assertIs<RefreshOutcome.Unavailable>(thrown).cause)    // a SinewException passes through
    }

    @Test
    fun clearDropsTheTokensAndEndsTheSession() = runTest {
        val store = MemoryStore()
        var ended = 0
        val tokens = SessionTokenSource(store, { error("not called") }, onSessionEnded = { ended++ })
        tokens.start("a-1", "r-1")
        tokens.clear()
        assertNull(tokens.accessToken())
        assertNull(store.token)
        assertEquals(1, ended)
    }

    // Every interleaving of refresh and clear leaves no token after clear.

    @Test
    fun clearWhileTheBackendAnswersVoidsTheRefresh() = runTest {
        val store = MemoryStore("r-1")
        val answer = CompletableDeferred<RefreshResult>()
        val tokens = SessionTokenSource(store, { answer.await() })
        val refresh = async { tokens.refresh() }
        runCurrent()
        tokens.clear()
        answer.complete(RefreshResult.Tokens("a-2", "r-2"))
        assertEquals(RefreshOutcome.Rejected, refresh.await())
        assertNull(tokens.accessToken())
        assertNull(store.token)
    }

    @Test
    fun clearBeforeTheRefreshReadsTheStoreVoidsIt() = runTest {
        val store = MemoryStore("r-1")
        val tokens = SessionTokenSource(store, { RefreshResult.Tokens("a-2", "r-2") })
        val refresh = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { tokens.refresh() }
        tokens.clear()
        assertEquals(RefreshOutcome.Rejected, refresh.await())                    // nothing left to refresh with
        assertNull(tokens.accessToken())
        assertNull(store.token)
    }

    @Test
    fun clearRacingTheFinalWriteLeavesNoToken() = runTest {
        // The backend answers, and clear is launched in the same tick: whichever takes the lock first, nothing is left.
        for (clearFirst in listOf(true, false)) {
            val store = MemoryStore("r-1")
            val answer = CompletableDeferred<RefreshResult>()
            val tokens = SessionTokenSource(store, { answer.await() })
            val refresh = async { tokens.refresh() }
            runCurrent()
            if (clearFirst) {
                launch { tokens.clear() }
                answer.complete(RefreshResult.Tokens("a-2", "r-2"))
            } else {
                answer.complete(RefreshResult.Tokens("a-2", "r-2"))
                launch { tokens.clear() }
            }
            refresh.await()
            yield()
            runCurrent()
            assertNull(tokens.accessToken(), "clearFirst=$clearFirst")
            assertNull(store.token, "clearFirst=$clearFirst")
        }
    }

    @Test
    fun clearAfterTheRefreshFinishedDropsItsTokens() = runTest {
        val store = MemoryStore("r-1")
        val tokens = SessionTokenSource(store, { RefreshResult.Tokens("a-2", "r-2") })
        tokens.refresh()
        tokens.clear()
        assertNull(tokens.accessToken())
        assertNull(store.token)
    }

    @Test
    fun aNewSessionVoidsARefreshFromThePreviousOne() = runTest {
        val store = MemoryStore("r-1")
        val answer = CompletableDeferred<RefreshResult>()
        val tokens = SessionTokenSource(store, { answer.await() })
        val refresh = async { tokens.refresh() }
        runCurrent()
        tokens.start("b-1", "s-1")                                                 // another user signed in meanwhile
        answer.complete(RefreshResult.Tokens("a-2", "r-2"))
        assertEquals(RefreshOutcome.Rejected, refresh.await())
        assertEquals("b-1", tokens.accessToken())
        assertEquals("s-1", store.token)
    }
}

class SessionEndingTest {
    @Test
    fun aRejectedRefreshEndsTheSessionOnce() = runTest {
        val store = MemoryStore("r-1")
        var ended = 0
        val tokens = SessionTokenSource(store, { RefreshResult.Rejected }, onSessionEnded = { ended++ })
        assertEquals(RefreshOutcome.Rejected, tokens.refresh())
        assertNull(store.token)
        assertEquals(1, ended)
    }

    @Test
    fun noStoredRefreshTokenEndsTheSession() = runTest {
        var ended = 0
        val tokens = SessionTokenSource(MemoryStore(), { error("not called") }, onSessionEnded = { ended++ })
        assertEquals(RefreshOutcome.Rejected, tokens.refresh())
        assertEquals(1, ended)
    }

    @Test
    fun aRejectionArrivingAfterAnotherUserSignedInLeavesTheirSession() = runTest {
        val store = MemoryStore("r-1")
        val answer = CompletableDeferred<RefreshResult>()
        var ended = 0
        val tokens = SessionTokenSource(store, { answer.await() }, onSessionEnded = { ended++ })
        val refresh = async { tokens.refresh() }
        runCurrent()
        tokens.start("b-1", "s-1")
        answer.complete(RefreshResult.Rejected)
        assertEquals(RefreshOutcome.Rejected, refresh.await())
        assertEquals("b-1", tokens.accessToken())
        assertEquals("s-1", store.token)
        assertEquals(0, ended)
    }

    @Test
    fun throughTheClientARejectionAfterANewSignInLeavesTheNewSession() = runTest {
        val store = MemoryStore()
        val answer = CompletableDeferred<RefreshResult>()
        val tokens = SessionTokenSource(store, { answer.await() })
        tokens.start("a-1", "r-1")
        val backend = AuthBackend(this, tokens, serverToken = "b-1")
        val call = async { runCatching { backend.client.get("orders") } }
        runCurrent()
        tokens.start("b-1", "s-1")                                                 // a new user signs in mid-refresh
        answer.complete(RefreshResult.Rejected)
        call.await()
        assertEquals("b-1", tokens.accessToken())
        assertEquals("s-1", store.token)
    }
}
