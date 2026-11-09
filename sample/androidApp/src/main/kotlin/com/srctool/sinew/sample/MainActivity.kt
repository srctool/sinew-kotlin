package com.srctool.sinew.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.srctool.sinew.devtools.ui.BuildRibbon
import com.srctool.sinew.devtools.ui.SinewDevTools

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SinewDevTools(ribbon = BuildRibbon("Sinew sample")) { SampleApp() } }   // the variant picks real or no-op
    }
}
