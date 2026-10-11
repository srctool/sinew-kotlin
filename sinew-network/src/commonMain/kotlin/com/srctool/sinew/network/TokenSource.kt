package com.srctool.sinew.network

import com.srctool.sinew.models.SinewException

/**
 * Where a client's tokens live and how they refresh. Sinew's `SessionTokenSource` is the ready implementation; an app
 * that writes its own must keep the [clear] contract.
 */
public interface TokenSource {
    /** The current access token, held in memory, or `null` when signed out. */
    public fun accessToken(): String?

    /**
     * Gets a new token pair from the backend and stores it. Called by the auth layer once for any number of
     * concurrent 401s. The call must not go through a client with the auth layer, so a refresh never refreshes
     * itself.
     */
    public suspend fun refresh(): RefreshOutcome

    /**
     * The session ended: drop the tokens, void any refresh still running, and tell the app's session service.
     *
     * Contract: once [clear] returns, a refresh that started before it must not store its tokens, and must return
     * [RefreshOutcome.Rejected]. Otherwise a refresh finishing after sign-out would silently sign the user, or the
     * next user, back in. Check and write under one lock, with a counter [clear] increments.
     */
    public suspend fun clear()
}

/** What a refresh attempt concluded. */
public sealed interface RefreshOutcome {
    /** New tokens are stored: replay the waiting requests once. */
    public data object Refreshed : RefreshOutcome

    /**
     * The backend refused the refresh token, or the session ended while refreshing. The session is over: waiting
     * requests fail with `UnauthorizedException`.
     */
    public data object Rejected : RefreshOutcome

    /**
     * The refresh couldn't be completed: offline, a timeout, a 5xx. The session is **kept**, and waiting requests fail
     * with [cause], so the page shows a retryable error instead of signing the user out.
     */
    public data class Unavailable(val cause: SinewException) : RefreshOutcome
}
