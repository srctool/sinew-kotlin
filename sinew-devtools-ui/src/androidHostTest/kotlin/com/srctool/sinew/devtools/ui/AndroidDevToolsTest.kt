package com.srctool.sinew.devtools.ui

import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.srctool.sinew.devtools.DevTools
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The overlay on Android, under Robolectric, and every scene's screenshot against this OS's references. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
@OptIn(ExperimentalRoborazziApi::class)
class AndroidDevToolsTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun reset() = DevTools.close()

    @Test
    fun theFloatingButtonOpensTheDashboard() {
        compose.setContent { SinewDevTools { Text("The app") } }
        compose.onNodeWithTag(FLOATING_TAG).performClick()
        compose.waitForIdle()
        assertTrue(DevTools.isOpen.value)
        compose.onNodeWithTag("section:Sinew").assertExists()
    }

    @Test
    fun aDragNeverOpensTheDashboard() {
        compose.setContent { SinewDevTools { Text("The app") } }
        compose.onNodeWithTag(FLOATING_TAG).performTouchInput { swipe(center, center + Offset(-100f, -150f), durationMillis = 300) }
        compose.waitForIdle()
        assertFalse(DevTools.isOpen.value)
    }

    private fun capture(name: String) {
        runBlocking { Scenes.prepare(name) }
        compose.setContent { Scenes.Render(name) }
        compose.waitForIdle()
        val target = screenshotTarget(
            platform = "android",
            name = name,
            dir = System.getProperty("sinew.screenshots.dir"),
            mode = System.getProperty("sinew.screenshots", "verify"),
            osName = System.getProperty("os.name"),
            exists = { File(it).exists() },
        )
        compose.onRoot().captureRoboImage(
            target.path,
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
