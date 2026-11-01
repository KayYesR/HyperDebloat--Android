package io.github.kayyesr.hyperdebloat.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.kayyesr.hyperdebloat.core.DeviceInfo
import io.github.kayyesr.hyperdebloat.core.RestoreAction
import io.github.kayyesr.hyperdebloat.core.Row
import io.github.kayyesr.hyperdebloat.core.Tier
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Renders each screen to a PNG so the design can be checked without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The app's Conscrypt (Android build) cannot load on the JVM, so it is kept out of these tests.
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val out = "build/outputs/roborazzi"

    private val apps = object : AppLookup {
        val names = mapOf("com.miui.analytics" to "Analytics", "com.google.android.youtube" to "YouTube",
            "com.miui.securitycenter" to "Security", "com.block.blast" to "Block Blast", "com.mi.globalbrowser" to "Mi Browser",
            "com.miui.weather2" to "Weather", "com.android.settings" to "Settings")
        override fun label(pkg: String) = names[pkg] ?: ""
        override fun icon(pkg: String): Bitmap? = null
    }

    private val rows = listOf(
        Row(Tier.SAFE, "SAFE", "com.miui.analytics", "Xiaomi Analytics telemetry.", "GREEN: nothing depends on it", "UAD-ng: Recommended | fadeltd: safe | ovsky: phase1_safe", "phone rates it GREEN", category = "Ads & tracking"),
        Row(Tier.SAFE, "SAFE", "com.miui.msa.global", "System ad engine that profiles usage for ads.", "", "", "", category = "Ads & tracking"),
        Row(Tier.SAFE, "SAFE", "com.google.android.youtube", "YouTube.", "", "", "", userApp = true, category = "Essential Google apps",
            note = "Remove only if you don't use YouTube."),
        Row(Tier.UNSAFE, "UNSAFE", "com.android.vending", "Google Play Store.", "", "", "", userApp = true, category = "Essential Google apps",
            note = "Keep. Needed to install and update apps."),
        Row(Tier.MOSTLY, "MOSTLY OK", "com.miui.player", "Mi Music player.", "", "", "", category = "Media & music"),
        Row(Tier.SAFE, "SAFE", "com.xiaomi.glgm", "Xiaomi game centre.", "", "", "", category = "Games"),
        Row(Tier.MODERATE, "MODERATE", "com.mi.globalbrowser", "Mi Browser.", "", "", "", category = "Xiaomi & phone apps"),
        Row(Tier.HIGH, "HIGH", "com.miui.weather2", "Weather app and widget data.", "", "", "", category = "Xiaomi & phone apps"),
        Row(Tier.UNSAFE, "UNSAFE", "com.miui.securitycenter", "Security app (permissions, cleaner, battery).", "", "", "", category = "Xiaomi services"),
        Row(Tier.UNSAFE, "UNSAFE", "com.android.settings", "Settings app. Never remove.", "", "", "", locked = true, category = "Android system"),
        Row(Tier.FLAGGED, "FLAGGED", "com.xiaomi.mtb", "", "", "", "", category = "Xiaomi services"),
        Row(Tier.APP, "APP", "com.block.blast", "", "", "", "", ordinary = true, atSetup = true, category = "Installed during setup"),
    )

    // setContent may only be called once per test, so later shots swap the content through state.
    private var current by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var darkMode by mutableStateOf(false)
    private var started = false

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        darkMode = dark
        current = content
        if (!started) {
            compose.setContent { CompositionLocalProvider(LocalAnimate provides false) { AppTheme(dark = darkMode) { key(current) { current?.invoke() } } } }
            started = true
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    @Test fun setup() {
        for (s in SetupStep.entries) {
            shot("1_setup_${s.ordinal + 1}_${s.name.lowercase()}") {
                SetupContent(s, s == SetupStep.WIRELESS_DEBUGGING, if (s == SetupStep.PAIR) "Now type the 6-digit code into the HyperDebloat notification." else "",
                    {}, {}, {}, {}, {}, {})
            }
        }
    }

    private val dev = DeviceInfo("Xiaomi", "POCO", "25098PN5AI", "POCO X8 Pro Max", "dagda", "OS3.0", "OS3.0.5.0.WOQINXM", "16", 36)

    @Test fun home() {
        val counts = mapOf(Tier.SAFE to 41, Tier.MOSTLY to 23, Tier.MODERATE to 41, Tier.HIGH to 22, Tier.UNSAFE to 58, Tier.FLAGGED to 37, Tier.APP to 12)
        @Composable fun home(g: Guide, c: Map<Tier, Int>?) =
            HomeContent(dev, c, g, false, false, "0.4.0", {}, {}, {}, {}, {}, {}, {}, {}, {})
        shot("2_home_1_scan") { home(Guide.SCAN, null) }
        shot("2_home_2_review") { home(Guide.REVIEW, counts) }
        shot("2_home_3_restart") { home(Guide.RESTART, counts) }
        shot("2_home_4_done") { home(Guide.DONE, counts) }
        shot("2_home_5_failed") {
            HomeContent(dev, null, Guide.SCAN, false, false, "0.5.0", {}, {}, {}, {}, {}, {}, {}, {}, {},
                scanError = "Command failed: dumpsys package packages (The phone stopped answering)")
        }
        shot("2_home_dark", dark = true) { home(Guide.REVIEW, counts) }
    }

    @Test fun lists() {
        val green = rows.filter { it.tier == Tier.SAFE || it.tier == Tier.MOSTLY || it.atSetup || it.category == "Essential Google apps" }
        shot("3_remove_junk") { PickContent(PickSpec("Remove junk", green, "Remove selected", true, "", false, simple = true,
            intro = "Junk is already ticked. Untick anything you want to keep, then tap Remove."), apps) {} }
        shot("3_custom") { PickContent(PickSpec("Choose apps", rows, "Remove selected", true, "", true, intro = "Every app on the phone, colour-coded. Tap ⓘ to see why."), apps) {} }
        shot("3_custom_dark", dark = true) { PickContent(PickSpec("Choose apps", rows, "Remove selected", true, "", true), apps) {} }
        val restore = listOf(
            Row(Tier.SAFE, "RESTORE", "com.miui.analytics", "Xiaomi Analytics telemetry.", "", "", "removed by HyperDebloat on 2026-10-28", action = RestoreAction.INSTALL),
            Row(Tier.SAFE, "UNFREEZE", "com.mi.globalbrowser", "Mi Browser.", "", "", "", action = RestoreAction.UNSUSPEND),
            Row(Tier.MODERATE, "PLAY STORE", "com.block.blast", "", "", "", "", action = RestoreAction.STORE),
        )
        shot("4_restore") { PickContent(PickSpec("Bring apps back", restore, "Restore selected", false, "", false, simple = true,
            intro = "Tick the apps you want back, then tap Restore."), apps) {} }
    }

    @Test fun busy() {
        shot("5_busy") { BusyContent("Scanning your phone", listOf("== Scanning the phone (read-only)", "412 packages installed (301 system)", "Reading live service and provider connections (only apps running right now)...")) }
    }

    @Test fun dialog() {
        compose.setContent {
            AppTheme {
                MessageDialog(DialogSpec("2 unsafe selected", "• Security\n• Settings storage\n\nThese can stop the phone starting. The fix may be a factory reset.",
                    true, true, "Remove anyway", "Skip them")) {}
            }
        }
        captureScreenRoboImage("$out/6_dialog_warning.png")
    }
}
