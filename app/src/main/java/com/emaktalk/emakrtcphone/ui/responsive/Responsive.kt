package com.emaktalk.emakrtcphone.ui.responsive

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Responsive scaling for the whole app.
 *
 * The screens are authored against a baseline phone width ([BASELINE_WIDTH_DP]).
 * Rather than sprinkle every screen with `if (tablet) … else …`, we derive a
 * single [factor] from the width the content actually occupies and multiply the
 * hard-coded `dp`/`sp` literals by it via [scaled]. That keeps the layout
 * proportional from a 320 dp phone up to a tablet, instead of looking cramped on
 * small screens and oversized on large ones.
 *
 * On wide screens content is capped at [contentMaxWidth] (and centred by the
 * screens), so the scale is based on that capped width — a tablet gets a modest
 * bump, not fonts scaled to its full 800 dp.
 */
data class UiScale(
    /** Multiplier applied to `dp`/`sp` literals; clamped to [MIN_SCALE]..[MAX_SCALE]. */
    val factor: Float,
    /** Cap for the width of a content column; screens centre within this. */
    val contentMaxWidth: Dp,
)

/** Reference width the fixed `dp`/`sp` values were designed against (≈ a typical phone). */
private const val BASELINE_WIDTH_DP = 411f

/** Widest a single content column is ever allowed to grow (keeps tablets readable). */
private val CONTENT_MAX_WIDTH = 480.dp

/** Don't shrink below this on tiny screens, or grow past this on large ones. */
private const val MIN_SCALE = 0.80f
private const val MAX_SCALE = 1.20f

val LocalUiScale = staticCompositionLocalOf {
    // Sensible default for previews / any composable read outside the provider.
    UiScale(factor = 1f, contentMaxWidth = CONTENT_MAX_WIDTH)
}

/**
 * Computes the [UiScale] for the current window. Provide it once near the app
 * root via `CompositionLocalProvider(LocalUiScale provides rememberUiScale())`.
 */
@Composable
fun rememberUiScale(): UiScale {
    val config = LocalConfiguration.current
    // Scale off the width the content can actually use, not the raw screen width,
    // so a wide tablet (capped at CONTENT_MAX_WIDTH) doesn't get oversized type.
    val effectiveWidthDp = minOf(config.screenWidthDp.dp, CONTENT_MAX_WIDTH)
    val factor = (effectiveWidthDp / BASELINE_WIDTH_DP.dp).coerceIn(MIN_SCALE, MAX_SCALE)
    return UiScale(factor = factor, contentMaxWidth = CONTENT_MAX_WIDTH)
}

/** Scales a fixed [Dp] literal by the current [UiScale] factor. */
val Dp.scaled: Dp
    @Composable @ReadOnlyComposable get() = this * LocalUiScale.current.factor

/** Scales a fixed [TextUnit] (sp) literal by the current [UiScale] factor. */
val TextUnit.scaled: TextUnit
    @Composable @ReadOnlyComposable get() = this * LocalUiScale.current.factor

/**
 * Caps a content column's width to [UiScale.contentMaxWidth]. Combine with a
 * centring parent (e.g. a `Box(contentAlignment = TopCenter)`) so the column
 * sits in the middle of wide screens instead of stretching edge-to-edge.
 */
@Composable
@ReadOnlyComposable
fun Modifier.maxContentWidth(): Modifier = widthIn(max = LocalUiScale.current.contentMaxWidth)
