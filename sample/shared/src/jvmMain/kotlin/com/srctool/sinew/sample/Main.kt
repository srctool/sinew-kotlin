package com.srctool.sinew.sample

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.srctool.sinew.devtools.ui.BuildRibbon
import com.srctool.sinew.devtools.ui.SinewDevTools

// region devtools-entry
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Sinew") {
        SinewDevTools(ribbon = BuildRibbon("Sinew sample")) { SampleApp() }   // sinew.devtools picks real or no-op
    }
}
// endregion
