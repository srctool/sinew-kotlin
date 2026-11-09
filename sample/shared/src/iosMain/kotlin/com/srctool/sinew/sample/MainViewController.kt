package com.srctool.sinew.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController
import com.srctool.sinew.devtools.ui.BuildRibbon
import com.srctool.sinew.devtools.ui.SinewDevTools

fun MainViewController(): UIViewController = ComposeUIViewController {
    SinewDevTools(ribbon = BuildRibbon("Sinew sample")) { SampleApp() }   // sinew.devtools picks real or no-op
}
