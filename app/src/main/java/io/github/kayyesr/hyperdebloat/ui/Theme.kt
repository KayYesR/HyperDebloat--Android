package io.github.kayyesr.hyperdebloat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kayyesr.hyperdebloat.core.Row
import io.github.kayyesr.hyperdebloat.core.Tier

private val Light = lightColorScheme(
    primary = Color(0xFF2E7D4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBD7),
    onPrimaryContainer = Color(0xFF0B3B21),
    secondaryContainer = Color(0xFFE3EDE6),
    onSecondaryContainer = Color(0xFF1C2B22),
    background = Color(0xFFF7F9F7),
    surface = Color(0xFFF7F9F7),
    surfaceVariant = Color(0xFFE6ECE8),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F4F1),
    surfaceContainerHigh = Color(0xFFEAEFEB),
    onSurfaceVariant = Color(0xFF55615A),
    outlineVariant = Color(0xFFD5DDD8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD3A0),
    onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF1C5235),
    onPrimaryContainer = Color(0xFFCDEBD7),
    secondaryContainer = Color(0xFF26332B),
    onSecondaryContainer = Color(0xFFD9E6DD),
    background = Color(0xFF101412),
    surface = Color(0xFF101412),
    surfaceVariant = Color(0xFF2A322D),
    surfaceContainerLow = Color(0xFF181D1A),
    surfaceContainer = Color(0xFF1C221E),
    surfaceContainerHigh = Color(0xFF242B26),
    onSurfaceVariant = Color(0xFFB7C2BB),
    outlineVariant = Color(0xFF38423C),
)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

/** Plain-word label and colour for each rating. */
data class TierStyle(val label: String, val colour: Color)

fun tierStyle(tier: Tier): TierStyle = when (tier) {
    Tier.SAFE -> TierStyle("Safe", Color(0xFF2E9E5B))
    Tier.MOSTLY -> TierStyle("Mostly okay", Color(0xFF6AAE3A))
    Tier.MODERATE -> TierStyle("Minor risk", Color(0xFFC9A100))
    Tier.HIGH -> TierStyle("Risky", Color(0xFFE07A10))
    Tier.UNSAFE -> TierStyle("Unsafe", Color(0xFFD63B3B))
    Tier.FLAGGED -> TierStyle("Unknown", Color(0xFF8A8F8C))
    Tier.APP -> TierStyle("App", Color(0xFF3B7DD8))
}

/** Badge text for a row: the rating, or the restore action. */
fun badgeFor(r: Row): TierStyle {
    val base = tierStyle(r.tier)
    if (r.locked) return TierStyle("Needed", Color(0xFF9B2C2C))
    return when (r.risk) {
        "RESTORE" -> base.copy(label = "Restore")
        "RE-ENABLE" -> base.copy(label = "Re-enable")
        "UNFREEZE" -> base.copy(label = "Unfreeze")
        "PLAY STORE" -> base.copy(label = "Play Store")
        else -> base
    }
}

@Composable
fun Badge(style: TierStyle, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(style.colour.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(style.label, color = style.colour, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** Material "wifi" icon (not in the core icon set). */
val WifiIcon: ImageVector by lazy {
    ImageVector.Builder("Wifi", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(1f, 9f); lineToRelative(2f, 2f)
            curveToRelative(4.97f, -4.97f, 13.03f, -4.97f, 18f, 0f); lineToRelative(2f, -2f)
            curveTo(16.93f, 2.93f, 7.08f, 2.93f, 1f, 9f); close()
            moveTo(9f, 17f); lineToRelative(3f, 3f); lineToRelative(3f, -3f)
            curveToRelative(-1.65f, -1.66f, -4.34f, -1.66f, -6f, 0f); close()
            moveTo(5f, 13f); lineToRelative(2f, 2f)
            curveToRelative(2.76f, -2.76f, 7.24f, -2.76f, 10f, 0f); lineToRelative(2f, -2f)
            curveTo(15.14f, 9.14f, 8.87f, 9.14f, 5f, 13f); close()
        }
    }.build()
}

/** Material "phone_android" style icon, simplified. */
val PhoneIcon: ImageVector by lazy {
    ImageVector.Builder("Phone", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 1f); horizontalLineTo(8f)
            curveTo(6.34f, 1f, 5f, 2.34f, 5f, 4f); verticalLineToRelative(16f)
            curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f); horizontalLineToRelative(8f)
            curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f); verticalLineTo(4f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f); close()
            moveTo(14f, 21f); horizontalLineToRelative(-4f); verticalLineToRelative(-1f); horizontalLineToRelative(4f); verticalLineToRelative(1f); close()
            moveTo(17.25f, 18f); horizontalLineTo(6.75f); verticalLineTo(4f); horizontalLineToRelative(10.5f); verticalLineToRelative(14f); close()
        }
    }.build()
}
