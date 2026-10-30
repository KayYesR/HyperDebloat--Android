package io.github.kayyesr.hyperdebloat.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun App(vm: MainViewModel) {
    AppTheme {
        val screen by vm.screen.collectAsState()
        when (val s = screen) {
            Screen.Setup -> SetupScreen(vm)
            Screen.Home -> HomeScreen(vm)
            is Screen.Busy -> BusyScreen(vm, s.title)
            is Screen.Pick -> PickScreen(vm, s.spec)
            Screen.Reports -> ReportsScreen(vm)
        }
        val dialog by vm.dialog.collectAsState()
        dialog?.let { MessageDialog(it, vm::answer) }
    }
}

/** A question (Yes/No) or a notice (OK). It must be answered; tapping outside does nothing. */
@Composable
fun MessageDialog(d: DialogSpec, answer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = if (d.warning) ({ Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }) else null,
        title = d.title?.let { t -> { Text(t) } },
        text = {
            Text(d.text, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            Button(
                onClick = { answer(true) },
                colors = if (d.warning && d.yesNo) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { Text(d.yesText ?: if (d.yesNo) "Yes" else "OK") }
        },
        dismissButton = if (d.yesNo) ({ TextButton(onClick = { answer(false) }) { Text(d.noText ?: "No") } }) else null,
    )
}
