package com.emaktalk.emakrtcphone.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emaktalk.emakrtcphone.audio.AudioRoute
import com.emaktalk.emakrtcphone.network.NetworkType
import com.emaktalk.emakrtcphone.sip.CallState
import com.emaktalk.emakrtcphone.sip.CallUiState
import com.emaktalk.emakrtcphone.sip.ConnectionPhase
import com.emaktalk.emakrtcphone.sip.MediaPath
import com.emaktalk.emakrtcphone.ui.components.DialPad
import com.emaktalk.emakrtcphone.ui.responsive.maxContentWidth
import com.emaktalk.emakrtcphone.ui.responsive.scaled
import com.emaktalk.emakrtcphone.ui.theme.CallGreen
import com.emaktalk.emakrtcphone.ui.theme.HangupRed
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InCallScreen(viewModel: InCallViewModel = viewModel()) {
    val call by viewModel.callState.collectAsState()
    val current = call ?: return

    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(current.isConnected) {
        if (current.isConnected) {
            while (true) {
                delay(1000)
                seconds++
            }
        }
    }

    var showKeypad by remember { mutableStateOf(false) }
    var showRoutePicker by remember { mutableStateOf(false) }

    if (current.isAddingCall) {
        AddCallContent(
            heldTitle = current.heldCallTitle,
            onPlace = viewModel::placeSecondCall,
            onCancel = viewModel::cancelAddCall
        )
        return
    }

    if (current.isTransferring) {
        TransferCallContent(
            callTitle = current.title,
            onTransfer = viewModel::completeBlindTransfer,
            onCancel = viewModel::cancelTransfer
        )
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {

      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .maxContentWidth()
                .fillMaxSize()
                .padding(24.dp.scaled),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ConnectionBanner(current, onReconnect = viewModel::forceReconnect)
            MediaPathBanner(current.quality.mediaPath)

            Spacer(Modifier.height(if (current.connectionPhase == ConnectionPhase.Healthy) 48.dp.scaled else 16.dp.scaled))
            Text(
                text = current.title,
                fontSize = 28.sp.scaled,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusLabel(current, seconds),
                fontSize = 16.sp.scaled,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (current.isConnected) {
                Spacer(Modifier.height(12.dp))
                QualityRow(current)
            }

            if (current.heldCallTitle != null) {
                Spacer(Modifier.height(12.dp))
                HeldCallChip(title = current.heldCallTitle!!, onSwap = viewModel::swapCalls)
            }

            Spacer(Modifier.weight(1f))

            AnimatedVisibility(visible = showKeypad && current.isConnected) {
                DialPad(
                    onKeyClick = { viewModel.sendDtmf(it) },
                    onZeroLongPress = { viewModel.sendDtmf('+') },
                    modifier = Modifier.padding(bottom = 24.dp),
                    digitColor = MaterialTheme.colorScheme.onSurface,
                    letterColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                current.state == CallState.IncomingReceived -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        RoundActionButton(
                            background = HangupRed,
                            icon = { Icon(Icons.Filled.CallEnd, "Decline", tint = Color.White) },
                            onClick = viewModel::hangUp
                        )
                        RoundActionButton(
                            background = CallGreen,
                            icon = { Icon(Icons.Filled.Call, "Answer", tint = Color.White) },
                            onClick = viewModel::answer
                        )
                    }
                }
                else -> {

                    if (current.isConnected) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            if (current.hasSecondCall) {
                                ToggleControl(
                                    active = false,
                                    iconActive = { Icon(Icons.Filled.SwapCalls, "Swap") },
                                    iconInactive = { Icon(Icons.Filled.SwapCalls, "Swap") },
                                    label = "Swap",
                                    onClick = { viewModel.swapCalls() }
                                )
                                ToggleControl(
                                    active = false,
                                    iconActive = { Icon(Icons.Filled.CallMerge, "Merge") },
                                    iconInactive = { Icon(Icons.Filled.CallMerge, "Merge") },
                                    label = "Merge",
                                    enabled = current.canMerge,
                                    onClick = { viewModel.mergeCalls() }
                                )
                            } else {
                                ToggleControl(
                                    active = current.isOnHold,
                                    iconActive = { Icon(Icons.Filled.PlayArrow, "Resume") },
                                    iconInactive = { Icon(Icons.Filled.Pause, "Hold") },
                                    label = if (current.isOnHold) "Resume" else "Hold",
                                    onClick = { if (current.isOnHold) viewModel.resumeCall() else viewModel.holdCall() }
                                )
                                ToggleControl(
                                    active = false,
                                    iconActive = { Icon(Icons.Filled.PersonAdd, "Add call") },
                                    iconInactive = { Icon(Icons.Filled.PersonAdd, "Add call") },
                                    label = "Add call",
                                    onClick = { viewModel.addCall() }
                                )
                                ToggleControl(
                                    active = false,
                                    iconActive = { Icon(Icons.Filled.PhoneForwarded, "Transfer") },
                                    iconInactive = { Icon(Icons.Filled.PhoneForwarded, "Transfer") },
                                    label = "Transfer",
                                    onClick = { viewModel.beginTransfer() }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ToggleControl(
                            active = current.isMuted,
                            iconActive = { Icon(Icons.Filled.MicOff, "Unmute") },
                            iconInactive = { Icon(Icons.Filled.Mic, "Mute") },
                            label = "Mute",
                            onClick = { viewModel.toggleMute() }
                        )
                        ToggleControl(
                            active = showKeypad,
                            iconActive = { Icon(Icons.Filled.Dialpad, "Hide keypad") },
                            iconInactive = { Icon(Icons.Filled.Dialpad, "Keypad") },
                            label = "Keypad",
                            enabled = current.isConnected,
                            onClick = { showKeypad = !showKeypad }
                        )

                        if (current.availableRoutes.size > 2) {
                            ToggleControl(
                                active = current.audioRoute !is AudioRoute.Earpiece,
                                iconActive = { Icon(iconFor(current.audioRoute), "Audio output") },
                                iconInactive = { Icon(Icons.Filled.VolumeUp, "Audio output") },
                                label = current.audioRoute.label,
                                onClick = { showRoutePicker = true }
                            )
                        } else {
                            ToggleControl(
                                active = current.isSpeakerOn,
                                iconActive = { Icon(Icons.Filled.VolumeUp, "Speaker off") },
                                iconInactive = { Icon(Icons.Filled.VolumeUp, "Speaker on") },
                                label = "Speaker",
                                onClick = { viewModel.toggleSpeaker() }
                            )
                        }
                    }

                    Spacer(Modifier.height(32.dp))

                    RoundActionButton(
                        background = HangupRed,
                        size = 72.dp,
                        icon = {
                            Icon(
                                Icons.Filled.CallEnd,
                                "Hang up",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp.scaled)
                            )
                        },
                        onClick = viewModel::hangUp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
      }
    }

    if (showRoutePicker) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showRoutePicker = false },
            sheetState = sheetState
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Audio output",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                current.availableRoutes.forEach { route ->
                    AudioRouteRow(
                        route = route,
                        selected = route == current.audioRoute,
                        onClick = {
                            viewModel.setAudioRoute(route)
                            showRoutePicker = false
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeldCallChip(title: String, onSwap: () -> Unit) {
    Surface(
        onClick = onSwap,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Filled.Pause,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "On hold · $title",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(8.dp))
            Icon(
                Icons.Filled.SwapCalls,
                contentDescription = "Swap",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCallContent(
    heldTitle: String?,
    onPlace: (String) -> Unit,
    onCancel: () -> Unit
) {
    var number by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.maxContentWidth().fillMaxSize().padding(24.dp.scaled),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                }
                Spacer(Modifier.weight(1f))
                Text("Add call", fontSize = 18.sp.scaled, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }

            if (heldTitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "On hold · $heldTitle",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp.scaled))
            Text(
                text = number.ifEmpty { "Enter number" },
                fontSize = if (number.isEmpty()) 22.sp.scaled else 40.sp.scaled,
                fontWeight = if (number.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                color = if (number.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            DialPad(
                onKeyClick = { number += it },
                onZeroLongPress = { number = number.dropLast(1) + "+" },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
                digitColor = MaterialTheme.colorScheme.onSurface,
                letterColor = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Spacer(Modifier.size(64.dp))
                RoundActionButton(
                    background = CallGreen,
                    size = 72.dp,
                    icon = {
                        Icon(
                            Icons.Filled.Call, "Call",
                            tint = Color.White, modifier = Modifier.size(34.dp.scaled)
                        )
                    },
                    onClick = { if (number.isNotEmpty()) onPlace(number) }
                )
                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    if (number.isNotEmpty()) {
                        IconButton(onClick = { number = number.dropLast(1) }) {
                            Icon(
                                Icons.Filled.Backspace, "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
      }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferCallContent(
    callTitle: String,
    onTransfer: (String) -> Unit,
    onCancel: () -> Unit
) {
    var number by remember { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.maxContentWidth().fillMaxSize().padding(24.dp.scaled),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                }
                Spacer(Modifier.weight(1f))
                Text("Transfer call", fontSize = 18.sp.scaled, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Transfer $callTitle to…",
                fontSize = 13.sp.scaled,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(24.dp.scaled))
            Text(
                text = number.ifEmpty { "Enter number" },
                fontSize = if (number.isEmpty()) 22.sp.scaled else 40.sp.scaled,
                fontWeight = if (number.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                color = if (number.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            DialPad(
                onKeyClick = { number += it },
                onZeroLongPress = { number = number.dropLast(1) + "+" },
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
                digitColor = MaterialTheme.colorScheme.onSurface,
                letterColor = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Spacer(Modifier.size(64.dp))
                RoundActionButton(
                    background = CallGreen,
                    size = 72.dp,
                    icon = {
                        Icon(
                            Icons.Filled.PhoneForwarded, "Transfer",
                            tint = Color.White, modifier = Modifier.size(34.dp.scaled)
                        )
                    },
                    onClick = { if (number.isNotEmpty()) onTransfer(number) }
                )
                Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    if (number.isNotEmpty()) {
                        IconButton(onClick = { number = number.dropLast(1) }) {
                            Icon(
                                Icons.Filled.Backspace, "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
      }
    }
}

@Composable
private fun ConnectionBanner(call: CallUiState, onReconnect: () -> Unit) {
    val (text, color) = when (call.connectionPhase) {
        ConnectionPhase.Healthy -> return
        ConnectionPhase.Reconnecting -> "Reconnecting…" to MaterialTheme.colorScheme.tertiaryContainer
        ConnectionPhase.Lost -> "Connection lost — waiting for network" to MaterialTheme.colorScheme.errorContainer
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Surface(
            onClick = onReconnect,
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(
                text = "Reconnect",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun MediaPathBanner(path: MediaPath) {

    var announced by remember { mutableStateOf(MediaPath.Unknown) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        if (path != MediaPath.Unknown && path != announced) {
            announced = path
            visible = true
            delay(4000)
            visible = false
        }
    }

    AnimatedVisibility(visible = visible) {
        val (text, color) = when (announced) {
            MediaPath.Relay -> "Connected via TURN relay" to MaterialTheme.colorScheme.tertiaryContainer
            MediaPath.Direct -> "Direct connection" to MaterialTheme.colorScheme.secondaryContainer
            MediaPath.Unknown -> return@AnimatedVisibility
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(
                if (announced == MediaPath.Relay) Icons.Filled.SwapCalls else Icons.Filled.Wifi,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun QualityRow(call: CallUiState) {
    val bars = call.quality.bars
    val netIcon = when (call.networkType) {
        NetworkType.WIFI -> Icons.Filled.Wifi
        NetworkType.CELLULAR -> Icons.Filled.SignalCellularAlt
        else -> Icons.Filled.SignalCellular4Bar
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            netIcon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val loss = if (call.quality.lossRate > 1f) " · ${call.quality.lossRate.toInt()}% loss" else ""
        val path = when (call.quality.mediaPath) {
            MediaPath.Direct -> " · Direct"
            MediaPath.Relay -> " · TURN relay"
            MediaPath.Unknown -> ""
        }
        Text(
            text = qualityLabel(bars) + loss + path,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun qualityLabel(bars: Int): String = when (bars) {
    0 -> "Measuring…"
    1 -> "Poor"
    2 -> "Weak"
    3 -> "Fair"
    4 -> "Good"
    else -> "Excellent"
}

@Composable
private fun AudioRouteRow(route: AudioRoute, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Icon(iconFor(route), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(16.dp))
            Text(route.label, fontSize = 16.sp)
        }
    }
}

private fun iconFor(route: AudioRoute): ImageVector = when (route) {
    is AudioRoute.Earpiece -> Icons.Filled.PhoneInTalk
    is AudioRoute.Speaker -> Icons.Filled.Speaker
    is AudioRoute.WiredHeadset -> Icons.Filled.Headphones
    is AudioRoute.Bluetooth -> Icons.Filled.Bluetooth
}

private fun statusLabel(call: CallUiState, seconds: Int): String = when {
    call.state == CallState.IncomingReceived -> "Incoming call"
    call.isConnected -> formatDuration(seconds)
    call.isOutgoing -> "Calling…"
    else -> "Ringing…"
}

private fun formatDuration(seconds: Int): String {
    val mm = seconds / 60
    val ss = seconds % 60
    return "%02d:%02d".format(mm, ss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoundActionButton(
    background: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 64.dp
) {
    Surface(
        shape = CircleShape,
        color = background,
        onClick = onClick,
        modifier = modifier
            .size(size.scaled)
            .clip(CircleShape)
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToggleControl(
    active: Boolean,
    iconActive: @Composable () -> Unit,
    iconInactive: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val container = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = if (enabled) container else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            contentColor = contentColor,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(60.dp.scaled)
                .clip(CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (active) iconActive() else iconInactive()
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
