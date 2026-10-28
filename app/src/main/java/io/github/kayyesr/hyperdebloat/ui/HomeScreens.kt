package io.github.kayyesr.hyperdebloat.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.kayyesr.hyperdebloat.ui.MainViewModel.Action
import java.io.File

@Composable
fun HomeScreen(vm: MainViewModel) {
    val dev by vm.device.collectAsState()
    val summary by vm.summary.collectAsState()
    val saveRaw by vm.saveRaw.collectAsState()

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("HyperDebloat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            dev?.let {
                Text("Phone: ${it.marketName} (${it.model})\n${it.osName} ${it.osBuild}  |  Android ${it.android}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(summary.ifEmpty { "Not scanned yet. Recommended and Custom scan the phone first." },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ActionCard("Recommended", "Remove every GREEN (safe) package after you review the list.", primary = true) { vm.run(Action.RECOMMENDED) }
            ActionCard("Custom", "Pick from the full colour-coded list of packages.") { vm.run(Action.CUSTOM) }
            ActionCard("Restore", "Put back anything removed or disabled - by this tool or any other.") { vm.run(Action.RESTORE) }

            Text("Before Recommended or Custom, open the apps you rely on (camera, gallery, file manager, payments) and then come back here, so the scan can see what they use.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.run(Action.RESCAN) }, modifier = Modifier.weight(1f)) { Text("Rescan phone") }
                OutlinedButton(onClick = vm::showReports, modifier = Modifier.weight(1f)) { Text("Reports") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Save raw phone output", style = MaterialTheme.typography.bodyMedium)
                    Text("Saves raw_adb_output.txt during scans. Attach it with the report when reporting a wrong rating.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = saveRaw, onCheckedChange = vm::setSaveRaw)
            }
            Text("Version ${vm.version}. Made by kayyesr, perfected by Claude.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActionCard(title: String, text: String, primary: Boolean = false, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onClick,
                colors = if (primary) ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White) else ButtonDefaults.buttonColors(),
                modifier = Modifier.weight(0.42f),
            ) { Text(title, maxLines = 1) }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.58f))
        }
    }
}

/** Progress log while the phone is scanned or packages are removed (the PowerShell console window). */
@Composable
fun BusyScreen(vm: MainViewModel, title: String) {
    val lines by vm.log.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    BackHandler(enabled = true) { /* an action is running; it ends by itself */ }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Keep HyperDebloat open until this finishes.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(lines) { l ->
                    Text(l, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = if (l.trimStart().startsWith("!")) Color(0xFFB26A00) else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** Saved reports, removal logs and came-back logs (the PowerShell reports folder). */
@Composable
fun ReportsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    BackHandler { vm.goHome() }
    val files = remember {
        val root = vm.storage.root
        val top = root.listFiles()?.filter { it.isFile } ?: emptyList()
        val inDirs = root.listFiles()?.filter { it.isDirectory && it.name != "cache" }?.flatMap { d -> d.listFiles()?.filter { it.isFile }?.toList() ?: emptyList() } ?: emptyList()
        (top + inDirs).filter { it.name != "removed_state.json" }.sortedByDescending { it.lastModified() }
    }
    fun share(f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (f.name.endsWith(".csv")) "text/csv" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::goHome) { Text("< Back") }
                Text("Reports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text("Every scan saves a report: phone model, HyperOS build, every rating, the phone's evidence, the lists' ratings and the final reasoning. Removals and came-back events are logged too. Copies are in Documents/HyperDebloat on the phone. Tap a file to share it.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            if (files.isEmpty()) Text("No reports yet.")
            LazyColumn {
                items(files) { f ->
                    Column(Modifier.fillMaxWidth().clickable { share(f) }.padding(vertical = 8.dp)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(f.parentFile?.takeIf { it != vm.storage.root }?.name ?: "", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
