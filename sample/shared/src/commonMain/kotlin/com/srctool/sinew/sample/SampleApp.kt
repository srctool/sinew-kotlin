package com.srctool.sinew.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srctool.camouflage.core.provider.CamouflageProvider
import com.srctool.camouflage.skin.minimal.MinimalSkin
import com.srctool.camouflage.skin.minimal.minimalTheme

/** The sample app: one list screen, through `Pager` → `PagingState` → `toCamo` → `CamoPagedList`. */
@Composable
fun SampleApp() {
    SampleTheme { OrderListScreen(viewModel { OrderListViewModel(MockOrderBackend()) }) }
}

/** The sample's Camouflage skin and theme. */
@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    CamouflageProvider(skin = MinimalSkin, theme = minimalTheme(dark = isSystemInDarkTheme()), content = content)
}
