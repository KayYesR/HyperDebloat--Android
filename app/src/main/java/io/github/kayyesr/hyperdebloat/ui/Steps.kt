package io.github.kayyesr.hyperdebloat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Turned off in screenshot tests, where a never-ending animation would never settle. */
val LocalAnimate = compositionLocalOf { true }

enum class StepState { DONE, CURRENT, LATER }

/**
 * One numbered step in a checklist. The current step is highlighted (a pulsing border) and shows its
 * description and button; done steps show a tick and a short result; later steps are greyed out.
 */
@Composable
fun StepCard(
    number: Int,
    title: String,
    state: StepState,
    description: String,
    doneText: String = "",
    button: String? = null,
    onClick: () -> Unit = {},
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    /** Tapping a done or later step does this (for example "check again"); null = not tappable. */
    onCardClick: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    val primary = MaterialTheme.colorScheme.primary
    val pulse = if (state == StepState.CURRENT && LocalAnimate.current) {
        val t = rememberInfiniteTransition(label = "pulse")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
        a
    } else 1f
    val container = when (state) {
        StepState.CURRENT -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (state == StepState.CURRENT) BorderStroke(2.dp, primary.copy(alpha = pulse)) else null,
        modifier = Modifier.fillMaxWidth().animateContentSize().then(
            if (state != StepState.CURRENT && onCardClick != null) Modifier.clickable(onClick = onCardClick) else Modifier
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val circle = when (state) {
                    StepState.DONE -> Color(0xFF2E9E5B)
                    StepState.CURRENT -> primary
                    StepState.LATER -> MaterialTheme.colorScheme.outlineVariant
                }
                Box(Modifier.size(32.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
                    if (state == StepState.DONE) Icon(Icons.Filled.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(20.dp))
                    else Text("$number", color = if (state == StepState.CURRENT) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = if (state == StepState.LATER) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (state == StepState.DONE && doneText.isNotEmpty()) {
                        Text(doneText, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E9E5B))
                    }
                    if (state == StepState.CURRENT) {
                        Text("Do this now", style = MaterialTheme.typography.labelMedium, color = primary.copy(alpha = 0.5f + pulse / 2))
                    }
                }
            }
            if (state == StepState.CURRENT) {
                Spacer(Modifier.height(10.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                extra()
                if (button != null) {
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(button, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (secondary != null) {
                    TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondary) }
                }
            }
        }
    }
}

/** A secondary option with a one-line explanation of what it does. */
@Composable
fun OptionRow(title: String, description: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The one main button on a screen. It gently pulses so it is easy to find. */
@Composable
fun PulsingButton(text: String, onClick: () -> Unit) {
    val scale = if (LocalAnimate.current) {
        val t = rememberInfiniteTransition(label = "pulse")
        val v by t.animateFloat(1f, 1.03f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
        v
    } else 1f
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Room left at the end of scrolling screens, so the progress bar never covers the last item. */
val BottomBarSpace = 72.dp

/**
 * The bar at the bottom of every screen while something is happening: what it is doing and, when known, how far along.
 * A moving bar means the app is working, never stuck.
 */
@androidx.compose.runtime.Composable
fun ProgressStrip(progress: Progress?, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = progress != null,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
        modifier = modifier,
    ) {
        // Keeps the last text while sliding away.
        var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(progress) }
        if (progress != null) shown = progress
        val p = shown ?: return@AnimatedVisibility
        androidx.compose.material3.Surface(
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        ) {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Text(
                        p.label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                    )
                    p.fraction?.let {
                        androidx.compose.material3.Text(
                            "${(it * 100).toInt()}%", style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                val bar = androidx.compose.ui.Modifier.fillMaxWidth().height(6.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                if (p.fraction != null) {
                    val anim by androidx.compose.animation.core.animateFloatAsState(p.fraction, label = "progress")
                    val animate = LocalAnimate.current
                    val target = p.fraction
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (animate) anim else target }, modifier = bar,
                        drawStopIndicator = {}, gapSize = 0.dp,
                    )
                } else if (LocalAnimate.current) {
                    androidx.compose.material3.LinearProgressIndicator(modifier = bar, gapSize = 0.dp)
                } else {
                    // Screenshot tests: a still bar.
                    androidx.compose.material3.LinearProgressIndicator(progress = { 0.35f }, modifier = bar, drawStopIndicator = {}, gapSize = 0.dp)
                }
            }
        }
    }
}
