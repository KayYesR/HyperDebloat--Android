package io.github.kayyesr.hyperdebloat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.kayyesr.hyperdebloat.adb.PairingService
import kotlinx.coroutines.delay

enum class SetupStep { NOTIFY, DEV_OPTIONS, WIFI, WIRELESS_DEBUGGING, PAIR }

/** One-time setup as a checklist. The step to do now is highlighted; each step ticks itself off. */
@Composable
fun SetupScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val step by vm.setupStep.collectAsState()
    val checking by vm.checking.collectAsState()
    val pairing by PairingService.status.collectAsState()
    val status by vm.adb.lastError.collectAsState()
    val details by vm.adb.debug.collectAsState()

    // Re-check every few seconds while this screen is open, so steps tick off by themselves.
    LaunchedEffect(Unit) {
        while (true) { vm.refreshSetup(); delay(3000) }
    }

    // Asked first, on its own step, so it never pops up while the pairing code is on screen.
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshSetup() }
    fun open(action: String) {
        try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    SetupContent(
        step = step,
        checking = checking,
        pairingStatus = pairing,
        onOpenAbout = { open(Settings.ACTION_DEVICE_INFO_SETTINGS) },
        onOpenWifi = { open(Settings.ACTION_WIFI_SETTINGS) },
        onOpenDev = { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
        onStartPairing = {
            vm.startPairing()
            open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        },
        onAllowNotifications = {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.refreshSetup()
        },
        onSkipNotifications = vm::skipNotifications,
        onPairCode = vm::pairInApp,
        onReports = vm::showReports,
        status = status,
        details = details,
        onPort = vm::connectPort,
    )
}

@Composable
fun SetupContent(
    step: SetupStep,
    checking: Boolean,
    pairingStatus: String,
    onOpenAbout: () -> Unit,
    onOpenWifi: () -> Unit,
    onOpenDev: () -> Unit,
    onStartPairing: () -> Unit,
    onPairCode: (String) -> Unit,
    onReports: () -> Unit,
    status: String = "",
    details: List<String> = emptyList(),
    onPort: (Int) -> Unit = {},
    onAllowNotifications: () -> Unit = {},
    onSkipNotifications: () -> Unit = {},
) {
    var showDetails by remember { mutableStateOf(false) }
    fun state(s: SetupStep) = when {
        s.ordinal < step.ordinal -> StepState.DONE
        s == step -> StepState.CURRENT
        else -> StepState.LATER
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HyperDebloat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onReports) { Text("Reports") }
            }
            Text("Removes junk apps from Xiaomi, Redmi and POCO phones.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Text("First, a one-time setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Follow the highlighted step. Each one ticks itself off.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StepCard(1, "Allow notifications", state(SetupStep.NOTIFY),
                    "HyperDebloat shows a notification so you can type the pairing code into it. That is all it is used for.",
                    doneText = "Allowed", button = "Allow notifications", onClick = onAllowNotifications,
                    secondary = "Not now", onSecondary = onSkipNotifications)
                StepCard(2, "Turn on Developer options", state(SetupStep.DEV_OPTIONS),
                    "This unlocks the hidden settings HyperDebloat needs.\n\nTap the button, then tap OS version 7 times. Come back here when it says you are a developer.",
                    doneText = "Done", button = "Open About phone", onClick = onOpenAbout)
                StepCard(3, "Connect to Wi-Fi", state(SetupStep.WIFI),
                    "The next setting only works on Wi-Fi. Any Wi-Fi works, including another phone's hotspot. This phone's own hotspot does not work: Android does not allow it.",
                    doneText = "Connected", button = "Open Wi-Fi settings", onClick = onOpenWifi)
                StepCard(4, "Turn on Wireless debugging", state(SetupStep.WIRELESS_DEBUGGING),
                    "This lets HyperDebloat remove apps, the same way a computer would.\n\nTap the button, find Wireless debugging, switch it on and tap Allow.",
                    doneText = "On", button = "Open Developer options", onClick = onOpenDev,
                    extra = { PortEntry(onPort) })
                StepCard(5, "Pair HyperDebloat", state(SetupStep.PAIR),
                    "This connects HyperDebloat to your phone. You only do it once.",
                    doneText = "Paired", extra = { PairExtra(pairingStatus, onStartPairing, onPairCode); PortEntry(onPort) })
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (checking) "Checking..." else status.ifEmpty { "Waiting for you" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!checking && status.isNotEmpty()) Spacer(Modifier.height(2.dp))
            TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showDetails) "Hide details" else "Stuck? Show details")
            }
            if (showDetails) {
                Text("Send this to the developer if it stays stuck.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp)) {
                    if (details.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                    for (l in details.takeLast(25)) {
                        Text(l, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = if (l.contains("  !")) Color(0xFFE07A10) else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun PairExtra(status: String, onStart: () -> Unit, onCode: (String) -> Unit) {
    var typeHere by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    Spacer(Modifier.height(12.dp))
    val lines = listOf(
        "Tap Start pairing. Developer options opens.",
        "Tap Wireless debugging, then Pair device with pairing code. A 6-digit code appears.",
        "Pull down the notification bar. Type the code into the HyperDebloat message and send it.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lines.forEachIndexed { n, l ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("${n + 1}", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(l, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (status.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(14.dp))
    if (!typeHere) {
        PulsingButton("Start pairing", onStart)
        TextButton(onClick = { typeHere = true }, modifier = Modifier.fillMaxWidth()) { Text("No reply box in the message? Type the code here") }
    } else {
        Text("Open Settings and HyperDebloat side by side (split screen), then type the code shown in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
            label = { Text("6-digit code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onCode(code) }, enabled = code.length == 6, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Pair") }
        TextButton(onClick = { typeHere = false }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

/** Fallback when the phone's announcement is not found: type the port shown under Wireless debugging. */
@Composable
private fun PortEntry(onPort: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Already on but stuck? Enter the port") }
        return
    }
    Spacer(Modifier.height(10.dp))
    Text("Open Wireless debugging. Under IP address & Port, type the number after the colon (for example 192.168.1.5:37145 → 37145).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = port,
            onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { port.toIntOrNull()?.let(onPort) }, enabled = (port.toIntOrNull() ?: 0) in 1..65535,
            modifier = Modifier.height(56.dp)) { Text("Connect") }
    }
}
