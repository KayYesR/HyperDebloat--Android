package io.github.kayyesr.hyperdebloat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.kayyesr.hyperdebloat.adb.AdbManager
import io.github.kayyesr.hyperdebloat.adb.PairingService

@Composable
fun ConnectScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val state by vm.adb.state.collectAsState()
    val error by vm.adb.lastError.collectAsState()
    val pairing by PairingService.status.collectAsState()
    var code by remember { mutableStateOf("") }
    // Paired from the notification while this screen is open: move on to the home screen.
    LaunchedEffect(state) { if (state == AdbManager.State.CONNECTED) vm.tryConnect() }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startPairing() }
    fun startPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startPairing()
    }
    fun openDevOptions() {
        val dev = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(dev) } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("HyperDebloat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Device-aware debloater for Xiaomi, Redmi and POCO phones. Runs on the phone itself over wireless debugging; no root, no PC.",
                style = MaterialTheme.typography.bodyMedium)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state == AdbManager.State.CHECKING) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    }
                    Column {
                        Text(
                            when (state) {
                                AdbManager.State.CHECKING -> "Looking for wireless debugging..."
                                AdbManager.State.NOT_PAIRED -> "Not paired yet - do step 3."
                                AdbManager.State.NOT_REACHABLE -> "Not connected."
                                AdbManager.State.CONNECTED -> "Connected."
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        if (error.isNotEmpty() && state != AdbManager.State.CONNECTED) Text(error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            StepCard("1. Developer options",
                "Settings > About phone > tap OS version 7 times. Then Settings > Additional settings > Developer options > USB debugging: ON.") {
                OutlinedButton(onClick = ::openDevOptions) { Text("Open Developer options") }
            }
            StepCard("2. Wireless debugging",
                "Connect to Wi-Fi. In Developer options turn on Wireless debugging and allow it on this network. It may switch itself off after a restart or a Wi-Fi change; turn it on again before using HyperDebloat.") {}
            StepCard("3. Pair (first time only)",
                "Tap Start pairing, then in Wireless debugging tap Pair device with pairing code. Leave that dialog open, pull down the notification shade and type the 6-digit code into the HyperDebloat notification.\n\n" +
                    "No reply box in the notification? On Xiaomi phones, search Settings for notification style and choose Android, or use split screen and type the code below.") {
                Button(onClick = ::startPairing) { Text("Start pairing") }
                if (pairing.isNotEmpty()) Text(pairing, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                        label = { Text("Pairing code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { vm.pairInApp(code) }, enabled = code.length == 6) { Text("Pair") }
                }
            }
            StepCard("4. Connect", "Once paired, HyperDebloat connects by itself whenever Wireless debugging is on.") {
                Button(onClick = vm::tryConnect, enabled = state != AdbManager.State.CHECKING) { Text("Connect") }
            }
            OutlinedButton(onClick = vm::showReports, modifier = Modifier.fillMaxWidth()) { Text("Reports") }
            Text("Version ${vm.version}. Made by kayyesr, perfected by Claude.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StepCard(title: String, text: String, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
