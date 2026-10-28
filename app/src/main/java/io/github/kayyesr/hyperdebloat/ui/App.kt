package io.github.kayyesr.hyperdebloat.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.kayyesr.hyperdebloat.core.Tier

/** Row colours from the PowerShell version. Text on them is always dark. */
fun tierColour(tier: Tier): Color = when (tier) {
    Tier.SAFE -> Color(0xFFD9F2D9)
    Tier.MODERATE -> Color(0xFFFFF6C2)
    Tier.HIGH -> Color(0xFFFFDDB0)
    Tier.UNSAFE -> Color(0xFFF8C8C8)
    Tier.FLAGGED -> Color(0xFFDDDDDD)
    Tier.APP -> Color(0xFFD6E8FA)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: MainViewModel) {
    AppTheme {
        val screen by vm.screen.collectAsState()
        when (val s = screen) {
            Screen.Connect -> ConnectScreen(vm)
            Screen.Home -> HomeScreen(vm)
            is Screen.Busy -> BusyScreen(vm, s.title)
            is Screen.Pick -> PickScreen(vm, s.spec)
            Screen.Reports -> ReportsScreen(vm)
        }
        val dialog by vm.dialog.collectAsState()
        dialog?.let { MessageDialog(it, vm::answer) }
    }
}

@Composable
private fun MessageDialog(d: DialogSpec, answer: (Boolean) -> Unit) {
    AlertDialog(
        // Must be answered: tapping outside does nothing, like a message box.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(if (d.warning) "HyperDebloat - warning" else "HyperDebloat") },
        text = {
            Text(
                d.text,
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { answer(true) }) { Text(if (d.yesNo) "Yes" else "OK") }
        },
        dismissButton = if (d.yesNo) ({ TextButton(onClick = { answer(false) }) { Text("No") } }) else null,
    )
}
