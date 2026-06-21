package com.emaktalk.emakrtcphone.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Classic 4x3 softphone keypad.
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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DIAL_KEYS.chunked(3).forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                rowKeys.forEach { key ->
                    DialButton(
                        key = key,
                        onClick = { onKeyClick(key.digit) },
                        onLongClick = if (key.digit == '0') onZeroLongPress else null,
                        digitColor = digitColor,
                        letterColor = letterColor,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialButton(
    key: DialKey,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    digitColor: Color,
    letterColor: Color,
    modifier: Modifier = Modifier
) {
    // Borderless keys: just the digit and letters floating on the dark canvas,
    // with a circular ripple bound to the touch target.
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = key.digit.toString(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Normal,
                color = digitColor,
                textAlign = TextAlign.Center
            )
            if (key.letters.isNotEmpty()) {
                Text(
                    text = key.letters,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium,
                    color = letterColor
                )
            }
        }
    }
}
