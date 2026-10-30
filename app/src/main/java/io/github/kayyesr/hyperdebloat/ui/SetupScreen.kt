package io.github.kayyesr.hyperdebloat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.kayyesr.hyperdebloat.adb.PairingService
import kotlinx.coroutines.delay

enum class SetupStep { DEV_OPTIONS, WIFI, WIRELESS_DEBUGGING, PAIR }

/** One step at a time until HyperDebloat is connected. Each step checks itself and moves on. */
@Composable
fun SetupScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val step by vm.setupStep.collectAsState()
    val checking by vm.checking.collectAsState()
    val pairing by PairingService.status.collectAsState()

    // Re-check every few seconds while this screen is open, so steps tick off by themselves.
    LaunchedEffect(Unit) {
        while (true) { vm.refreshSetup(); delay(3000) }
    }

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startPairing() }
    fun open(action: String, fallback: String = Settings.ACTION_SETTINGS) {
        try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
            ctx.startActivity(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startPairing()
        },
        onPairCode = vm::pairInApp,
        onReports = vm::showReports,
    )
}

private class StepInfo(val icon: ImageVector, val title: String, val text: String)

private fun info(step: SetupStep) = when (step) {
    SetupStep.DEV_OPTIONS -> StepInfo(PhoneIcon, "Turn on Developer options",
        "Open About phone and tap OS version 7 times.")
    SetupStep.WIFI -> StepInfo(WifiIcon, "Connect to Wi-Fi",
        "Any Wi-Fi works, including another phone's hotspot. Needed once after each restart.")
    SetupStep.WIRELESS_DEBUGGING -> StepInfo(Icons.Filled.Build, "Turn on Wireless debugging",
        "In Developer options, switch on Wireless debugging and tap Allow.")
    SetupStep.PAIR -> StepInfo(Icons.Filled.Lock, "Pair HyperDebloat",
        "Only needed once.")
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
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HyperDebloat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onReports) { Text("Reports") }
            }
            Spacer(Modifier.height(8.dp))
            StepDots(step.ordinal, SetupStep.entries.size)

            AnimatedContent(targetState = step, label = "step", modifier = Modifier.weight(1f)) { s ->
                val i = info(s)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(i.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Step ${s.ordinal + 1} of ${SetupStep.entries.size}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(i.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(i.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s == SetupStep.PAIR) {
                        Spacer(Modifier.height(20.dp))
                        PairSteps()
                    }
                }
            }

            when (step) {
                SetupStep.DEV_OPTIONS -> BigButton("Open About phone", onOpenAbout)
                SetupStep.WIFI -> BigButton("Open Wi-Fi settings", onOpenWifi)
                SetupStep.WIRELESS_DEBUGGING -> BigButton("Open Developer options", onOpenDev)
                SetupStep.PAIR -> PairActions(pairingStatus, onStartPairing, onPairCode, onOpenDev)
            }
            Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("This screen moves on by itself.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PairSteps() {
    val lines = listOf(
        "Tap Start pairing below.",
        "In Wireless debugging, tap Pair device with pairing code.",
        "Pull down notifications and type the 6-digit code into the HyperDebloat one.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lines.forEachIndexed { n, l ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("${n + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(l, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PairActions(status: String, onStart: () -> Unit, onCode: (String) -> Unit, onOpenDev: () -> Unit) {
    var typeHere by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (!typeHere) {
            BigButton("Start pairing") { onStart(); onOpenDev() }
            TextButton(onClick = { typeHere = true }) { Text("No box in the notification? Type the code here") }
        } else {
            Text("Use split screen with Settings, then type the code shown there.", style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            TextButton(onClick = { typeHere = false }) { Text("Back") }
        }
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until total) {
            Box(
                Modifier.weight(1f).height(4.dp).clip(CircleShape).background(
                    if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                )
            )
        }
    }
}
