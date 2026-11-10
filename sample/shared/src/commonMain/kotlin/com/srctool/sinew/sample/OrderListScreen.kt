package com.srctool.sinew.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.srctool.camouflage.core.button.CamoButton
import com.srctool.camouflage.core.list.CamoListItem
import com.srctool.camouflage.core.pagedlist.CamoPagedList
import com.srctool.camouflage.core.provider.Camouflage
import com.srctool.sinew.camouflage.toCamo

/** The list. There's no pull gesture with a mouse, so the header offers a refresh too. */
// region screen
@Composable
fun OrderListScreen(viewModel: OrderListViewModel) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.onEvent(OrderListEvent.Opened) }
    Column(Modifier.fillMaxSize().background(Camouflage.colors.surface).safeDrawingPadding()) {
        CamoButton("Refresh", onClick = { viewModel.onEvent(OrderListEvent.Refreshed) }, modifier = Modifier.padding(8.dp))
        CamoPagedList(
            state = state.orders.toCamo(),
            itemKey = { it.id },
            onLoadMore = { viewModel.onEvent(OrderListEvent.EndReached) },
            onRetry = { viewModel.onEvent(OrderListEvent.RetryTapped) },
            onRefresh = { viewModel.onEvent(OrderListEvent.Refreshed) },
            item = { order, _ -> CamoListItem(order.title, supporting = order.status) },
            modifier = Modifier.weight(1f).testTag("orders"),
        )
    }
}
// endregion
