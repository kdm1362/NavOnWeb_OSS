/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.ui

import android.content.Context
import android.view.Surface as AndroidSurface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pebble.tecomheadunit.BuildConfig
import com.pebble.tecomheadunit.R
import com.pebble.tecomheadunit.automation.AutomationTriggerMode
import com.pebble.tecomheadunit.diagnostics.DiagnosticLogSummary
import com.pebble.tecomheadunit.diagnostics.upload.DiagnosticUploadConsentGate
import com.pebble.tecomheadunit.diagnostics.upload.DiagnosticUploadStatusSnapshot
import com.pebble.tecomheadunit.diagnostics.upload.DiagnosticUploadTerminalOutcome
import com.pebble.tecomheadunit.diagnostics.upload.WorkerFailure
import com.pebble.tecomheadunit.session.SessionController
import com.pebble.tecomheadunit.session.SessionPhase
import com.pebble.tecomheadunit.session.AndroidAutoConnectionState
import com.pebble.tecomheadunit.session.AndroidAutoConnectionStatus
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val AppColors = darkColorScheme(
    primary = Color(0xFF67E8F9),
    onPrimary = Color(0xFF051014),
    secondary = Color(0xFFA7F3D0),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF121922),
    onSurface = Color(0xFFE7EEF7),
    error = Color(0xFFFF8A8A),
)

private const val MAX_DIAGNOSTIC_DESCRIPTION_CODE_POINTS = 500
private const val MAX_DIAGNOSTIC_DESCRIPTION_BYTES = 2 * 1_024
internal const val SETTINGS_CLOSE_HIGHLIGHT_INTERVAL_MILLIS = 1_000L

internal fun shouldHighlightSettingsCloseAfterOnboarding(settingsVisible: Boolean): Boolean =
    settingsVisible

@Composable
fun TecomHeadUnitApp(
    projectionControl: ProjectionControlUiState,
    diagnosticLogs: DiagnosticLogSummary,
    diagnosticLogMessage: String,
    diagnosticUploadAvailable: Boolean,
    diagnosticUploadStatus: DiagnosticUploadStatusSnapshot,
    serviceAutomation: ServiceAutomationUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestNewBrowserPairing: () -> Unit,
    onProjectionProfileSelected: (String) -> Unit,
    onWebRtcCodecSelected: (WebRtcCodecPreferenceOption) -> Unit,
    onExportDiagnosticLogs: () -> Unit,
    onUploadDiagnosticLogs: (String) -> Unit,
    onCancelPendingDiagnosticUploads: () -> Unit,
    onClearDiagnosticLogs: () -> Unit,
    onAutomationModeSelected: (AutomationTriggerMode) -> Unit,
    onSelectBluetoothDevice: () -> Unit,
    onBluetoothDeviceSelected: (String) -> Unit,
    onDismissBluetoothPicker: () -> Unit,
    onProjectionSurfaceAvailable: (AndroidSurface) -> Unit,
    onProjectionSurfaceDestroyed: (AndroidSurface) -> Unit,
    firstRunOnboardingRequired: Boolean = false,
    onFirstRunOnboardingCompleted: () -> Unit = {},
    onOpenAndroidAutoSettings: () -> Unit = {},
    premiumPurchase: PremiumPurchaseUiState = PremiumPurchaseUiState(),
    onUnlockPremium: () -> Unit = {},
    onRefreshPurchases: () -> Unit = {},
) {
    val session by SessionController.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showFirstRunOnboarding by rememberSaveable {
        mutableStateOf(firstRunOnboardingRequired)
    }
    var highlightSettingsClose by rememberSaveable { mutableStateOf(false) }
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val running = session.phase == SessionPhase.STARTING || session.phase == SessionPhase.READY
    LaunchedEffect(firstRunOnboardingRequired) {
        if (firstRunOnboardingRequired) showFirstRunOnboarding = true
    }
    MaterialTheme(colorScheme = AppColors) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                ModalDrawerSheet {
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.ui_nav_home)) },
                        selected = !showSettings,
                        onClick = {
                            highlightSettingsClose = false
                            showSettings = false
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.ui_nav_settings)) },
                        selected = showSettings,
                        onClick = {
                            coroutineScope.launch {
                                drawerState.close()
                                highlightSettingsClose = false
                                showSettings = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            },
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AppHeader(onOpenDrawer = { coroutineScope.launch { drawerState.open() } })
                        HomeScreen(
                            running = running,
                            projectionControl = projectionControl,
                            session = session,
                            onStart = onStart,
                            onStop = onStop,
                            onRequestNewBrowserPairing = onRequestNewBrowserPairing,
                            premiumPurchase = premiumPurchase,
                            onUnlockPremium = onUnlockPremium,
                        )
                    }
                    HiddenProjectionSurfaceHost(
                        width = projectionControl.activeProfile.width,
                        height = projectionControl.activeProfile.height,
                        onSurfaceAvailable = onProjectionSurfaceAvailable,
                        onSurfaceDestroyed = onProjectionSurfaceDestroyed,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }

        if (showSettings) {
            SettingsDialog(
                projectionControl = projectionControl,
                diagnosticLogs = diagnosticLogs,
                diagnosticLogMessage = diagnosticLogMessage,
                diagnosticUploadAvailable = diagnosticUploadAvailable,
                diagnosticUploadStatus = diagnosticUploadStatus,
                serviceAutomation = serviceAutomation,
                premiumPurchase = premiumPurchase,
                highlightCloseButton = highlightSettingsClose,
                onSettingsScrolled = { highlightSettingsClose = false },
                onDismiss = {
                    highlightSettingsClose = false
                    showSettings = false
                },
                onProjectionProfileSelected = onProjectionProfileSelected,
                onWebRtcCodecSelected = onWebRtcCodecSelected,
                onExportDiagnosticLogs = onExportDiagnosticLogs,
                onUploadDiagnosticLogs = onUploadDiagnosticLogs,
                onCancelPendingDiagnosticUploads = onCancelPendingDiagnosticUploads,
                onClearDiagnosticLogs = onClearDiagnosticLogs,
                onAutomationModeSelected = onAutomationModeSelected,
                onSelectBluetoothDevice = onSelectBluetoothDevice,
                onBluetoothDeviceSelected = onBluetoothDeviceSelected,
                onDismissBluetoothPicker = onDismissBluetoothPicker,
                onUnlockPremium = onUnlockPremium,
                onRefreshPurchases = onRefreshPurchases,
                onOpenFirstRunOnboarding = {
                    highlightSettingsClose = false
                    showFirstRunOnboarding = true
                },
            )
        }

        if (showFirstRunOnboarding) {
            FirstRunOnboardingDialog(
                onOpenAndroidAutoSettings = onOpenAndroidAutoSettings,
                onFinished = {
                    showFirstRunOnboarding = false
                    highlightSettingsClose = shouldHighlightSettingsCloseAfterOnboarding(
                        settingsVisible = showSettings,
                    )
                    onFirstRunOnboardingCompleted()
                },
            )
        }
    }
}

@Composable
private fun AppHeader(onOpenDrawer: () -> Unit) {
    val openDrawerDescription = stringResource(R.string.ui_nav_open_drawer)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF10161E))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier.semantics {
                contentDescription = openDrawerDescription
            },
        ) {
            Text(
                "☰",
                modifier = Modifier.clearAndSetSemantics {},
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Column(modifier = Modifier.padding(horizontal = 6.dp)) {
            Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.app_subtitle),
                color = Color(0xFFAAB7C7),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    running: Boolean,
    projectionControl: ProjectionControlUiState,
    session: com.pebble.tecomheadunit.session.SessionUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestNewBrowserPairing: () -> Unit,
    premiumPurchase: PremiumPurchaseUiState,
    onUnlockPremium: () -> Unit,
) {
    val sessionMessage = session.messageRes?.let { stringResource(it) } ?: session.message
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_home_intro),
            color = Color(0xFFAAB7C7),
            style = MaterialTheme.typography.bodyLarge,
        )
        StatusCard(phase = session.phase, message = sessionMessage)
        AndroidAutoConnectionCard(session.androidAutoConnection)

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121922)),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { if (running) onStop() else onStart() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) Color(0xFF324152) else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        stringResource(
                            if (running) R.string.ui_service_stop else R.string.ui_service_start,
                        ),
                    )
                }
                if (!premiumPurchase.entitled) {
                    OutlinedButton(
                        onClick = onUnlockPremium,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = premiumPurchase.canLaunchPurchase,
                    ) {
                        Text(stringResource(R.string.premium_unlock_features))
                    }
                }
            }
        }

        if (session.phase == SessionPhase.READY) {
            ConnectionCard(
                browserUrl = session.browserUrl.orEmpty(),
                pairingCode = session.pairingCode,
                onRequestNewBrowserPairing = onRequestNewBrowserPairing,
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    projectionControl: ProjectionControlUiState,
    diagnosticLogs: DiagnosticLogSummary,
    diagnosticLogMessage: String,
    diagnosticUploadAvailable: Boolean,
    diagnosticUploadStatus: DiagnosticUploadStatusSnapshot,
    serviceAutomation: ServiceAutomationUiState,
    premiumPurchase: PremiumPurchaseUiState,
    highlightCloseButton: Boolean,
    onSettingsScrolled: () -> Unit,
    onDismiss: () -> Unit,
    onProjectionProfileSelected: (String) -> Unit,
    onWebRtcCodecSelected: (WebRtcCodecPreferenceOption) -> Unit,
    onExportDiagnosticLogs: () -> Unit,
    onUploadDiagnosticLogs: (String) -> Unit,
    onCancelPendingDiagnosticUploads: () -> Unit,
    onClearDiagnosticLogs: () -> Unit,
    onAutomationModeSelected: (AutomationTriggerMode) -> Unit,
    onSelectBluetoothDevice: () -> Unit,
    onBluetoothDeviceSelected: (String) -> Unit,
    onDismissBluetoothPicker: () -> Unit,
    onUnlockPremium: () -> Unit,
    onRefreshPurchases: () -> Unit,
    onOpenFirstRunOnboarding: () -> Unit,
) {
    val settingsScrollState = rememberScrollState()
    var closeButtonEmphasized by remember { mutableStateOf(false) }
    LaunchedEffect(highlightCloseButton) {
        closeButtonEmphasized = highlightCloseButton
        while (highlightCloseButton) {
            delay(SETTINGS_CLOSE_HIGHLIGHT_INTERVAL_MILLIS)
            closeButtonEmphasized = !closeButtonEmphasized
        }
    }
    LaunchedEffect(highlightCloseButton, settingsScrollState) {
        if (highlightCloseButton) {
            snapshotFlow { settingsScrollState.isScrollInProgress }.first { it }
            onSettingsScrolled()
        }
    }
    val closeButtonContainerColor by animateColorAsState(
        targetValue = if (closeButtonEmphasized) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 250),
        label = "settings-close-highlight-background",
    )
    val closeButtonContentColor by animateColorAsState(
        targetValue = if (closeButtonEmphasized) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 250),
        label = "settings-close-highlight-content",
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF10161E))
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.ui_settings_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = closeButtonContainerColor,
                            contentColor = closeButtonContentColor,
                        ),
                    ) {
                        Text(stringResource(R.string.ui_close))
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(settingsScrollState)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    GettingStartedCard(onOpen = onOpenFirstRunOnboarding)
                    PremiumPurchaseCard(
                        state = premiumPurchase,
                        onUnlockPremium = onUnlockPremium,
                        onRefreshPurchases = onRefreshPurchases,
                    )
                    ServiceAutomationCard(
                        state = serviceAutomation,
                        premiumEntitled = premiumPurchase.entitled,
                        onModeSelected = onAutomationModeSelected,
                        onSelectBluetoothDevice = onSelectBluetoothDevice,
                    )
                    ProjectionSettingsCard(
                        state = projectionControl,
                        premiumPurchase = premiumPurchase,
                        onProjectionProfileSelected = onProjectionProfileSelected,
                        onUnlockPremium = onUnlockPremium,
                        onWebRtcCodecSelected = onWebRtcCodecSelected,
                    )
                    DiagnosticLogCard(
                        summary = diagnosticLogs,
                        message = diagnosticLogMessage,
                        uploadAvailable = diagnosticUploadAvailable,
                        uploadStatus = diagnosticUploadStatus,
                        onExport = onExportDiagnosticLogs,
                        onUpload = onUploadDiagnosticLogs,
                        onCancelPendingUploads = onCancelPendingDiagnosticUploads,
                        onClear = onClearDiagnosticLogs,
                    )
                    OpenSourceLicensesCard()
                }
            }
        }
    }

    if (serviceAutomation.bluetoothPickerVisible) {
        BluetoothDevicePickerDialog(
            devices = serviceAutomation.bluetoothDevices,
            onSelected = onBluetoothDeviceSelected,
            onDismiss = onDismissBluetoothPicker,
        )
    }
}

@Composable
private fun GettingStartedCard(onOpen: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A2F)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.first_run_settings_title),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.first_run_settings_description),
                color = Color(0xFFA0B6BA),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.first_run_settings_button))
            }
        }
    }
}

@Composable
private fun FirstRunOnboardingDialog(
    onOpenAndroidAutoSettings: () -> Unit,
    onFinished: () -> Unit,
) {
    val steps = listOf(
        FirstRunOnboardingStep(
            title = stringResource(R.string.first_run_welcome_title),
            body = stringResource(R.string.first_run_welcome_body),
            warning = stringResource(R.string.first_run_welcome_warning),
        ),
        FirstRunOnboardingStep(
            title = stringResource(R.string.first_run_android_auto_title),
            body = stringResource(R.string.first_run_android_auto_body),
            warning = stringResource(R.string.first_run_android_auto_warning),
        ),
        FirstRunOnboardingStep(
            title = stringResource(R.string.first_run_hotspot_title),
            body = stringResource(R.string.first_run_hotspot_body),
            warning = stringResource(R.string.first_run_hotspot_warning),
        ),
        FirstRunOnboardingStep(
            title = stringResource(R.string.first_run_service_title),
            body = stringResource(R.string.first_run_service_body),
        ),
        FirstRunOnboardingStep(
            title = stringResource(R.string.first_run_browser_title),
            body = stringResource(R.string.first_run_browser_body),
            warning = stringResource(R.string.first_run_browser_warning),
        ),
    )
    var currentPage by rememberSaveable { mutableStateOf(0) }
    val safePage = currentPage.coerceIn(steps.indices)
    val step = steps[safePage]

    Dialog(
        onDismissRequest = onFinished,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.first_run_header),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(
                                R.string.first_run_step_count,
                                safePage + 1,
                                steps.size,
                            ),
                            color = Color(0xFFAAB7C7),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = onFinished) {
                        Text(stringResource(R.string.first_run_skip))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    steps.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .background(
                                    color = if (index <= safePage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color(0xFF334155)
                                    },
                                    shape = RoundedCornerShape(99.dp),
                                ),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = step.body,
                        color = Color(0xFFD7E1EC),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (
                        firstRunOnboardingAction(safePage) ==
                        FirstRunOnboardingAction.OPEN_ANDROID_AUTO_SETTINGS
                    ) {
                        Button(
                            onClick = onOpenAndroidAutoSettings,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.first_run_open_android_auto_settings))
                        }
                    }
                    step.warning?.let { warning ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1D0B)),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.first_run_warning_label),
                                    color = Color(0xFFFBBF24),
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = warning,
                                    color = Color(0xFFF8E6BD),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { currentPage = (safePage - 1).coerceAtLeast(0) },
                        modifier = Modifier.weight(1f),
                        enabled = safePage > 0,
                    ) {
                        Text(stringResource(R.string.first_run_previous))
                    }
                    Button(
                        onClick = {
                            if (safePage == steps.lastIndex) {
                                onFinished()
                            } else {
                                currentPage = safePage + 1
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (safePage == steps.lastIndex) {
                                    R.string.first_run_finish
                                } else {
                                    R.string.first_run_next
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

private data class FirstRunOnboardingStep(
    val title: String,
    val body: String,
    val warning: String? = null,
)

internal enum class FirstRunOnboardingAction {
    OPEN_ANDROID_AUTO_SETTINGS,
}

/** Welcome is page 1, so the Android Auto server instructions are page 2 of 5. */
internal fun firstRunOnboardingAction(pageIndex: Int): FirstRunOnboardingAction? =
    if (pageIndex == FIRST_RUN_ANDROID_AUTO_PAGE_INDEX) {
        FirstRunOnboardingAction.OPEN_ANDROID_AUTO_SETTINGS
    } else {
        null
    }

private const val FIRST_RUN_ANDROID_AUTO_PAGE_INDEX = 1

@Composable
private fun ServiceAutomationCard(
    state: ServiceAutomationUiState,
    premiumEntitled: Boolean,
    onModeSelected: (AutomationTriggerMode) -> Unit,
    onSelectBluetoothDevice: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121922)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.service_automation_title), fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(R.string.service_automation_event_only),
                color = Color(0xFFAAB7C7),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!premiumEntitled) {
                Text(
                    text = stringResource(R.string.service_automation_premium_required),
                    color = Color(0xFFFFC98B),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AutomationModeRow(
                selected = state.mode == AutomationTriggerMode.NONE,
                enabled = true,
                title = stringResource(R.string.service_automation_off),
                description = stringResource(R.string.service_automation_off_detail),
                onClick = { onModeSelected(AutomationTriggerMode.NONE) },
            )
            AutomationModeRow(
                selected = state.mode == AutomationTriggerMode.BLUETOOTH,
                enabled = premiumEntitled && state.selectedBluetoothDeviceName != null,
                title = stringResource(R.string.service_automation_bluetooth),
                description = state.selectedBluetoothDeviceName?.let { name ->
                    val hint = state.selectedBluetoothAddressHint.orEmpty()
                    stringResource(R.string.service_automation_selected_device, name, hint)
                } ?: stringResource(R.string.service_automation_select_device_first),
                onClick = { onModeSelected(AutomationTriggerMode.BLUETOOTH) },
            )
            TextButton(onClick = onSelectBluetoothDevice, enabled = premiumEntitled) {
                Text(
                    if (state.selectedBluetoothDeviceName == null) {
                        stringResource(R.string.service_automation_choose_device)
                    } else {
                        stringResource(R.string.service_automation_change_device)
                    },
                )
            }
            AutomationModeRow(
                selected = state.mode == AutomationTriggerMode.HOTSPOT,
                enabled = premiumEntitled && state.hotspotSupported,
                title = stringResource(R.string.service_automation_hotspot),
                description = if (state.hotspotSupported) {
                    stringResource(R.string.service_automation_hotspot_detail)
                } else {
                    stringResource(R.string.service_automation_hotspot_unsupported)
                },
                onClick = { onModeSelected(AutomationTriggerMode.HOTSPOT) },
            )
            if (state.feedback.isNotBlank()) {
                Text(
                    text = state.feedback,
                    color = Color(0xFFFFC98B),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PremiumPurchaseCard(
    state: PremiumPurchaseUiState,
    onUnlockPremium: () -> Unit,
    onRefreshPurchases: () -> Unit,
) {
    val priceText = state.formattedPrice?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.premium_price_google_play)
    val statusText = if (state.statusMessage.isBlank()) {
        stringResource(
            if (state.entitled) R.string.premium_status_active else R.string.premium_status_free,
        )
    } else {
        state.statusMessage
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121922)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.premium_title), fontWeight = FontWeight.SemiBold)
            PurchaseDetailRow(
                label = stringResource(R.string.premium_entitlement_label),
                value = stringResource(
                    if (state.entitled) {
                        R.string.premium_entitlement_premium
                    } else {
                        R.string.premium_entitlement_free
                    },
                ),
            )
            PurchaseDetailRow(
                label = stringResource(R.string.premium_price_label),
                value = priceText,
            )
            PurchaseDetailRow(
                label = stringResource(R.string.premium_status_label),
                value = statusText,
            )
            Button(
                onClick = onUnlockPremium,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canLaunchPurchase,
            ) {
                Text(
                    stringResource(
                        when {
                            state.entitled -> R.string.premium_already_unlocked
                            state.purchaseInProgress -> R.string.premium_purchase_in_progress
                            else -> R.string.premium_unlock_features
                        },
                    ),
                )
            }
            OutlinedButton(
                onClick = onRefreshPurchases,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canRefreshPurchases,
            ) {
                Text(
                    stringResource(
                        if (state.purchaseRefreshInProgress) {
                            R.string.premium_refresh_in_progress
                        } else {
                            R.string.premium_refresh_purchases
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun PurchaseDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.34f),
            color = Color(0xFF8292A5),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.66f),
            color = Color(0xFFB9C8D8),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun AutomationModeRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else Color(0xFF8995A3),
                fontWeight = FontWeight.Medium,
            )
            Text(text = description, color = Color(0xFF8292A5), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BluetoothDevicePickerDialog(
    devices: List<BluetoothDeviceOptionUi>,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.service_automation_device_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.service_automation_no_bonded_devices))
                } else {
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelected(device.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name, fontWeight = FontWeight.Medium)
                                Text(
                                    text = device.addressHint,
                                    color = Color(0xFF8292A5),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DiagnosticLogCard(
    summary: DiagnosticLogSummary,
    message: String,
    uploadAvailable: Boolean,
    uploadStatus: DiagnosticUploadStatusSnapshot,
    onExport: () -> Unit,
    onUpload: (String) -> Unit,
    onCancelPendingUploads: () -> Unit,
    onClear: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    var confirmClear by remember { mutableStateOf(false) }
    var confirmCancelPendingUploads by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showUploadConsentDialog by remember { mutableStateOf(false) }
    var uploadDescription by remember { mutableStateOf("") }
    val currentOnUpload by rememberUpdatedState(onUpload)
    val uploadConsentGate = remember {
        DiagnosticUploadConsentGate { approvedDescription ->
            currentOnUpload(approvedDescription)
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121922)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ui_diagnostic_title), fontWeight = FontWeight.SemiBold)
            Text(
                text = if (summary.fileCount == 0) {
                    stringResource(R.string.ui_diagnostic_empty)
                } else {
                    stringResource(
                        R.string.ui_diagnostic_summary,
                        pluralStringResource(
                            R.plurals.ui_diagnostic_file_count,
                            summary.fileCount,
                            summary.fileCount,
                        ),
                        formatByteSize(summary.totalBytes, locale),
                        formatLastLogTime(summary.lastModifiedEpochMillis, locale),
                    )
                },
                color = Color(0xFFB9C8D8),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.ui_diagnostic_storage_policy),
                color = Color(0xFF8292A5),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = if (uploadStatus.pendingCount == 0) {
                    stringResource(R.string.ui_diagnostic_pending_none)
                } else {
                    pluralStringResource(
                        R.plurals.ui_diagnostic_pending_count,
                        uploadStatus.pendingCount,
                        uploadStatus.pendingCount,
                    )
                },
                color = if (uploadStatus.pendingCount == 0) {
                    Color(0xFF8292A5)
                } else {
                    Color(0xFFFFC98B)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            formatLastDiagnosticUpload(uploadStatus, locale)?.let { lastResult ->
                Text(
                    text = lastResult,
                    color = Color(0xFF8292A5),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        uploadDescription = ""
                        showUploadDialog = true
                    },
                    enabled = summary.fileCount > 0 && uploadAvailable,
                ) {
                    Text(stringResource(R.string.ui_diagnostic_upload))
                }
                TextButton(onClick = onExport, enabled = summary.fileCount > 0) {
                    Text(stringResource(R.string.ui_diagnostic_save))
                }
                TextButton(onClick = { confirmClear = true }, enabled = summary.fileCount > 0) {
                    Text(stringResource(R.string.ui_diagnostic_delete))
                }
            }
            if (uploadStatus.pendingCount > 0) {
                TextButton(onClick = { confirmCancelPendingUploads = true }) {
                    Text(stringResource(R.string.ui_diagnostic_cancel_pending))
                }
            }
            if (message.isNotBlank()) {
                Text(message, color = Color(0xFFFFC98B), style = MaterialTheme.typography.bodySmall)
            }
            if (!uploadAvailable) {
                Text(
                    stringResource(R.string.ui_diagnostic_upload_unavailable),
                    color = Color(0xFF8292A5),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (confirmCancelPendingUploads) {
        AlertDialog(
            onDismissRequest = { confirmCancelPendingUploads = false },
            title = { Text(stringResource(R.string.ui_diagnostic_cancel_pending_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.ui_diagnostic_cancel_pending_body,
                        uploadStatus.pendingCount,
                        uploadStatus.pendingCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancelPendingUploads = false
                        onCancelPendingUploads()
                    },
                ) { Text(stringResource(R.string.ui_diagnostic_cancel_pending_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelPendingUploads = false }) {
                    Text(stringResource(R.string.ui_back))
                }
            },
        )
    }

    if (showUploadDialog) {
        val codePointCount = uploadDescription.codePointCount(0, uploadDescription.length)
        val byteCount = uploadDescription.toByteArray(StandardCharsets.UTF_8).size
        val canSubmit = uploadDescription.trim().isNotEmpty() &&
            codePointCount <= MAX_DIAGNOSTIC_DESCRIPTION_CODE_POINTS &&
            byteCount <= MAX_DIAGNOSTIC_DESCRIPTION_BYTES
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text(stringResource(R.string.ui_diagnostic_description_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.ui_diagnostic_description_instructions),
                    )
                    OutlinedTextField(
                        value = uploadDescription,
                        onValueChange = { uploadDescription = boundDiagnosticDescription(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.ui_diagnostic_description_label)) },
                        minLines = 3,
                        maxLines = 6,
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.ui_diagnostic_description_counter,
                                    NumberFormat.getIntegerInstance(locale).format(codePointCount),
                                    NumberFormat.getIntegerInstance(locale)
                                        .format(MAX_DIAGNOSTIC_DESCRIPTION_CODE_POINTS),
                                ),
                            )
                        },
                    )
                    Text(
                        stringResource(R.string.ui_diagnostic_upload_rate_policy),
                        color = Color(0xFF8292A5),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        uploadConsentGate.requestConfirmation(uploadDescription)
                        showUploadDialog = false
                        showUploadConsentDialog = true
                    },
                    enabled = canSubmit,
                ) { Text(stringResource(R.string.ui_send)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        uploadConsentGate.cancel()
                        showUploadDialog = false
                    },
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showUploadConsentDialog && uploadConsentGate.hasPendingConfirmation) {
        val cancelConsent: () -> Unit = {
            uploadConsentGate.cancel()
            showUploadConsentDialog = false
            showUploadDialog = true
        }
        AlertDialog(
            onDismissRequest = cancelConsent,
            title = { Text(stringResource(R.string.ui_diagnostic_consent_title)) },
            text = { Text(stringResource(R.string.ui_diagnostic_consent_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUploadConsentDialog = false
                        uploadDescription = ""
                        uploadConsentGate.confirm()
                    },
                ) { Text(stringResource(R.string.ui_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = cancelConsent) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.ui_diagnostic_delete_title)) },
            text = { Text(stringResource(R.string.ui_diagnostic_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text(stringResource(R.string.ui_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun boundDiagnosticDescription(value: String): String {
    val normalized = value.replace("\u0000", "")
    var accepted = normalized
    while (
        accepted.codePointCount(0, accepted.length) > MAX_DIAGNOSTIC_DESCRIPTION_CODE_POINTS ||
        accepted.toByteArray(StandardCharsets.UTF_8).size > MAX_DIAGNOSTIC_DESCRIPTION_BYTES
    ) {
        val lastCodePointStart = accepted.offsetByCodePoints(accepted.length, -1)
        accepted = accepted.substring(0, lastCodePointStart)
    }
    return accepted
}

@Composable
private fun OpenSourceLicensesCard() {
    var selectedDocument by remember { mutableStateOf<LegalDocument?>(null) }
    val uriHandler = LocalUriHandler.current
    val sourceUrl = BuildConfig.SOURCE_CODE_URL.trim()
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10161E)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ui_open_source_title), fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(
                    R.string.ui_open_source_summary,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                color = Color(0xFFB9C8D8),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("OpenAuto · GPL-3.0-or-later · aa90412bf93b")
            Text("AASDK · GPL-3.0-or-later · 046b3b381595")
            Text("WebRTC Android SDK 144.7559.09 · BSD-3-Clause")
            TextButton(onClick = { selectedDocument = LegalDocument.THIRD_PARTY_NOTICES }) {
                Text(stringResource(R.string.ui_open_source_view_third_party))
            }
            TextButton(onClick = { selectedDocument = LegalDocument.GPL_V3 }) {
                Text(stringResource(R.string.ui_open_source_view_gpl))
            }
            TextButton(
                onClick = { if (sourceUrl.isNotBlank()) uriHandler.openUri(sourceUrl) },
                enabled = sourceUrl.isNotBlank(),
            ) {
                Text(
                    stringResource(
                        if (sourceUrl.isBlank()) {
                            R.string.ui_open_source_url_required
                        } else {
                            R.string.ui_open_source_open_code
                        },
                    ),
                )
            }
            Text(
                text = stringResource(R.string.ui_trademark_disclaimer),
                color = Color(0xFF8292A5),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    selectedDocument?.let { document ->
        LegalDocumentDialog(document = document, onDismiss = { selectedDocument = null })
    }
}

@Composable
private fun LegalDocumentDialog(document: LegalDocument, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val loadFailedMessage = stringResource(R.string.ui_legal_document_load_failed)
    val content = remember(context, document, loadFailedMessage) {
        runCatching {
            context.assets.open(document.assetName).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }.getOrElse { loadFailedMessage }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(12.dp),
            color = Color(0xFF0B0F14),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(document.titleResource),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SelectionContainer {
                    Text(
                        text = content,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        color = Color(0xFFCED8E3),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private enum class LegalDocument(val titleResource: Int, val assetName: String) {
    THIRD_PARTY_NOTICES(R.string.ui_legal_third_party_title, "THIRD_PARTY_NOTICES.md"),
    GPL_V3(R.string.ui_legal_gpl_title, "GPL-3.0.txt"),
}

private fun formatByteSize(bytes: Long, locale: Locale): String = when {
    bytes < 1_024L -> "${NumberFormat.getIntegerInstance(locale).format(bytes)} B"
    bytes < 1_048_576L -> "${formatDecimal(bytes / 1_024.0, locale)} KiB"
    else -> "${formatDecimal(bytes / 1_048_576.0, locale)} MiB"
}

private fun formatDecimal(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)

@Composable
private fun formatLastLogTime(epochMillis: Long?, locale: Locale): String = epochMillis?.let {
    stringResource(R.string.ui_diagnostic_last_record, formatDateTime(it, locale))
} ?: stringResource(R.string.ui_diagnostic_no_record_time)

@Composable
private fun formatLastDiagnosticUpload(
    status: DiagnosticUploadStatusSnapshot,
    locale: Locale,
): String? {
    val completedAt = status.lastCompletedAtEpochMillis ?: return null
    val timestamp = formatDateTime(completedAt, locale)
    return when (status.lastOutcome) {
        DiagnosticUploadTerminalOutcome.SUCCESS ->
            stringResource(R.string.ui_diagnostic_last_upload_success, timestamp)
        DiagnosticUploadTerminalOutcome.PERMANENT_FAILURE -> {
            val reasonResource = when (status.lastFailure) {
                WorkerFailure.INVALID_INPUT -> R.string.ui_diagnostic_failure_invalid_input
                WorkerFailure.CONFIGURATION -> R.string.ui_diagnostic_failure_configuration
                WorkerFailure.SERVER_REJECTED -> R.string.ui_diagnostic_failure_server_rejected
                WorkerFailure.EXPIRED_OR_ATTEMPTS_EXHAUSTED ->
                    R.string.ui_diagnostic_failure_retry_exhausted
                null -> R.string.ui_diagnostic_failure_unknown
            }
            stringResource(
                R.string.ui_diagnostic_last_upload_failure,
                stringResource(reasonResource),
                timestamp,
            )
        }
        null -> null
    }
}

private fun formatDateTime(epochMillis: Long, locale: Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        .format(Date(epochMillis))

@Composable
private fun ProjectionSettingsCard(
    state: ProjectionControlUiState,
    premiumPurchase: PremiumPurchaseUiState,
    onProjectionProfileSelected: (String) -> Unit,
    onUnlockPremium: () -> Unit,
    onWebRtcCodecSelected: (WebRtcCodecPreferenceOption) -> Unit,
) {
    var premiumPromptProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(premiumPurchase.entitled) {
        if (premiumPurchase.entitled) premiumPromptProfileId = null
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121922)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ui_projection_profile_title), fontWeight = FontWeight.SemiBold)
            if (state.profileOptions.isEmpty()) {
                Text(
                    text = stringResource(R.string.ui_projection_profile_loading),
                    color = Color(0xFFAAB7C7),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.profileOptions.forEach { profile ->
                    ProjectionProfileRow(
                        profile = profile,
                        onClick = {
                            if (profile.premiumLocked) {
                                premiumPromptProfileId = profile.profileId
                            } else {
                                onProjectionProfileSelected(profile.profileId)
                            }
                        },
                    )
                }
            }
            Text(
                text = state.profileStatus,
                color = if (state.profileChangeInProgress) Color(0xFFFFC98B) else Color(0xFFAAB7C7),
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.ui_webrtc_codec_title), fontWeight = FontWeight.SemiBold)
            WebRtcCodecPreferenceOption.selectableOptions.forEach { option ->
                CodecPreferenceRow(
                    option = option,
                    selected = option == state.codecPreference,
                    onClick = { onWebRtcCodecSelected(option) },
                )
            }
            Text(
                text = state.codecStatus,
                color = Color(0xFFFFC98B),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    state.profileOptions.firstOrNull {
        it.profileId == premiumPromptProfileId && it.premiumLocked
    }?.let { profile ->
        AlertDialog(
            onDismissRequest = { premiumPromptProfileId = null },
            title = { Text(stringResource(R.string.premium_resolution_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.premium_resolution_dialog_message,
                        profile.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        premiumPromptProfileId = null
                        onUnlockPremium()
                    },
                    enabled = premiumPurchase.canLaunchPurchase,
                ) {
                    Text(
                        stringResource(
                            if (premiumPurchase.busy) {
                                R.string.premium_purchase_in_progress
                            } else {
                                R.string.premium_open_purchase
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { premiumPromptProfileId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ProjectionProfileRow(
    profile: ProjectionProfileOptionUi,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = profile.enabled, onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = profile.selected,
            onClick = onClick,
            enabled = profile.enabled,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = profile.title,
                    color = if (profile.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color(0xFF8995A3)
                    },
                    fontWeight = FontWeight.Medium,
                )
                if (profile.active) {
                    Text(
                        text = stringResource(R.string.ui_active),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (profile.premiumLocked) {
                    Text(
                        text = stringResource(R.string.premium_badge),
                        color = Color(0xFFFFC98B),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = profile.detail,
                color = Color(0xFF8292A5),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CodecPreferenceRow(
    option: WebRtcCodecPreferenceOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(option.displayNameRes), fontWeight = FontWeight.Medium)
            Text(
                text = stringResource(option.descriptionRes),
                color = Color(0xFF8292A5),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HiddenProjectionSurfaceHost(
    width: Int,
    height: Int,
    onSurfaceAvailable: (AndroidSurface) -> Unit,
    onSurfaceDestroyed: (AndroidSurface) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeWidth = width.takeIf { it > 0 } ?: PROJECTION_WIDTH
    val safeHeight = height.takeIf { it > 0 } ?: PROJECTION_HEIGHT
    AndroidView(
        factory = { context ->
            ProjectionSurfaceView(context).apply {
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                updateProjectionSize(safeWidth, safeHeight)
                this.onSurfaceAvailable = onSurfaceAvailable
                this.onSurfaceDestroyed = onSurfaceDestroyed
            }
        },
        update = { view ->
            view.updateProjectionSize(safeWidth, safeHeight)
            view.onSurfaceAvailable = onSurfaceAvailable
            view.onSurfaceDestroyed = onSurfaceDestroyed
        },
        onRelease = ProjectionSurfaceView::releaseCallbacks,
        modifier = modifier.size(1.dp),
    )
}

private class ProjectionSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    var onSurfaceAvailable: (AndroidSurface) -> Unit = {}
    var onSurfaceDestroyed: (AndroidSurface) -> Unit = {}
    private var activeSurface: AndroidSurface? = null
    private var projectionWidth = PROJECTION_WIDTH
    private var projectionHeight = PROJECTION_HEIGHT

    init {
        holder.setFixedSize(projectionWidth, projectionHeight)
        holder.addCallback(this)
    }

    fun updateProjectionSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 ||
            width == projectionWidth && height == projectionHeight
        ) {
            return
        }
        projectionWidth = width
        projectionHeight = height
        holder.setFixedSize(width, height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        publishSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        publishSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        clearActiveSurface()
    }

    fun releaseCallbacks() {
        holder.removeCallback(this)
        clearActiveSurface()
        onSurfaceAvailable = {}
        onSurfaceDestroyed = {}
    }

    private fun publishSurface(surface: AndroidSurface) {
        if (!surface.isValid || activeSurface === surface) return
        clearActiveSurface()
        activeSurface = surface
        onSurfaceAvailable(surface)
    }

    private fun clearActiveSurface() {
        val surface = activeSurface ?: return
        activeSurface = null
        onSurfaceDestroyed(surface)
    }
}

@Composable
private fun StatusCard(phase: SessionPhase, message: String) {
    val accent = when (phase) {
        SessionPhase.IDLE -> Color(0xFF7D8B9B)
        SessionPhase.STARTING -> Color(0xFFFFC98B)
        SessionPhase.READY -> Color(0xFF86EFAC)
        SessionPhase.ERROR -> MaterialTheme.colorScheme.error
    }
    val phaseLabel = when (phase) {
        SessionPhase.IDLE -> stringResource(R.string.ui_session_idle)
        SessionPhase.STARTING -> stringResource(R.string.ui_session_starting)
        SessionPhase.READY -> stringResource(R.string.ui_session_ready)
        SessionPhase.ERROR -> stringResource(R.string.ui_session_attention_required)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121922)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(accent, RoundedCornerShape(99.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = phaseLabel,
                    color = Color(0xFF071015),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(message, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AndroidAutoConnectionCard(connection: AndroidAutoConnectionStatus) {
    val (accent, container, title) = when (connection.state) {
        AndroidAutoConnectionState.CONNECTED -> Triple(
            Color(0xFF86EFAC),
            Color(0xFF0B2419),
            stringResource(R.string.ui_android_auto_connected),
        )
        AndroidAutoConnectionState.RECONNECTING -> Triple(
            Color(0xFFFBBF24),
            Color(0xFF251D0B),
            stringResource(R.string.ui_android_auto_reconnecting),
        )
        AndroidAutoConnectionState.DISCONNECTED -> Triple(
            Color(0xFFFB7185),
            Color(0xFF281018),
            stringResource(R.string.ui_android_auto_disconnected),
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                color = accent,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(connection.reason.messageRes), color = Color(0xFFE7EEF7))
        }
    }
}

@Composable
private fun ConnectionCard(
    browserUrl: String,
    pairingCode: String?,
    onRequestNewBrowserPairing: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A2F)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.browser_open_title), fontWeight = FontWeight.SemiBold)
            Text(
                text = browserUrl,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            if (pairingCode != null) {
                Text(stringResource(R.string.browser_pairing_code_title))
                Text(
                    text = pairingCode,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.browser_pairing_code_hint),
                    color = Color(0xFFA0B6BA),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onRequestNewBrowserPairing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.browser_pairing_refresh_button))
                }
            } else {
                Text(
                    text = stringResource(R.string.browser_pairing_remembered_hint),
                    color = Color(0xFFA0B6BA),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onRequestNewBrowserPairing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.browser_pairing_new_button))
                }
            }
        }
    }
}

private const val PROJECTION_WIDTH = 800
private const val PROJECTION_HEIGHT = 480
