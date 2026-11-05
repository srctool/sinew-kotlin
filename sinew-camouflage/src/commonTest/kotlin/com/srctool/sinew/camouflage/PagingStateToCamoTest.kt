package com.srctool.sinew.camouflage

import com.srctool.camouflage.core.pagedlist.CamoPagedListState
import com.srctool.camouflage.core.pagedlist.PagedError
import com.srctool.camouflage.core.pagedlist.PagedErrorScope
import com.srctool.camouflage.core.pagedlist.PagedLoad
import com.srctool.sinew.exception.Localizer
import com.srctool.sinew.exception.NoInternetConnectionException
import com.srctool.sinew.models.ExceptionLayer
import com.srctool.sinew.models.PagingDomain
import com.srctool.sinew.models.PagingMetaDomain
import com.srctool.sinew.models.ViewState
import com.srctool.sinew.paging.LoadType
import com.srctool.sinew.paging.PagingState
import kotlin.test.Test
import kotlin.test.assertEquals

private val offline = NoInternetConnectionException("ORD", ExceptionLayer.Repository, "GO")
private const val ENGLISH = "You're offline. Check your connection and try again."
private val page = PagingDomain(listOf("a", "b"), PagingMetaDomain(hasNextPage = true))
private val lastPage = PagingDomain(listOf("a", "b"), PagingMetaDomain(hasNextPage = false))

private fun camo(view: ViewState<PagingDomain<String>>, loadType: LoadType, localizer: Localizer? = null) =
    PagingState(view, loadType).toCamo(localizer)

class PagingStateToCamoTest {
    @Test
    fun loadingInitialIsTheSkeletonWithoutItems() {
        assertEquals(CamoPagedListState(loading = PagedLoad.Initial), camo(ViewState.Loading(page), LoadType.Initial))
    }

    @Test
    fun loadingRefreshDropsTheItems() {
        assertEquals(CamoPagedListState(loading = PagedLoad.Refresh), camo(ViewState.Loading(page), LoadType.Refresh))
    }

    @Test
    fun loadingMoreKeepsTheItems() {
        assertEquals(CamoPagedListState(page.items, PagedLoad.More), camo(ViewState.Loading(page), LoadType.More))
    }

    @Test
    fun doneWithANextPageHasNotReachedTheEndForAnyLoadType() {
        LoadType.entries.forEach { type ->
            assertEquals(CamoPagedListState(page.items), camo(ViewState.Done(page), type), "$type")
        }
    }

    @Test
    fun doneOnTheLastPageHasReachedTheEndForAnyLoadType() {
        LoadType.entries.forEach { type ->
            assertEquals(CamoPagedListState(lastPage.items, endReached = true), camo(ViewState.Done(lastPage), type), "$type")
        }
    }

    @Test
    fun failedInitialOrRefreshIsAFirstPageErrorWithoutItems() {
        listOf(LoadType.Initial, LoadType.Refresh).forEach { type ->
            assertEquals(
                CamoPagedListState(error = PagedError("$ENGLISH (${offline.code})", PagedErrorScope.FirstPage)),
                camo(ViewState.Failed(page, offline), type),
                "$type",
            )
        }
    }

    @Test
    fun failedMoreIsANextPageErrorThatKeepsTheItems() {
        assertEquals(
            CamoPagedListState(page.items, error = PagedError("$ENGLISH (${offline.code})", PagedErrorScope.NextPage)),
            camo(ViewState.Failed(page, offline), LoadType.More),
        )
    }

    @Test
    fun aNewStateStartsAsTheInitialSkeleton() {
        assertEquals(CamoPagedListState(loading = PagedLoad.Initial), PagingState<String>().toCamo(null))
    }

    @Test
    fun anEmptyFirstPageHasReachedTheEnd() {
        assertEquals(CamoPagedListState(endReached = true), camo(ViewState.Done(PagingDomain()), LoadType.Initial))
    }

    @Test
    fun loadingAndFailedMoreWithoutDataHaveNoItems() {
        assertEquals(CamoPagedListState(loading = PagedLoad.More), camo(ViewState.Loading(), LoadType.More))
        assertEquals(emptyList(), camo(ViewState.Failed(error = offline), LoadType.More).items)
    }

    @Test
    fun theErrorTextUsesTheLocalizer() {
        val state = camo(ViewState.Failed(error = offline), LoadType.Initial) { _, _ -> "Anda offline." }
        assertEquals("Anda offline. (${offline.code})", state.error?.message)
    }

    @Test
    fun theErrorTextFallsBackToEnglishWithoutALocalizer() {
        assertEquals("$ENGLISH (${offline.code})", camo(ViewState.Failed(error = offline), LoadType.Initial).error?.message)
    }
}
