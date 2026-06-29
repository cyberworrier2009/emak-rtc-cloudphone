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

data class UiScale(

    val factor: Float,

    val contentMaxWidth: Dp,
)

private const val BASELINE_WIDTH_DP = 411f

private val CONTENT_MAX_WIDTH = 480.dp

private const val MIN_SCALE = 0.80f
private const val MAX_SCALE = 1.20f

val LocalUiScale = staticCompositionLocalOf {

    UiScale(factor = 1f, contentMaxWidth = CONTENT_MAX_WIDTH)
}

@Composable
fun rememberUiScale(): UiScale {
    val config = LocalConfiguration.current

    val effectiveWidthDp = minOf(config.screenWidthDp.dp, CONTENT_MAX_WIDTH)
    val factor = (effectiveWidthDp / BASELINE_WIDTH_DP.dp).coerceIn(MIN_SCALE, MAX_SCALE)
    return UiScale(factor = factor, contentMaxWidth = CONTENT_MAX_WIDTH)
}

val Dp.scaled: Dp
    @Composable @ReadOnlyComposable get() = this * LocalUiScale.current.factor

val TextUnit.scaled: TextUnit
    @Composable @ReadOnlyComposable get() = this * LocalUiScale.current.factor

@Composable
@ReadOnlyComposable
fun Modifier.maxContentWidth(): Modifier = widthIn(max = LocalUiScale.current.contentMaxWidth)
