package com.srctool.sinew.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import com.srctool.sinew.devtools.ui.BuildRibbon
import com.srctool.sinew.devtools.ui.SinewDevTools

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        SinewDevTools(ribbon = BuildRibbon("Sinew sample")) { SampleApp() }   // sinew.devtools picks real or no-op
    }
}
