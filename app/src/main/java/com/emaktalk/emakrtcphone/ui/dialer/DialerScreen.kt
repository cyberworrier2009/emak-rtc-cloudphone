package com.emaktalk.emakrtcphone.ui.dialer

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emaktalk.emakrtcphone.R
import com.emaktalk.emakrtcphone.sip.RegistrationState
import com.emaktalk.emakrtcphone.ui.components.DialPad
import com.emaktalk.emakrtcphone.ui.responsive.maxContentWidth
import com.emaktalk.emakrtcphone.ui.responsive.scaled
import com.emaktalk.emakrtcphone.ui.theme.BrandIndigo
import com.emaktalk.emakrtcphone.ui.theme.CallGreenVivid
import com.emaktalk.emakrtcphone.ui.theme.CallGreenLight
import com.emaktalk.emakrtcphone.ui.theme.DialerBackground
import com.emaktalk.emakrtcphone.ui.theme.DialerMuted
import com.emaktalk.emakrtcphone.ui.theme.DialerOnSurface
import com.emaktalk.emakrtcphone.ui.theme.DialerSurface
import com.emaktalk.emakrtcphone.ui.theme.HangupRed
import com.emaktalk.emakrtcphone.ui.theme.SuggestionText
import com.emaktalk.emakrtcphone.ui.theme.SuggestionTint

@Composable
fun DialerScreen(
    onOpenAccount: () -> Unit,
    onLogout: () -> Unit,
    viewModel: DialerViewModel = viewModel()
) {
    val number by viewModel.number.collectAsState()
    val registration by viewModel.registrationState.collectAsState()
    val callError by viewModel.callError.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(callError) {
        callError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearCallError()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onCall()
        } else {
            Toast.makeText(context, "Microphone permission is required to call", Toast.LENGTH_LONG)
                .show()
        }
    }

    val placeCall: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onCall()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val suggestion = ContactDirectory.match(number)

    Scaffold(
        containerColor = DialerBackground,
        bottomBar = { DialerBottomBar(onOpenSettings = onOpenAccount) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),

            contentAlignment = Alignment.TopCenter
        ) {
        Column(
            modifier = Modifier
                .maxContentWidth()
                .fillMaxSize()
                .padding(horizontal = 24.dp.scaled)
        ) {
            DialerHeader(registration = registration)

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.dialer_title),
                fontSize = 34.sp.scaled,
                fontWeight = FontWeight.Bold,
                color = DialerOnSurface
            )
            Text(
                text = stringResource(R.string.dialer_subtitle),
                fontSize = 14.sp.scaled,
                color = DialerMuted,
                modifier = Modifier.padding(top = 2.dp)
            )

            NumberDisplay(
                number = number,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp.scaled)
            )

            if (suggestion != null) {
                ContactSuggestionChip(
                    suggestion = suggestion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )
            }

            DialPad(
                onKeyClick = viewModel::onKeyPress,
                onZeroLongPress = viewModel::onZeroLongPress,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 12.dp)
            )

            CallActionRow(
                showBackspace = number.isNotEmpty(),
                onAddContact = {
                    Toast.makeText(context, R.string.add_contact, Toast.LENGTH_SHORT).show()
                },
                onCall = placeCall,
                onBackspace = viewModel::onBackspace,
                onClear = viewModel::onClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
        }
        }
    }
}

@Composable
private fun DialerHeader(registration: RegistrationState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandLogo(registration = registration)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {  }) {
            Icon(
                Icons.Filled.Search,
                contentDescription = stringResource(R.string.search),
                tint = DialerOnSurface
            )
        }
    }
}

@Composable
private fun BrandLogo(registration: RegistrationState) {
    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BrandIndigo),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "e",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(10.dp)
                .clip(CircleShape)
                .background(DialerBackground),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(registration.statusColor())
            )
        }
    }
}

private fun RegistrationState.statusColor(): Color = when (this) {
    RegistrationState.Ok -> CallGreenLight
    RegistrationState.Progress -> Color(0xFFF5A623)
    RegistrationState.Failed -> HangupRed
    else -> DialerMuted
}

@Composable
private fun NumberDisplay(number: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = number.ifEmpty { stringResource(R.string.dialer_hint) },
            fontSize = if (number.isEmpty()) 24.sp.scaled else 44.sp.scaled,
            fontWeight = if (number.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
            color = if (number.isEmpty()) DialerMuted else DialerOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ContactSuggestionChip(suggestion: ContactSuggestion, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SuggestionTint,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Business,
                contentDescription = null,
                tint = SuggestionText,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = suggestion.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = SuggestionText
            )
            Text(
                text = "  ·  ${suggestion.company}",
                fontSize = 14.sp,
                color = SuggestionText.copy(alpha = 0.75f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallActionRow(
    showBackspace: Boolean,
    onAddContact: () -> Unit,
    onCall: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {

        Box(modifier = Modifier.size(64.dp.scaled), contentAlignment = Alignment.Center) {
            IconButton(onClick = onAddContact) {
                Icon(
                    Icons.Filled.PersonAdd,
                    contentDescription = stringResource(R.string.add_contact),
                    tint = DialerMuted
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = CallGreenVivid,
            onClick = onCall,
            modifier = Modifier
                .size(72.dp.scaled)
                .clip(CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = stringResource(R.string.call),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp.scaled)
                )
            }
        }

        Box(modifier = Modifier.size(64.dp.scaled), contentAlignment = Alignment.Center) {
            if (showBackspace) {

                Box(
                    modifier = Modifier
                        .size(56.dp.scaled)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = onBackspace,
                            onLongClick = onClear
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Backspace,
                        contentDescription = stringResource(R.string.delete),
                        tint = DialerMuted
                    )
                }
            }
        }
    }
}

private data class NavTab(
    val labelRes: Int,
    val icon: ImageVector
)

@Composable
private fun DialerBottomBar(onOpenSettings: () -> Unit) {
    val tabs = listOf(
        NavTab(R.string.nav_dial, Icons.Filled.Dialpad),
        NavTab(R.string.nav_recents, Icons.Filled.History),
        NavTab(R.string.nav_people, Icons.Filled.Group),
        NavTab(R.string.nav_voicemail, Icons.Filled.Voicemail),
        NavTab(R.string.nav_settings, Icons.Filled.Settings)
    )
    val context = LocalContext.current

    NavigationBar(containerColor = DialerSurface) {
        tabs.forEachIndexed { index, tab ->
            val isDial = index == 0
            val isSettings = index == tabs.lastIndex
            NavigationBarItem(
                selected = isDial,
                onClick = {
                    when {
                        isSettings -> onOpenSettings()
                        isDial -> Unit

                        else -> Toast.makeText(
                            context,
                            "${context.getString(tab.labelRes)} coming soon",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                icon = {
                    Icon(tab.icon, contentDescription = stringResource(tab.labelRes))
                },
                label = { Text(stringResource(tab.labelRes), fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = BrandIndigo,
                    indicatorColor = BrandIndigo,
                    unselectedIconColor = DialerMuted,
                    unselectedTextColor = DialerMuted
                )
            )
        }
    }
}
