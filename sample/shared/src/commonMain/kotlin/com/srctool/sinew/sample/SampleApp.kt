package com.srctool.sinew.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** The sample app. Until milestone S5 it shows a placeholder; then it becomes the list screen through CamoPagedList. */
@Composable
fun SampleApp() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText("Sinew sample (S0)")
    }
}
