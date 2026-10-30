package io.github.kayyesr.hyperdebloat.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kayyesr.hyperdebloat.core.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.kayyesr.hyperdebloat.core.Row as PkgRow

/** App names and icons for display. */
interface AppLookup {
    fun label(pkg: String): String
    fun icon(pkg: String): Bitmap?
}

@Composable
fun PickScreen(vm: MainViewModel, spec: PickSpec) {
    PickContent(spec, vm.appInfo) { vm.finishPick(spec, it) }
}

/** The colour-coded tick list. [onDone] gets the ticked rows, or an empty list for Back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickContent(spec: PickSpec, apps: AppLookup, onDone: (List<PkgRow>) -> Unit) {
    // Preselection as in the PowerShell version: green background packages only.
    val ticked = remember(spec) {
        mutableStateMapOf<String, Boolean>().apply {
            for (r in spec.items) if (spec.preselectBackground && r.tier == Tier.SAFE && !r.userApp && !r.locked) put(r.pkg, true)
        }
    }
    var search by remember(spec) { mutableStateOf("") }
    var category by remember(spec) { mutableStateOf("") }
    var details by remember(spec) { mutableStateOf<PkgRow?>(null) }
    var legend by remember(spec) { mutableStateOf(false) }
    var labels by remember(spec) { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(spec) { labels = withContext(Dispatchers.IO) { spec.items.associate { it.pkg to apps.label(it.pkg) } } }

    fun selectable(r: PkgRow) = !r.locked && (spec.allowFlagged || r.tier != Tier.FLAGGED)
    fun toggle(r: PkgRow) { if (selectable(r)) { if (ticked[r.pkg] == true) ticked.remove(r.pkg) else ticked[r.pkg] = true } }

    val categories = remember(spec) {
        spec.items.map { it.category }.filter { it.isNotEmpty() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val visible by remember(spec) {
        derivedStateOf {
            val t = search.trim()
            spec.items.filter { r ->
                (category.isEmpty() || r.category == category) && (t.isEmpty() || r.pkg.contains(t, true) ||
                    r.description.contains(t, true) || (labels[r.pkg]?.contains(t, true) == true))
            }
        }
    }
    val count = ticked.count { it.value }
    val verb = spec.actionText.substringBefore(' ')

    BackHandler { onDone(emptyList()) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            // Top bar
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onDone(emptyList()) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(spec.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${spec.items.size} packages · $count selected", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { legend = true }) { Icon(Icons.Filled.Info, contentDescription = "What the colours mean", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            // Search
            TextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            // Categories
            if (categories.size > 1) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = category.isEmpty(), onClick = { category = "" }, label = { Text("All") }) }
                    items(categories) { c -> FilterChip(selected = category == c, onClick = { category = if (category == c) "" else c }, label = { Text(c) }) }
                }
            } else Spacer(Modifier.height(8.dp))

            // List
            Box(Modifier.weight(1f)) {
                if (visible.isEmpty()) {
                    Text(
                        if (search.isBlank()) "Nothing here." else "No match for \"${search.trim()}\".\n${spec.noMatchHint.ifEmpty { "Removed apps are under Restore." }}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                LazyColumn {
                    items(visible, key = { it.pkg }) { r ->
                        PackageItem(r, labels[r.pkg].orEmpty(), apps, ticked[r.pkg] == true, selectable(r), onToggle = { toggle(r) }, onInfo = { details = r })
                    }
                }
            }

            // Bottom bar
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                if (spec.items.any { it.tier == Tier.SAFE && selectable(it) && it.action == null }) {
                    TextButton(onClick = { spec.items.filter { it.tier == Tier.SAFE && selectable(it) }.forEach { ticked[it.pkg] = true } }) { Text("All safe") }
                }
                TextButton(onClick = { ticked.clear() }, enabled = count > 0) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onDone(spec.items.filter { ticked[it.pkg] == true && selectable(it) }) },
                    enabled = count > 0,
                    modifier = Modifier.height(48.dp),
                ) { Text(if (count > 0) "$verb $count" else verb, style = MaterialTheme.typography.titleSmall) }
            }
        }
    }

    details?.let { r ->
        ModalBottomSheet(onDismissRequest = { details = null }) {
            DetailsSheet(r, labels[r.pkg].orEmpty(), apps, ticked[r.pkg] == true, selectable(r)) { toggle(r); details = null }
        }
    }
    if (legend) {
        AlertDialog(
            onDismissRequest = { legend = false },
            title = { Text("What the colours mean") },
            text = { LegendList(spec) },
            confirmButton = { TextButton(onClick = { legend = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun LegendList(spec: PickSpec) {
    val restore = spec.items.any { it.action != null }
    val lines = if (restore) listOf(
        tierStyle(Tier.SAFE).copy(label = "Restore / Re-enable / Unfreeze") to "Comes straight back.",
        tierStyle(Tier.MODERATE).copy(label = "Play Store") to "Was fully uninstalled. Opens its Play Store page.",
    ) else listOf(
        tierStyle(Tier.SAFE) to "Nothing else depends on it.",
        tierStyle(Tier.MODERATE) to "A small feature may stop working.",
        tierStyle(Tier.HIGH) to "A main feature may stop working.",
        tierStyle(Tier.UNSAFE) to "Can stop the phone working. Avoid.",
        tierStyle(Tier.FLAGGED) to "No list knows it. Avoid unless you know it.",
        tierStyle(Tier.APP) to "A normal app. Never ticked for you.",
        TierStyle("Needed", Color(0xFF9B2C2C)) to "HyperDebloat needs it to run. Can't be ticked.",
    )
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((s, text) in lines) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(110.dp)) { Badge(s) }
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("Tap ⓘ on any row to see why it got its rating.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppIcon(pkg: String, label: String, apps: AppLookup, size: Int = 40) {
    var icon by remember(pkg) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pkg) { icon = withContext(Dispatchers.IO) { apps.icon(pkg) } }
    val bmp = icon
    if (bmp != null) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size.dp))
    } else {
        Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Text((label.ifEmpty { pkg.substringAfterLast('.') }).take(1).uppercase(), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PackageItem(r: PkgRow, label: String, apps: AppLookup, checked: Boolean, enabled: Boolean, onToggle: () -> Unit, onInfo: () -> Unit) {
    val style = badgeFor(r)
    val bg = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(bg).clickable(enabled = enabled, onClick = onToggle).height(72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(style.colour))
        Spacer(Modifier.width(12.dp))
        AppIcon(r.pkg, label, apps)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label.ifEmpty { r.pkg.substringAfterLast('.') }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(r.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Badge(style)
                if (r.description.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        IconButton(onClick = onInfo) { Icon(Icons.Filled.Info, contentDescription = "Details", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

@Composable
private fun DetailsSheet(r: PkgRow, label: String, apps: AppLookup, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(r.pkg, label, apps, size = 48)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label.ifEmpty { r.pkg.substringAfterLast('.') }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(r.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Badge(badgeFor(r))
        }
        if (r.description.isNotEmpty()) Field("What it does", r.description)
        Field("What the phone says", r.phone)
        if (r.lists.isNotEmpty()) Field("What the lists say", r.lists)
        Field("Why this rating", r.reason)
        if (enabled) {
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (checked) "Unselect" else "Select") }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
