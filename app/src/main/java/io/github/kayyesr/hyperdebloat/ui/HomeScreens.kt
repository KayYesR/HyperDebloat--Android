package io.github.kayyesr.hyperdebloat.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.kayyesr.hyperdebloat.core.DeviceInfo
import io.github.kayyesr.hyperdebloat.core.Tier
import io.github.kayyesr.hyperdebloat.ui.MainViewModel.Action
import java.io.File

@Composable
fun HomeScreen(vm: MainViewModel) {
    val dev by vm.device.collectAsState()
    val counts by vm.counts.collectAsState()
    val lastScan by vm.lastScan.collectAsState()
    val saveRaw by vm.saveRaw.collectAsState()
    val noWifi by vm.noWifi.collectAsState()
    HomeContent(
        device = dev, counts = counts, lastScan = lastScan, saveRaw = saveRaw, version = vm.version,
        noWifi = noWifi, onNoWifi = vm::setNoWifi,
        onRecommended = { vm.run(Action.RECOMMENDED) },
        onCustom = { vm.run(Action.CUSTOM) },
        onRestore = { vm.run(Action.RESTORE) },
        onRescan = { vm.run(Action.RESCAN) },
        onReports = vm::showReports,
        onSaveRaw = vm::setSaveRaw,
    )
}

@Composable
fun HomeContent(
    device: DeviceInfo?,
    counts: Map<Tier, Int>?,
    lastScan: String,
    saveRaw: Boolean,
    version: String,
    onRecommended: () -> Unit,
    onCustom: () -> Unit,
    onRestore: () -> Unit,
    onRescan: () -> Unit,
    onReports: () -> Unit,
    onSaveRaw: (Boolean) -> Unit,
    noWifi: Boolean = false,
    onNoWifi: (Boolean) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HyperDebloat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRescan) { Icon(Icons.Filled.Refresh, contentDescription = "Rescan") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Reports") }, onClick = { menu = false; onReports() })
                        DropdownMenuItem(
                            text = { Text("Work without Wi-Fi") },
                            trailingIcon = { Checkbox(checked = noWifi, onCheckedChange = null) },
                            onClick = { menu = false; onNoWifi(!noWifi) },
                        )
                        DropdownMenuItem(
                            text = { Text("Save raw output for bug reports") },
                            trailingIcon = { Checkbox(checked = saveRaw, onCheckedChange = null) },
                            onClick = { onSaveRaw(!saveRaw) },
                        )
                        DropdownMenuItem(text = { Text("Version $version", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = { menu = false })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PhoneCard(device, counts, lastScan)
            if (noWifi) {
                Spacer(Modifier.height(8.dp))
                Text("Works without Wi-Fi. After a restart, it needs Wi-Fi once.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(20.dp))

            ActionCard(Icons.Filled.CheckCircle, "Recommended", "Remove safe junk. You check the list first.", primary = true, onClick = onRecommended)
            Spacer(Modifier.height(12.dp))
            ActionCard(Icons.Filled.Search, "Custom", "See everything, colour-coded, and pick.", onClick = onCustom)
            Spacer(Modifier.height(12.dp))
            ActionCard(Icons.Filled.Refresh, "Restore", "Bring back anything removed.", onClick = onRestore)

            Spacer(Modifier.height(20.dp))
            Text("Tip: open the apps you use most before scanning.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("Made by kayyesr, perfected by Claude.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhoneCard(device: DeviceInfo?, counts: Map<Tier, Int>?, lastScan: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PhoneIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(device?.marketName ?: "Phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (device != null) {
                        Text("${device.osBuild} · Android ${device.android}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (counts == null) {
                Text("Not scanned yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    for (t in listOf(Tier.SAFE, Tier.MODERATE, Tier.HIGH, Tier.UNSAFE, Tier.FLAGGED, Tier.APP)) {
                        val s = tierStyle(t)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${counts[t] ?: 0}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = s.colour)
                            Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
                if (lastScan.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Scanned at $lastScan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, text: String, primary: Boolean = false, onClick: () -> Unit) {
    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val sub = if (primary) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = bg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (primary) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (primary) fg else MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = fg)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = sub)
            }
        }
    }
}

/** Shown while the phone is scanned or packages are removed. Details are hidden unless asked for. */
@Composable
fun BusyScreen(vm: MainViewModel, title: String) {
    val lines by vm.log.collectAsState()
    BackHandler(enabled = true) { /* an action is running; it ends by itself */ }
    BusyContent(title, lines)
}

@Composable
fun BusyContent(title: String, lines: List<String>) {
    var details by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, details) { if (details && lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }
    val current = lines.lastOrNull { it.isNotBlank() }?.trim()?.removePrefix("== ") ?: ""
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(if (details) 0.2f else 1f))
            CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 5.dp)
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(current, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text("Keep the app open.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details") }
            if (details) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = list, modifier = Modifier.padding(10.dp)) {
                        items(lines) { l ->
                            Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = if (l.trimStart().startsWith("!")) Color(0xFFE07A10) else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            } else Spacer(Modifier.weight(1f))
        }
    }
}

/** Saved reports, removal logs and came-back logs. Tap to share. */
@Composable
fun ReportsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    BackHandler { vm.goHome() }
    val files = remember {
        val root = vm.storage.root
        val top = root.listFiles()?.filter { it.isFile } ?: emptyList()
        val inDirs = root.listFiles()?.filter { it.isDirectory && it.name != "cache" }
            ?.flatMap { d -> d.listFiles()?.filter { it.isFile }?.toList() ?: emptyList() } ?: emptyList()
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::goHome) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Reports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Text("One report per scan. Copies are also in Documents/HyperDebloat.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No reports yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            LazyColumn {
                items(files) { f ->
                    Row(Modifier.fillMaxWidth().clickable { share(f) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            f.parentFile?.takeIf { it != vm.storage.root }?.let {
                                Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
