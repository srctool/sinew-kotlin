package com.srctool.sinew.network

import com.srctool.sinew.exception.ApiErrorException
import com.srctool.sinew.exception.ConflictException
import com.srctool.sinew.exception.ForbiddenException
import com.srctool.sinew.exception.NotFoundException
import com.srctool.sinew.exception.UnauthorizedException
import com.srctool.sinew.exception.UndefinedErrorResponseException
import com.srctool.sinew.exception.ValidationException
import com.srctool.sinew.exception.processCall
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.Result
import com.srctool.sinew.models.SinewException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Where [SessionTokenSource] keeps the refresh token: the app adapts its secure store in one line. */
public interface RefreshTokenStore {
    /** The stored refresh token, or `null` when there's none. */
    public suspend fun read(): String?

    /** Stores [token], replacing the previous one. */
    public suspend fun write(token: String)

    /** Deletes the stored token. */
    public suspend fun delete()
}

/**
 * The app's backend call that trades a refresh token for a new pair. It must use a client **without** the auth layer
 * (a plain Ktor client, or a second `SinewHttp` client for the auth endpoints), so a refresh never refreshes itself.
 */
public fun interface RefreshCall {
    /** Asks the backend for a new pair. A thrown failure counts as [RefreshResult.Unavailable]. */
    public suspend fun refresh(refreshToken: String): RefreshResult
}

/** What the backend answered to a refresh. */
public sealed interface RefreshResult {
    /** A new pair. With rotating refresh tokens, [refresh] replaces the stored one. */
    public data class Tokens(val access: String, val refresh: String) : RefreshResult

    /** The backend refused the refresh token: the session is over. */
    public data object Rejected : RefreshResult

    /** The refresh couldn't be completed (offline, a timeout, a 5xx): the session is kept. */
    public data class Unavailable(val cause: SinewException) : RefreshResult

    public companion object {
        /**
         * The answer a guarded refresh call means, so a [RefreshCall] is only the HTTP call:
         *
         * ```kotlin
         * RefreshCall { token -> RefreshResult.from(processApiCall("AUTH", "REFRESH") { authApi.refresh(token) }) }
         * ```
         *
         * The backend refusing the token is [Rejected]: a 401 or 403, a 400 or 422 (OAuth's `invalid_grant`), a
         * readable refusal (`ApiErrorException`) and any other 4xx. Everything else keeps the session as
         * [Unavailable]: offline, a timeout, a 408 or 429, a 5xx, and an unreadable answer, which is a bug, not a
         * sign-out.
         */
        public fun from(result: Result<Tokens>): RefreshResult = when (result) {
            is Result.Success -> result.data
            is Result.Failure -> if (result.error.isRefusal()) Rejected else Unavailable(result.error)
        }

        private fun SinewException.isRefusal(): Boolean = when (this) {
            is UnauthorizedException, is ForbiddenException, is ValidationException, is ApiErrorException,
            is NotFoundException, is ConflictException -> true
            is UndefinedErrorResponseException -> status in 400..499 && status != 408
            else -> false
        }
    }
}

/**
 * Sinew's ready [TokenSource]: the access token in memory, the refresh token in [store], and a refresh through
 * [call].
 *
 * A **session epoch** keeps a sign-out final. [clear] increments it, and a refresh stores its tokens only if the
 * epoch is the one it started in, checked and written under one lock. So a refresh that finishes after sign-out
 * stores nothing and returns [RefreshOutcome.Rejected], whatever the order of the two.
 *
 * A refresh the backend rejects ends the session here, under the same lock, and only if it's still the session the
 * refresh started in: a refresh voided by sign-out, or by another user's sign-in, never touches the new state.
 *
 * @param onSessionEnded called when a session ends: on [clear], and when the backend rejects the refresh token (or
 *   none is stored), which the app learns only here. Route to sign-in from it, and keep it idempotent.
 */
@OptIn(ExperimentalAtomicApi::class)
public class SessionTokenSource(
    private val store: RefreshTokenStore,
    private val call: RefreshCall,
    private val onSessionEnded: suspend () -> Unit = {},
) : TokenSource {
    private val mutex = Mutex()
    private var epoch = 0L
    private val access = AtomicReference<String?>(null)

    override fun accessToken(): String? = access.load()

    /** Starts a session after sign-in: stores [refreshToken] and holds [accessToken]. Voids any refresh still running. */
    public suspend fun start(accessToken: String, refreshToken: String): Unit = mutex.withLock {
        epoch++
        store.write(refreshToken)
        access.store(accessToken)
    }

    override suspend fun refresh(): RefreshOutcome {
        val (started, refreshToken) = mutex.withLock { epoch to store.read() }
        if (refreshToken == null) return endIfStill(started)
        val result = processCall("AUTH", "REFRESH", ExceptionLayer.Repository, emptyList()) { call.refresh(refreshToken) }
        val answer = when (result) {
            is Result.Failure -> return RefreshOutcome.Unavailable(result.error)
            is Result.Success -> result.data
        }
        return when (answer) {
            is RefreshResult.Unavailable -> RefreshOutcome.Unavailable(answer.cause)
            RefreshResult.Rejected -> endIfStill(started)
            is RefreshResult.Tokens -> mutex.withLock {
                if (epoch != started) return@withLock RefreshOutcome.Rejected      // signed out or in meanwhile: store nothing
                store.write(answer.refresh)
                access.store(answer.access)
                RefreshOutcome.Refreshed
            }
        }
    }

    /**
     * The backend refused, or there's no refresh token: ends the session, unless the app already ended it or started
     * another one since [started]. Ending someone else's session would sign a new user out.
     */
    private suspend fun endIfStill(started: Long): RefreshOutcome {
        val ended = mutex.withLock {
            if (epoch != started) return@withLock false
            drop()
            true
        }
        if (ended) onSessionEnded()
        return RefreshOutcome.Rejected
    }

    private suspend fun drop() {
        epoch++
        access.store(null)
        store.delete()
    }

    override suspend fun clear() {
        mutex.withLock { drop() }
        onSessionEnded()
    }
}
