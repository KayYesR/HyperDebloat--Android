package io.github.kayyesr.hyperdebloat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kayyesr.hyperdebloat.core.Row as PkgRow
import io.github.kayyesr.hyperdebloat.core.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The colour-coded tick list (Show-SelectionGrid): category filter, search, tick all GREEN, clear all. */
@Composable
fun PickScreen(vm: MainViewModel, spec: PickSpec) {
    // Ticked state per package; preselection as in the PowerShell version.
    val ticked = remember(spec) {
        mutableStateMapOf<String, Boolean>().apply {
            for (it in spec.items) {
                val pick = spec.preselectBackground && it.tier == Tier.SAFE && !it.userApp && it.tier != Tier.FLAGGED && !it.locked
                if (pick) put(it.pkg, true)
            }
        }
    }
    var search by remember(spec) { mutableStateOf("") }
    var category by remember(spec) { mutableStateOf("") }
    var details by remember(spec) { mutableStateOf<PkgRow?>(null) }
    var showLegend by remember(spec) { mutableStateOf(true) }
    var nothingTicked by remember(spec) { mutableStateOf(false) }

    fun selectable(r: PkgRow) = !r.locked && (spec.allowFlagged || r.tier != Tier.FLAGGED)

    val categories = remember(spec) {
        spec.items.map { it.category }.filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { c -> c to spec.items.count { it.category == c } }
    }
    // App names load in the background; search also matches them once loaded.
    var labels by remember(spec) { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(spec) { labels = withContext(Dispatchers.IO) { spec.items.associate { it.pkg to vm.appInfo.label(it.pkg) } } }
    val visible by remember(spec) {
        derivedStateOf {
            val t = search.trim()
            spec.items.filter { r ->
                val textOk = t.isEmpty() || r.pkg.contains(t, true) || r.description.contains(t, true) ||
                    r.risk.contains(t, true) || r.category.contains(t, true) || (labels[r.pkg]?.contains(t, true) == true)
                textOk && (category.isEmpty() || r.category == category)
            }
        }
    }
    val count = ticked.count { it.value }

    BackHandler { vm.finishPick(spec, emptyList()) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.finishPick(spec, emptyList()) }) { Text("< Back") }
                Text(spec.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { showLegend = !showLegend }) { Text(if (showLegend) "Hide help" else "Help") }
            }
            if (showLegend) {
                Text(spec.legend + "\nTap a row to tick or untick it, then press '${spec.actionText}'. Tap (i) for the full reasoning.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).heightIn(max = 150.dp).verticalScroll(rememberScrollState()))
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryMenu(categories, category, spec.items.size) { category = it }
                OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                    placeholder = { Text("Search") }, modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
            if (visible.isEmpty()) {
                val hint = spec.noMatchHint.ifEmpty { "Already-removed packages are under Restore." }
                Text("No match for '${search.trim()}'. $hint", color = Color(0xFFB22222), modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.weight(1f)) {
                items(visible, key = { it.pkg }) { r ->
                    PackageRow(vm, r, ticked[r.pkg] == true, selectable(r),
                        onToggle = { if (selectable(r)) { if (ticked[r.pkg] == true) ticked.remove(r.pkg) else ticked[r.pkg] = true } },
                        onInfo = { details = r })
                }
            }
            HorizontalDivider()
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$count ticked", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { spec.items.filter { it.tier == Tier.SAFE && selectable(it) }.forEach { ticked[it.pkg] = true } }) { Text("All GREEN") }
                OutlinedButton(onClick = { ticked.clear() }) { Text("Clear") }
            }
            Button(
                onClick = {
                    val picked = spec.items.filter { ticked[it.pkg] == true && selectable(it) }
                    if (picked.isEmpty()) nothingTicked = true else vm.finishPick(spec, picked)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) { Text(spec.actionText) }
        }
    }

    details?.let { r ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(vm.appInfo.label(r.pkg).ifEmpty { r.pkg }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("Package", r.pkg)
                    Field("Risk", r.risk + if (r.category.isNotEmpty()) "  (${r.category})" else "")
                    if (r.description.isNotEmpty()) Field("What it does", r.description)
                    Field("Phone says", r.phone)
                    if (r.lists.isNotEmpty()) Field("Lists say", r.lists)
                    Field("Final reasoning", r.reason)
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } },
        )
    }
    if (nothingTicked) {
        AlertDialog(
            onDismissRequest = { nothingTicked = false },
            title = { Text("HyperDebloat") },
            text = { Text("Nothing is ticked.\n\nTap a row to tick it, then press '${spec.actionText}'. Use '< Back' to return without changing anything.") },
            confirmButton = { TextButton(onClick = { nothingTicked = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CategoryMenu(categories: List<Pair<String, Int>>, current: String, total: Int, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(if (current.isEmpty()) "All categories" else current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All categories ($total)") }, onClick = { onPick(""); open = false })
            for ((c, n) in categories) DropdownMenuItem(text = { Text("$c ($n)") }, onClick = { onPick(c); open = false })
        }
    }
}

@Composable
private fun PackageRow(vm: MainViewModel, r: PkgRow, checked: Boolean, enabled: Boolean, onToggle: () -> Unit, onInfo: () -> Unit) {
    var label by remember(r.pkg) { mutableStateOf("") }
    var icon by remember(r.pkg) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(r.pkg) {
        label = withContext(Dispatchers.IO) { vm.appInfo.label(r.pkg) }
        icon = withContext(Dispatchers.IO) { vm.appInfo.icon(r.pkg) }
    }
    val dark = Color(0xFF1A1A1A)
    Row(
        Modifier.fillMaxWidth().background(tierColour(r.tier)).clickable(enabled = enabled, onClick = onToggle).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled,
            colors = CheckboxDefaults.colors(uncheckedColor = dark, checkmarkColor = Color.White, checkedColor = Color(0xFF1B5E20)))
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            icon?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.risk, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(chipColour(r.tier, r.locked), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                Spacer(Modifier.width(6.dp))
                Text(label.ifEmpty { r.pkg }, color = dark, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (label.isNotEmpty()) Text(r.pkg, color = dark, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(r.category, r.description).filter { it.isNotEmpty() }.joinToString(" - ")
            if (r.locked) Text("Locked: HyperDebloat needs it while running", color = Color(0xFF8B0000), style = MaterialTheme.typography.bodySmall)
            if (sub.isNotEmpty()) Text(sub, color = Color(0xFF444444), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onInfo) { Icon(Icons.Filled.Info, contentDescription = "Details", tint = Color(0xFF333333)) }
    }
    HorizontalDivider(color = Color(0x22000000))
}

private fun chipColour(t: Tier, locked: Boolean): Color = if (locked) Color(0xFF7F0000) else when (t) {
    Tier.SAFE -> Color(0xFF2E7D32)
    Tier.MODERATE -> Color(0xFF9E8600)
    Tier.HIGH -> Color(0xFFD86400)
    Tier.UNSAFE -> Color(0xFFC62828)
    Tier.FLAGGED -> Color(0xFF616161)
    Tier.APP -> Color(0xFF1565C0)
}
