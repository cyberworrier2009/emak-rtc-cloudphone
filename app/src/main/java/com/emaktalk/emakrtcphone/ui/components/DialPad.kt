package com.emaktalk.emakrtcphone.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emaktalk.emakrtcphone.ui.responsive.maxContentWidth
import com.emaktalk.emakrtcphone.ui.theme.DialerMuted
import com.emaktalk.emakrtcphone.ui.theme.DialerOnSurface

/** A single dialpad key (the primary digit plus the letters underneath it). */
private data class DialKey(val digit: Char, val letters: String)

private val DIAL_KEYS = listOf(
    DialKey('1', ""),
    DialKey('2', "ABC"),
    DialKey('3', "DEF"),
    DialKey('4', "GHI"),
    DialKey('5', "JKL"),
    DialKey('6', "MNO"),
    DialKey('7', "PQRS"),
    DialKey('8', "TUV"),
    DialKey('9', "WXYZ"),
    DialKey('*', ""),
    DialKey('0', "+"),
    DialKey('#', "")
)

private const val COLS = 3
private const val ROWS = 4
private val ROW_SPACING = 6.dp

/** Upper bound on a key so the pad doesn't balloon on tablets / tall screens. */
private val MAX_KEY = 84.dp
/** Preferred lower bound for tappability when height is unbounded. */
private val MIN_KEY = 44.dp

/**
 * Classic 4x3 softphone keypad.
 *
 * Keys are sized to the largest square that fits the *available* space — width
 * AND height — so the pad shrinks to leave room for the number display and call
 * button on short screens, and is capped so it doesn't grow huge on big ones.
 * Give it a bounded height (e.g. `Modifier.weight(1f)` inside a Column) to let
 * it flex; with unbounded height it falls back to a width-derived size.
 *
 * @param onKeyClick invoked with the pressed character ('0'-'9', '*', '#').
 * @param onZeroLongPress invoked when '0' is long-pressed (inserts '+').
 */
@Composable
fun DialPad(
    onKeyClick: (Char) -> Unit,
    onZeroLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    digitColor: Color = DialerOnSurface,
    letterColor: Color = DialerMuted
) {
    BoxWithConstraints(
        modifier = modifier
            .maxContentWidth()
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val byWidth = maxWidth / COLS
        val keySize = if (constraints.hasBoundedHeight) {
            // Fit all four rows within the slot we were given — never overflow it,
            // so the call/transfer button below always stays visible.
            val byHeight = (maxHeight - ROW_SPACING * (ROWS - 1)) / ROWS
            minOf(byWidth, byHeight, MAX_KEY)
        } else {
            // Unbounded height (e.g. inside a scroll/animated container): derive
            // from width, but keep keys comfortably tappable.
            minOf(byWidth, MAX_KEY).coerceAtLeast(MIN_KEY)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ROW_SPACING)
        ) {
            DIAL_KEYS.chunked(COLS).forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowKeys.forEach { key ->
                        DialButton(
                            key = key,
                            size = keySize,
                            onClick = { onKeyClick(key.digit) },
                            onLongClick = if (key.digit == '0') onZeroLongPress else null,
                            digitColor = digitColor,
                            letterColor = letterColor
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialButton(
    key: DialKey,
    size: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    digitColor: Color,
    letterColor: Color
) {
    // Borderless keys: just the digit and letters floating on the dark canvas,
    // with a circular ripple bound to the touch target. Type is derived from the
    // key size so it stays proportional as the pad flexes.
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = key.digit.toString(),
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Normal,
                color = digitColor,
                textAlign = TextAlign.Center
            )
            if (key.letters.isNotEmpty()) {
                Text(
                    text = key.letters,
                    fontSize = (size.value * 0.13f).sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium,
                    color = letterColor
                )
            }
        }
    }
}
