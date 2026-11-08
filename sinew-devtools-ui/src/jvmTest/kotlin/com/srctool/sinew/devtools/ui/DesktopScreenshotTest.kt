package com.srctool.sinew.devtools.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import io.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.Test

/** Every scene, captured at a phone's size on the desktop, against this OS's references. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class DesktopScreenshotTest {
    private fun capture(name: String) = runDesktopComposeUiTest(width = 400, height = 800) {
        Scenes.prepare(name)
        setContent { Scenes.Render(name) }
        waitForIdle()
        val target = screenshotTarget(
            platform = "desktop",
            name = name,
            dir = System.getProperty("sinew.screenshots.dir"),
            mode = System.getProperty("sinew.screenshots", "verify"),
            osName = System.getProperty("os.name"),
            exists = { File(it).exists() },
        )
        onRoot().captureRoboImage(
            File(target.path),
            RoborazziOptions(taskType = if (target.record) RoborazziTaskType.Record else RoborazziTaskType.Verify),
        )
    }

    @Test fun home() = capture("home")
    @Test fun networkList() = capture("network-list")
    @Test fun networkDetail() = capture("network-detail")
    @Test fun networkBlocked() = capture("network-blocked")
    @Test fun exceptions() = capture("exceptions")
    @Test fun secureStore() = capture("secure-store")
    @Test fun faults() = capture("faults")
    @Test fun overlay() = capture("overlay")
    @Test fun uncaught() = capture("uncaught")
}
