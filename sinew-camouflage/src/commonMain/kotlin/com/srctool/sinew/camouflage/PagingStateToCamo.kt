package com.srctool.sinew.camouflage

import androidx.compose.runtime.Composable
import com.srctool.camouflage.core.pagedlist.CamoPagedListState
import com.srctool.camouflage.core.pagedlist.PagedError
import com.srctool.camouflage.core.pagedlist.PagedErrorScope
import com.srctool.camouflage.core.pagedlist.PagedLoad
import com.srctool.sinew.exception.Localizer
import com.srctool.sinew.exception.displayMessage
import com.srctool.sinew.l10n.rememberSinewLocalizer
import com.srctool.sinew.models.ViewState
import com.srctool.sinew.paging.LoadType
import com.srctool.sinew.paging.PagingState

/**
 * This state as `CamoPagedList` shows it. Only a next-page load or failure keeps the items: a refresh drops them, so
 * the list shows its skeleton. An error's text is rendered now with [localizer], so call this while building the UI
 * and the text follows the current language. Without a localizer, a local message falls back to English.
 */
public fun <T> PagingState<T>.toCamo(localizer: Localizer?): CamoPagedListState<T> = when (val v = view) {
    is ViewState.Loading -> CamoPagedListState(
        items = if (loadType == LoadType.More) v.data?.items.orEmpty() else emptyList(),
        loading = when (loadType) {
            LoadType.Initial -> PagedLoad.Initial
            LoadType.Refresh -> PagedLoad.Refresh
            LoadType.More -> PagedLoad.More
        },
    )
    is ViewState.Done -> CamoPagedListState(items = v.data.items, endReached = !canLoadMore)
    is ViewState.Failed -> CamoPagedListState(
        items = if (loadType == LoadType.More) v.data?.items.orEmpty() else emptyList(),
        error = PagedError(
            message = v.error.displayMessage(localizer),
            scope = if (loadType == LoadType.More) PagedErrorScope.NextPage else PagedErrorScope.FirstPage,
        ),
    )
}

/** [toCamo] with Sinew's localizer for the current language. */
@Composable
public fun <T> PagingState<T>.toCamo(): CamoPagedListState<T> = toCamo(rememberSinewLocalizer())
