/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.eigenkodex.navonweb.ui

import android.content.Context
import android.view.Surface as AndroidSurface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eigenkodex.navonweb.BuildConfig
import com.eigenkodex.navonweb.R
import com.eigenkodex.navonweb.automation.AutomationTriggerMode
import com.eigenkodex.navonweb.browser.cloud.CloudPairingRegistrationStatus
import com.eigenkodex.navonweb.billing.ReviewPromoSubmissionResult
import com.eigenkodex.navonweb.diagnostics.DiagnosticLogSummary
import com.eigenkodex.navonweb.diagnostics.upload.DiagnosticUploadConsentGate
import com.eigenkodex.navonweb.diagnostics.upload.DiagnosticUploadStatusSnapshot
import com.eigenkodex.navonweb.diagnostics.upload.DiagnosticUploadTerminalOutcome
import com.eigenkodex.navonweb.diagnostics.upload.WorkerFailure
import com.eigenkodex.navonweb.session.SessionController
import com.eigenkodex.navonweb.session.SessionPhase
import com.eigenkodex.navonweb.session.SessionUiState
import com.eigenkodex.navonweb.session.AndroidAutoConnectionState
import com.eigenkodex.navonweb.session.AndroidAutoConnectionStatus
import com.eigenkodex.navonweb.session.BrowserDevicePermission
import com.eigenkodex.navonweb.session.PairedBrowserDevice
import com.eigenkodex.navonweb.openauto.ProjectionVideoProfile
import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val AppColors = darkColorScheme(
    primary = Color(0xFF67E8F9),
    onPrimary = Color(0xFF051014),
    secondary = Color(0xFFA7F3D0),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF121922),
    onSurface = Color(0xFFE7EEF7),
    error = Color(0xFFFF8A8A),
)

private val PremiumGold = Color(0xFFFFC83D)
private val PremiumGoldContent = Color(0xFF2A1B00)

private const val MAX_DIAGNOSTIC_DESCRIPTION_CODE_POINTS = 500
private const val MAX_DIAGNOSTIC_DESCRIPTION_BYTES = 2 * 1_024
internal const val MAX_PAIRED_BROWSER_DISPLAY_NAME_CODE_POINTS = 64
internal const val SETTINGS_CLOSE_HIGHLIGHT_INTERVAL_MILLIS = 1_000L
internal const val REVIEW_PROMO_HOLD_MILLIS = 10_000L
internal const val LOCAL_BROWSER_URL_REVEAL_HOLD_MILLIS = 3_000L
internal const val PREMIUM_UNLOCK_CELEBRATION_MILLIS = 1_800L

internal fun shouldHighlightSettingsCloseAfterOnboarding(settingsVisible: Boolean): Boolean =
    settingsVisible

internal fun shouldDispatchReviewPromoHold(releaseBeforeThreshold: Boolean?): Boolean =
    releaseBeforeThreshold == null

internal data class BrowserLinkPresentation(
    val url: String,
    val isDebugLocal: Boolean,
)

internal fun numericLocalBrowserUrlOrNull(url: String?): String? {
    val candidate = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val parsed = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!parsed.scheme.equals("http", ignoreCase = true) ||
        parsed.userInfo != null || parsed.query != null || parsed.fragment != null ||
        parsed.port !in 1..65_535 || (parsed.rawPath.orEmpty() !in setOf("", "/"))
    ) {
        return null
    }
    val octets = parsed.host?.split('.') ?: return null
    if (octets.size != 4 || octets.any { octet ->
            octet.toIntOrNull()?.let { it in 0..255 } != true
        }
    ) {
        return null
    }
    return candidate
}

internal fun shouldRevealLocalBrowserUrl(
    releaseBeforeThreshold: Boolean?,
    localBrowserUrl: String?,
): Boolean = releaseBeforeThreshold == null && numericLocalBrowserUrlOrNull(localBrowserUrl) != null

internal fun browserLinkPresentation(
    normalBrowserUrl: String,
    localBrowserUrl: String?,
    revealLocalUrl: Boolean,
): BrowserLinkPresentation {
    val safeLocalUrl = numericLocalBrowserUrlOrNull(localBrowserUrl)
    return if (revealLocalUrl && safeLocalUrl != null) {
        BrowserLinkPresentation(url = safeLocalUrl, isDebugLocal = true)
    } else {
        BrowserLinkPresentation(url = normalBrowserUrl, isDebugLocal = false)
    }
}

internal enum class PairedBrowserDisplayNameFailure {
    EMPTY,
    CONTROL_CHARACTER,
    TOO_MANY_CODE_POINTS,
}

internal data class PairedBrowserDisplayNameValidation(
    val normalizedName: String,
    val failure: PairedBrowserDisplayNameFailure? = null,
) {
    val isValid: Boolean get() = failure == null
}

internal fun validatePairedBrowserDisplayName(
    candidate: String,
): PairedBrowserDisplayNameValidation {
    if (candidate.any(Char::isISOControl)) {
        return PairedBrowserDisplayNameValidation(
            normalizedName = candidate,
            failure = PairedBrowserDisplayNameFailure.CONTROL_CHARACTER,
        )
    }
    val normalized = candidate.trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) {
        return PairedBrowserDisplayNameValidation(
            normalizedName = normalized,
            failure = PairedBrowserDisplayNameFailure.EMPTY,
        )
    }
    if (
        normalized.codePointCount(0, normalized.length) >
        MAX_PAIRED_BROWSER_DISPLAY_NAME_CODE_POINTS
    ) {
        return PairedBrowserDisplayNameValidation(
            normalizedName = normalized,
            failure = PairedBrowserDisplayNameFailure.TOO_MANY_CODE_POINTS,
        )
    }
    return PairedBrowserDisplayNameValidation(normalizedName = normalized)
}

@Composable
internal fun NavOnWebApp(
    projectionControl: ProjectionControlUiState,
    diagnosticLogs: DiagnosticLogSummary,
    diagnosticLogMessage: String,
    diagnosticUploadAvailable: Boolean,
    diagnosticUploadStatus: DiagnosticUploadStatusSnapshot,
    serviceAutomation: ServiceAutomationUiState,
    pairedBrowserDevices: List<PairedBrowserDevice> = emptyList(),
    connectedBrowserDeviceIds: Set<String> = emptySet(),
    pairedBrowserDeviceMessage: String = "",
    pairedBrowserManagementReady: Boolean = false,
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
    onClearBluetoothDeviceSelection: () -> Unit,
    onDismissBluetoothPicker: () -> Unit,
    onPairedBrowserRenamed: (String, String) -> Unit = { _, _ -> },
    onPairedBrowserReadOnlyChanged: (String, Boolean) -> Unit = { _, _ -> },
    onPreferredMainBrowserSelected: (String) -> Unit = {},
    onPairedBrowserDeleted: (String) -> Unit = {},
    onProjectionSurfaceAvailable: (AndroidSurface) -> Unit,
    onProjectionSurfaceDestroyed: (AndroidSurface) -> Unit,
    firstRunOnboardingRequired: Boolean = false,
    onFirstRunOnboardingCompleted: () -> Unit = {},
    onOpenAndroidAutoSettings: () -> Unit = {},
    onOpenBrowserUrl: (String) -> Unit = {},
    premiumPurchase: PremiumPurchaseUiState = PremiumPurchaseUiState(),
    onUnlockPremium: () -> Unit = {},
    onRefreshPurchases: () -> Unit = {},
    onDismissPremiumPurchaseConfirmation: () -> Unit = {},
    onPremiumLicenseConfirmationPresented: () -> Unit = {},
    onReviewPromoCodeSubmitted: (
        String,
        (ReviewPromoSubmissionResult) -> Unit,
    ) -> Unit = { _, callback -> callback(ReviewPromoSubmissionResult.NotConfigured) },
    onProjectionDpiChanged: (String, Int) -> Unit = { _, _ -> },
    onProjectionDpiReset: (String) -> Unit = {},
) {
    val session by SessionController.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showFirstRunOnboarding by rememberSaveable {
        mutableStateOf(firstRunOnboardingRequired)
    }
    var highlightSettingsClose by rememberSaveable { mutableStateOf(false) }
    var showReviewPromoDialog by remember { mutableStateOf(false) }
    var showPremiumLicenseConfirmedDialog by rememberSaveable { mutableStateOf(false) }
    var celebratePremiumUnlock by rememberSaveable { mutableStateOf(false) }
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val running = session.phase == SessionPhase.STARTING || session.phase == SessionPhase.READY
    LaunchedEffect(firstRunOnboardingRequired) {
        if (firstRunOnboardingRequired) showFirstRunOnboarding = true
    }
    LaunchedEffect(premiumPurchase.licenseConfirmationPending) {
        if (premiumPurchase.licenseConfirmationPending) {
            showPremiumLicenseConfirmedDialog = true
            celebratePremiumUnlock = true
            delay(PREMIUM_UNLOCK_CELEBRATION_MILLIS)
            celebratePremiumUnlock = false
            onPremiumLicenseConfirmationPresented()
        }
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
                        AppHeader(
                            onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                            onReviewPromoRequested = { showReviewPromoDialog = true },
                        )
                        HomeScreen(
                            running = running,
                            projectionControl = projectionControl,
                            session = session,
                            onStart = onStart,
                            onStop = onStop,
                            onRequestNewBrowserPairing = onRequestNewBrowserPairing,
                            onOpenBrowserUrl = onOpenBrowserUrl,
                            premiumPurchase = premiumPurchase,
                            onUnlockPremium = onUnlockPremium,
                            celebratePremiumUnlock = celebratePremiumUnlock,
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
                pairedBrowserDevices = pairedBrowserDevices,
                connectedBrowserDeviceIds = connectedBrowserDeviceIds,
                pairedBrowserDeviceMessage = pairedBrowserDeviceMessage,
                pairedBrowserManagementReady = pairedBrowserManagementReady,
                premiumPurchase = premiumPurchase,
                highlightCloseButton = highlightSettingsClose,
                onSettingsScrolled = { highlightSettingsClose = false },
                onDismiss = {
                    highlightSettingsClose = false
                    showSettings = false
                },
                onProjectionProfileSelected = onProjectionProfileSelected,
                onProjectionDpiChanged = onProjectionDpiChanged,
                onProjectionDpiReset = onProjectionDpiReset,
                onWebRtcCodecSelected = onWebRtcCodecSelected,
                onExportDiagnosticLogs = onExportDiagnosticLogs,
                onUploadDiagnosticLogs = onUploadDiagnosticLogs,
                onCancelPendingDiagnosticUploads = onCancelPendingDiagnosticUploads,
                onClearDiagnosticLogs = onClearDiagnosticLogs,
                onAutomationModeSelected = onAutomationModeSelected,
                onSelectBluetoothDevice = onSelectBluetoothDevice,
                onBluetoothDeviceSelected = onBluetoothDeviceSelected,
                onClearBluetoothDeviceSelection = onClearBluetoothDeviceSelection,
                onDismissBluetoothPicker = onDismissBluetoothPicker,
                onPairedBrowserRenamed = onPairedBrowserRenamed,
                onPairedBrowserReadOnlyChanged = onPairedBrowserReadOnlyChanged,
                onPreferredMainBrowserSelected = onPreferredMainBrowserSelected,
                onPairedBrowserDeleted = onPairedBrowserDeleted,
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
                session = session,
                onStartService = onStart,
                onRequestNewBrowserPairing = onRequestNewBrowserPairing,
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

        if (showReviewPromoDialog) {
            ReviewPromoCodeDialog(
                onSubmit = onReviewPromoCodeSubmitted,
                onDismiss = { showReviewPromoDialog = false },
            )
        }

        if (premiumPurchase.confirmationDialog != PremiumPurchaseConfirmationDialog.HIDDEN) {
            PremiumPurchaseConfirmationAlert(
                state = premiumPurchase,
                onDismiss = onDismissPremiumPurchaseConfirmation,
            )
        }

        if (showPremiumLicenseConfirmedDialog) {
            AlertDialog(
                onDismissRequest = { showPremiumLicenseConfirmedDialog = false },
                title = { Text(stringResource(R.string.billing_license_confirmed_title)) },
                text = { Text(stringResource(R.string.billing_license_confirmed_message)) },
                confirmButton = {
                    TextButton(onClick = { showPremiumLicenseConfirmedDialog = false }) {
                        Text(stringResource(R.string.ui_confirm))
                    }
                },
            )
        }
    }
}

@Composable
private fun AppHeader(
    onOpenDrawer: () -> Unit,
    onReviewPromoRequested: () -> Unit,
) {
    val openDrawerDescription = stringResource(R.string.ui_nav_open_drawer)
    val currentOnReviewPromoRequested by rememberUpdatedState(onReviewPromoRequested)
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
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            val releaseBeforeThreshold =
                                withTimeoutOrNull(REVIEW_PROMO_HOLD_MILLIS) {
                                    tryAwaitRelease()
                                }
                            if (shouldDispatchReviewPromoHold(releaseBeforeThreshold)) {
                                currentOnReviewPromoRequested()
                            }
                        },
                    )
                },
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.app_subtitle),
                color = Color(0xFFAAB7C7),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ReviewPromoCodeDialog(
    onSubmit: (String, (ReviewPromoSubmissionResult) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var submissionResult by remember { mutableStateOf<ReviewPromoSubmissionResult?>(null) }
    var verifying by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!verifying) onDismiss() },
        title = { Text(stringResource(R.string.review_promo_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        submissionResult = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.review_promo_code_label)) },
                    isError = submissionResult != null && !verifying,
                    singleLine = true,
                    enabled = !verifying,
                )
                if (verifying) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.review_promo_verifying),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    submissionResult?.let { result ->
                        Text(
                            text = stringResource(result.reviewPromoErrorString()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    verifying = true
                    submissionResult = null
                    onSubmit(code) { result ->
                        verifying = false
                        if (result is ReviewPromoSubmissionResult.Activated) {
                            code = ""
                            onDismiss()
                        } else {
                            submissionResult = result
                        }
                    }
                },
                enabled = code.isNotEmpty() && !verifying,
            ) {
                Text(stringResource(R.string.ui_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !verifying) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun ReviewPromoSubmissionResult.reviewPromoErrorString(): Int = when (this) {
    ReviewPromoSubmissionResult.InvalidCode -> R.string.review_promo_invalid_code
    ReviewPromoSubmissionResult.RateLimited -> R.string.review_promo_rate_limited
    ReviewPromoSubmissionResult.RequestExpired -> R.string.review_promo_request_expired
    ReviewPromoSubmissionResult.DeviceBindingUnavailable ->
        R.string.review_promo_binding_unavailable
    ReviewPromoSubmissionResult.NetworkUnavailable -> R.string.review_promo_network_unavailable
    ReviewPromoSubmissionResult.ServerUnavailable -> R.string.review_promo_server_unavailable
    ReviewPromoSubmissionResult.InvalidServerResponse -> R.string.review_promo_invalid_response
    ReviewPromoSubmissionResult.NotConfigured -> R.string.review_promo_not_configured
    is ReviewPromoSubmissionResult.Activated -> R.string.review_promo_status_active
}

@Composable
private fun HomeScreen(
    running: Boolean,
    projectionControl: ProjectionControlUiState,
    session: com.eigenkodex.navonweb.session.SessionUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestNewBrowserPairing: () -> Unit,
    onOpenBrowserUrl: (String) -> Unit,
    premiumPurchase: PremiumPurchaseUiState,
    onUnlockPremium: () -> Unit,
    celebratePremiumUnlock: Boolean,
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
                if (!premiumPurchase.entitled || celebratePremiumUnlock) {
                    PremiumHomePurchaseButton(
                        state = premiumPurchase,
                        celebrate = celebratePremiumUnlock,
                        onClick = onUnlockPremium,
                    )
                }
            }
        }

        if (session.phase == SessionPhase.READY) {
            ConnectionCard(
                browserUrl = session.browserUrl.orEmpty(),
                localBrowserUrl = session.localBrowserUrl,
                pairingCode = session.pairingCode,
                cloudPairingRegistrationStatus = session.cloudPairingRegistrationStatus,
                onRequestNewBrowserPairing = onRequestNewBrowserPairing,
                onOpenBrowserUrl = onOpenBrowserUrl,
            )
        }
    }
}

@Composable
private fun PremiumHomePurchaseButton(
    state: PremiumPurchaseUiState,
    celebrate: Boolean,
    onClick: () -> Unit,
) {
    val burstProgress = remember { Animatable(1f) }
    LaunchedEffect(celebrate) {
        if (celebrate) {
            burstProgress.snapTo(0f)
            burstProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 1_500,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            burstProgress.snapTo(1f)
        }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        if (state.entitled) {
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = PremiumGold,
                    disabledContentColor = PremiumGoldContent,
                ),
            ) {
                Text(stringResource(R.string.premium_already_unlocked))
            }
        } else {
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canLaunchPurchase,
            ) {
                Text(stringResource(R.string.premium_unlock_features))
            }
        }
        if (celebrate) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val progress = burstProgress.value
                val fade = (1f - progress).coerceIn(0f, 1f)
                val origin = Offset(size.width / 2f, size.height / 2f)
                repeat(28) { index ->
                    val angle = Math.toRadians(index * 137.5)
                    val distance = size.width * (0.08f + (index % 7) * 0.055f) * progress
                    val gravity = size.height * 0.34f * progress * progress
                    val center = Offset(
                        x = origin.x + cos(angle).toFloat() * distance,
                        y = origin.y + sin(angle).toFloat() * distance * 0.38f + gravity,
                    )
                    val color = when (index % 4) {
                        0 -> PremiumGold
                        1 -> Color(0xFFFF7A59)
                        2 -> Color(0xFF67E8F9)
                        else -> Color(0xFFA7F3D0)
                    }
                    drawCircle(
                        color = color,
                        radius = (2.5f + (index % 3) * 1.5f).dp.toPx(),
                        center = center,
                        alpha = fade,
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumPurchaseConfirmationAlert(
    state: PremiumPurchaseUiState,
    onDismiss: () -> Unit,
) {
    val message = stringResource(
        when (state.confirmationDialog) {
            PremiumPurchaseConfirmationDialog.VERIFYING ->
                R.string.billing_confirmation_verifying
            PremiumPurchaseConfirmationDialog.DELAYED ->
                R.string.billing_confirmation_delayed
            PremiumPurchaseConfirmationDialog.FAILED ->
                R.string.billing_confirmation_failed
            PremiumPurchaseConfirmationDialog.HIDDEN ->
                R.string.billing_confirmation_verifying
        },
    )
    AlertDialog(
        onDismissRequest = { if (state.confirmationCanBeDismissed) onDismiss() },
        title = { Text(stringResource(R.string.billing_confirmation_title)) },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (state.confirmationDialog != PremiumPurchaseConfirmationDialog.FAILED) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(message)
            }
        },
        confirmButton = {
            if (state.confirmationCanBeDismissed) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ui_close))
                }
            }
        },
    )
}

private enum class SettingsSubpage {
    ROOT,
    PAIRED_BROWSER_DEVICES,
    OPEN_SOURCE_LICENSES,
}

@Composable
private fun SettingsDialog(
    projectionControl: ProjectionControlUiState,
    diagnosticLogs: DiagnosticLogSummary,
    diagnosticLogMessage: String,
    diagnosticUploadAvailable: Boolean,
    diagnosticUploadStatus: DiagnosticUploadStatusSnapshot,
    serviceAutomation: ServiceAutomationUiState,
    pairedBrowserDevices: List<PairedBrowserDevice>,
    connectedBrowserDeviceIds: Set<String>,
    pairedBrowserDeviceMessage: String,
    pairedBrowserManagementReady: Boolean,
    premiumPurchase: PremiumPurchaseUiState,
    highlightCloseButton: Boolean,
    onSettingsScrolled: () -> Unit,
    onDismiss: () -> Unit,
    onProjectionProfileSelected: (String) -> Unit,
    onProjectionDpiChanged: (String, Int) -> Unit,
    onProjectionDpiReset: (String) -> Unit,
    onWebRtcCodecSelected: (WebRtcCodecPreferenceOption) -> Unit,
    onExportDiagnosticLogs: () -> Unit,
    onUploadDiagnosticLogs: (String) -> Unit,
    onCancelPendingDiagnosticUploads: () -> Unit,
    onClearDiagnosticLogs: () -> Unit,
    onAutomationModeSelected: (AutomationTriggerMode) -> Unit,
    onSelectBluetoothDevice: () -> Unit,
    onBluetoothDeviceSelected: (String) -> Unit,
    onClearBluetoothDeviceSelection: () -> Unit,
    onDismissBluetoothPicker: () -> Unit,
    onPairedBrowserRenamed: (String, String) -> Unit,
    onPairedBrowserReadOnlyChanged: (String, Boolean) -> Unit,
    onPreferredMainBrowserSelected: (String) -> Unit,
    onPairedBrowserDeleted: (String) -> Unit,
    onUnlockPremium: () -> Unit,
    onRefreshPurchases: () -> Unit,
    onOpenFirstRunOnboarding: () -> Unit,
) {
    val settingsScrollState = rememberScrollState()
    val pairedBrowserScrollState = rememberScrollState()
    val openSourceScrollState = rememberScrollState()
    var settingsSubpageName by rememberSaveable {
        mutableStateOf(SettingsSubpage.ROOT.name)
    }
    val settingsSubpage = runCatching {
        SettingsSubpage.valueOf(settingsSubpageName)
    }.getOrDefault(SettingsSubpage.ROOT)
    val returnToSettingsRoot = { settingsSubpageName = SettingsSubpage.ROOT.name }
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
    BackHandler(
        enabled = settingsSubpage != SettingsSubpage.ROOT,
        onBack = returnToSettingsRoot,
    )
    Dialog(
        onDismissRequest = {
            if (settingsSubpage != SettingsSubpage.ROOT) {
                returnToSettingsRoot()
            } else {
                onDismiss()
            }
        },
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
                    if (settingsSubpage != SettingsSubpage.ROOT) {
                        TextButton(onClick = returnToSettingsRoot) {
                            Text(stringResource(R.string.ui_back))
                        }
                    }
                    Text(
                        text = stringResource(
                            when (settingsSubpage) {
                                SettingsSubpage.ROOT -> R.string.ui_settings_title
                                SettingsSubpage.PAIRED_BROWSER_DEVICES ->
                                    R.string.ui_paired_browser_title
                                SettingsSubpage.OPEN_SOURCE_LICENSES ->
                                    R.string.ui_open_source_title
                            },
                        ),
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
                when (settingsSubpage) {
                    SettingsSubpage.PAIRED_BROWSER_DEVICES -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(pairedBrowserScrollState)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                    ) {
                        PairedBrowserDevicesManagementCard(
                            devices = pairedBrowserDevices,
                            connectedDeviceIds = connectedBrowserDeviceIds,
                            message = pairedBrowserDeviceMessage,
                            managementReady = pairedBrowserManagementReady,
                            premiumEntitled = premiumPurchase.entitled,
                            onRename = onPairedBrowserRenamed,
                            onReadOnlyChanged = onPairedBrowserReadOnlyChanged,
                            onPreferredMainSelected = onPreferredMainBrowserSelected,
                            onDelete = onPairedBrowserDeleted,
                        )
                    }

                    SettingsSubpage.OPEN_SOURCE_LICENSES -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(openSourceScrollState)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                    ) {
                        OpenSourceLicensesCard()
                    }

                    SettingsSubpage.ROOT -> Column(
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
                        PairedBrowserDevicesEntryCard(
                            deviceCount = pairedBrowserDevices.size,
                            connectedDeviceCount = pairedBrowserDevices.count { device ->
                                device.deviceId in connectedBrowserDeviceIds
                            },
                            managementReady = pairedBrowserManagementReady,
                            onOpen = {
                                onSettingsScrolled()
                                settingsSubpageName =
                                    SettingsSubpage.PAIRED_BROWSER_DEVICES.name
                            },
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
                            onProjectionDpiChanged = onProjectionDpiChanged,
                            onProjectionDpiReset = onProjectionDpiReset,
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
                        OpenSourceLicensesEntryCard(
                            onOpen = {
                                onSettingsScrolled()
                                settingsSubpageName = SettingsSubpage.OPEN_SOURCE_LICENSES.name
                            },
                        )
                    }
                }
            }
        }
    }

    if (serviceAutomation.bluetoothPickerVisible) {
        BluetoothDevicePickerDialog(
            devices = serviceAutomation.bluetoothDevices,
            selectedDeviceId = serviceAutomation.selectedBluetoothDeviceId,
            onSelected = onBluetoothDeviceSelected,
            onClearSelection = onClearBluetoothDeviceSelection,
            onDismiss = onDismissBluetoothPicker,
        )
    }
}

internal fun canSelectPairedBrowserAsMain(
    device: PairedBrowserDevice,
    premiumEntitled: Boolean,
    managementReady: Boolean = true,
): Boolean = managementReady &&
    premiumEntitled &&
    device.permission == BrowserDevicePermission.CONTROL

internal fun pairedBrowserPublicId(deviceId: String): String = deviceId
    .removePrefix("browser_")
    .takeLast(6)
    .uppercase(Locale.ROOT)

@Composable
private fun PairedBrowserDevicesEntryCard(
    deviceCount: Int,
    connectedDeviceCount: Int,
    managementReady: Boolean,
    onOpen: () -> Unit,
) {
    val openLabel = stringResource(R.string.ui_paired_browser_manage)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = openLabel,
                onClick = onOpen,
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121922)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_paired_browser_title),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.ui_paired_browser_overview_counts,
                        deviceCount,
                        connectedDeviceCount,
                    ),
                    color = Color(0xFFAAB7C7),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!managementReady) {
                    Text(
                        text = stringResource(R.string.ui_paired_browser_waiting_for_service),
                        color = Color(0xFFFFC98B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                text = "\u203A",
                modifier = Modifier.clearAndSetSemantics {},
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

@Composable
private fun PairedBrowserDevicesManagementCard(
    devices: List<PairedBrowserDevice>,
    connectedDeviceIds: Set<String>,
    message: String,
    managementReady: Boolean,
    premiumEntitled: Boolean,
    onRename: (String, String) -> Unit,
    onReadOnlyChanged: (String, Boolean) -> Unit,
    onPreferredMainSelected: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    var pendingDeleteDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRenameDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(devices, pendingDeleteDeviceId, managementReady) {
        if (!managementReady || devices.none { it.deviceId == pendingDeleteDeviceId }) {
            pendingDeleteDeviceId = null
        }
    }
    LaunchedEffect(devices, pendingRenameDeviceId, managementReady) {
        if (!managementReady || devices.none { it.deviceId == pendingRenameDeviceId }) {
            pendingRenameDeviceId = null
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121922)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.ui_paired_browser_title),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.ui_paired_browser_summary),
                color = Color(0xFFAAB7C7),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!managementReady) {
                Text(
                    text = stringResource(R.string.ui_paired_browser_waiting_for_service),
                    color = Color(0xFFFFC98B),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (devices.isEmpty()) {
                Text(
                    text = stringResource(R.string.ui_paired_browser_empty),
                    color = Color(0xFFAAB7C7),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider(color = Color(0xFF263341))
                    key(device.deviceId) {
                        PairedBrowserDeviceRow(
                            device = device,
                            connected = device.deviceId in connectedDeviceIds,
                            locale = locale,
                            premiumEntitled = premiumEntitled,
                            managementReady = managementReady,
                            onRename = { pendingRenameDeviceId = device.deviceId },
                            onReadOnlyChanged = { readOnly ->
                                onReadOnlyChanged(device.deviceId, readOnly)
                            },
                            onPreferredMainSelected = {
                                onPreferredMainSelected(device.deviceId)
                            },
                            onDelete = { pendingDeleteDeviceId = device.deviceId },
                        )
                    }
                }
            }
            if (!premiumEntitled && devices.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.ui_paired_browser_main_premium_hint),
                    color = Color(0xFFFFC98B),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    color = Color(0xFFFFC98B),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    val pendingDelete = devices.firstOrNull { it.deviceId == pendingDeleteDeviceId }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteDeviceId = null },
            title = { Text(stringResource(R.string.ui_paired_browser_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.ui_paired_browser_delete_body,
                        pendingDelete.displayName,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteDeviceId = null
                        onDelete(pendingDelete.deviceId)
                    },
                    enabled = managementReady,
                ) {
                    Text(stringResource(R.string.ui_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteDeviceId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val pendingRename = devices.firstOrNull { it.deviceId == pendingRenameDeviceId }
    if (pendingRename != null) {
        PairedBrowserRenameDialog(
            device = pendingRename,
            managementReady = managementReady,
            onRename = { normalizedName ->
                pendingRenameDeviceId = null
                onRename(pendingRename.deviceId, normalizedName)
            },
            onDismiss = { pendingRenameDeviceId = null },
        )
    }
}

@Composable
private fun PairedBrowserDeviceRow(
    device: PairedBrowserDevice,
    connected: Boolean,
    locale: Locale,
    premiumEntitled: Boolean,
    managementReady: Boolean,
    onRename: () -> Unit,
    onReadOnlyChanged: (Boolean) -> Unit,
    onPreferredMainSelected: () -> Unit,
    onDelete: () -> Unit,
) {
    val publicId = pairedBrowserPublicId(device.deviceId)
    val maySelectMain = canSelectPairedBrowserAsMain(
        device = device,
        premiumEntitled = premiumEntitled,
        managementReady = managementReady,
    )
    val color = when (device.colorSlot) {
        0 -> Color(0xFFFF6B6B)
        1 -> Color(0xFF35C7F0)
        else -> Color(0xFFFFC857)
    }
    val colorName = stringResource(
        when (device.colorSlot) {
            0 -> R.string.ui_paired_browser_color_red
            1 -> R.string.ui_paired_browser_color_cyan
            else -> R.string.ui_paired_browser_color_yellow
        },
    )
    val colorDescription = stringResource(
        R.string.ui_paired_browser_color,
        device.displayName,
        colorName,
    )
    val renameDescription = stringResource(
        R.string.ui_paired_browser_rename_accessibility,
        device.displayName,
        publicId,
    )
    val mainDescription = stringResource(
        R.string.ui_paired_browser_main_accessibility,
        device.displayName,
        publicId,
    )
    val readOnlyDescription = stringResource(
        R.string.ui_paired_browser_read_only_accessibility,
        device.displayName,
        publicId,
    )
    val deleteDescription = stringResource(
        R.string.ui_paired_browser_delete_accessibility,
        device.displayName,
        publicId,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, RoundedCornerShape(50))
                    .semantics { contentDescription = colorDescription },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(device.displayName, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (device.preferredMain) {
                        Text(
                            text = stringResource(R.string.ui_paired_browser_main_badge),
                            color = MaterialTheme.colorScheme.secondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (device.permission == BrowserDevicePermission.READ_ONLY) {
                        Text(
                            text = stringResource(R.string.ui_paired_browser_read_only_badge),
                            color = Color(0xFFFFC98B),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = stringResource(
                            if (connected) {
                                R.string.ui_paired_browser_connected_badge
                            } else {
                                R.string.ui_paired_browser_disconnected_badge
                            },
                        ),
                        color = if (connected) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            Color(0xFF8292A5)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.ui_paired_browser_public_id,
                        publicId,
                    ),
                    color = Color(0xFF8292A5),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.ui_paired_browser_last_seen,
                        formatDateTime(device.lastSeenAtEpochMillis, locale),
                    ),
                    color = Color(0xFF8292A5),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onRename,
                enabled = managementReady,
                modifier = Modifier.semantics {
                    contentDescription = renameDescription
                },
            ) {
                Text(stringResource(R.string.ui_paired_browser_rename))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = device.preferredMain,
                    enabled = maySelectMain,
                    role = Role.RadioButton,
                    onClick = onPreferredMainSelected,
                )
                .semantics { contentDescription = mainDescription },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = device.preferredMain,
                onClick = null,
                enabled = maySelectMain,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ui_paired_browser_main_session),
                    color = if (maySelectMain || device.preferredMain) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color(0xFF8995A3)
                    },
                )
                Text(
                    text = stringResource(R.string.ui_paired_browser_main_summary),
                    color = Color(0xFF8292A5),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = device.permission == BrowserDevicePermission.READ_ONLY,
                    enabled = managementReady,
                    role = Role.Switch,
                    onValueChange = onReadOnlyChanged,
                )
                .semantics { contentDescription = readOnlyDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_paired_browser_read_only))
                Text(
                    text = stringResource(R.string.ui_paired_browser_read_only_summary),
                    color = Color(0xFF8292A5),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = device.permission == BrowserDevicePermission.READ_ONLY,
                onCheckedChange = null,
                enabled = managementReady,
            )
        }
        TextButton(
            onClick = onDelete,
            enabled = managementReady,
            modifier = Modifier
                .align(Alignment.End)
                .semantics { contentDescription = deleteDescription },
        ) {
            Text(stringResource(R.string.ui_paired_browser_delete))
        }
    }
}

@Composable
private fun PairedBrowserRenameDialog(
    device: PairedBrowserDevice,
    managementReady: Boolean,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var displayName by rememberSaveable(device.deviceId) {
        mutableStateOf(device.displayName)
    }
    val validation = remember(displayName) {
        validatePairedBrowserDisplayName(displayName)
    }
    val originalName = remember(device.displayName) {
        validatePairedBrowserDisplayName(device.displayName).normalizedName
    }
    val codePointCount = validation.normalizedName.codePointCount(
        0,
        validation.normalizedName.length,
    )
    val canRename = managementReady &&
        validation.isValid &&
        validation.normalizedName != originalName
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submitRename = {
        if (canRename) {
            keyboardController?.hide()
            focusManager.clearFocus()
            onRename(validation.normalizedName)
        }
    }
    val validationMessage = when (validation.failure) {
        PairedBrowserDisplayNameFailure.EMPTY ->
            R.string.ui_paired_browser_name_error_required
        PairedBrowserDisplayNameFailure.CONTROL_CHARACTER ->
            R.string.ui_paired_browser_name_error_control
        PairedBrowserDisplayNameFailure.TOO_MANY_CODE_POINTS ->
            R.string.ui_paired_browser_name_error_too_long
        null -> null
    }
    val locale = LocalConfiguration.current.locales[0]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_paired_browser_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.ui_paired_browser_name_help),
                    color = Color(0xFFAAB7C7),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = managementReady,
                    singleLine = true,
                    isError = validationMessage != null,
                    label = { Text(stringResource(R.string.ui_paired_browser_name_label)) },
                    supportingText = {
                        Column {
                            if (validationMessage != null) {
                                Text(stringResource(validationMessage))
                            }
                            Text(
                                stringResource(
                                    R.string.ui_paired_browser_name_counter,
                                    NumberFormat.getIntegerInstance(locale).format(codePointCount),
                                    NumberFormat.getIntegerInstance(locale).format(
                                        MAX_PAIRED_BROWSER_DISPLAY_NAME_CODE_POINTS,
                                    ),
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitRename() }),
                )
                if (!managementReady) {
                    Text(
                        text = stringResource(R.string.ui_paired_browser_waiting_for_service),
                        color = Color(0xFFFFC98B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = submitRename,
                enabled = canRename,
            ) {
                Text(stringResource(R.string.ui_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun OpenSourceLicensesEntryCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10161E)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_open_source_title),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.ui_open_source_settings_summary),
                    color = Color(0xFFA0B6BA),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = stringResource(R.string.ui_open_source_open_details),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
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
    session: SessionUiState,
    onStartService: () -> Unit,
    onRequestNewBrowserPairing: () -> Unit,
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

                // The title, body, and optional action scroll; the safety/caution card stays
                // pinned above the navigation buttons so it is always visible at the same
                // place regardless of how long the step copy is.
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                lineBreak = LineBreak.Heading,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = step.body,
                            color = Color(0xFFD7E1EC),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                lineBreak = LineBreak.Paragraph,
                            ),
                        )
                        when (firstRunOnboardingAction(safePage)) {
                            FirstRunOnboardingAction.OPEN_ANDROID_AUTO_SETTINGS -> Button(
                                onClick = onOpenAndroidAutoSettings,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.first_run_open_android_auto_settings))
                            }

                            FirstRunOnboardingAction.START_SERVICE -> FirstRunServiceAction(
                                phase = session.phase,
                                onStartService = onStartService,
                            )

                            FirstRunOnboardingAction.SHOW_PAIRING_CODE -> FirstRunPairingCode(
                                session = session,
                                onRequestNewBrowserPairing = onRequestNewBrowserPairing,
                            )

                            null -> Unit
                        }
                    }
                    step.warning?.let { warning ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
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
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineBreak = LineBreak.Paragraph,
                                    ),
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
                                when {
                                    safePage == steps.lastIndex -> R.string.first_run_finish
                                    // The service step offers its own start button, so the
                                    // forward button says "Later" until the service is running:
                                    // moving on without starting is a deliberate choice, not the
                                    // default next step.
                                    firstRunOnboardingAction(safePage) ==
                                        FirstRunOnboardingAction.START_SERVICE &&
                                        session.phase != SessionPhase.READY ->
                                        R.string.first_run_start_service_later

                                    else -> R.string.first_run_next
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lets the reader start projection without leaving the guide. "Later" simply advances, because the
 * home screen keeps the same action; the guide must never be a required path to starting.
 */
@Composable
private fun FirstRunServiceAction(
    phase: SessionPhase,
    onStartService: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (phase) {
            SessionPhase.IDLE, SessionPhase.ERROR -> {
                Button(
                    onClick = onStartService,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.first_run_start_service))
                }
                if (phase == SessionPhase.ERROR) {
                    Text(
                        text = stringResource(R.string.first_run_service_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            SessionPhase.STARTING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.first_run_service_starting),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            SessionPhase.READY -> Text(
                text = stringResource(R.string.first_run_service_ready),
                color = Color(0xFF86EFAC),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * Shows the same live code as the home screen, so the reader can type it into the browser without
 * leaving the guide. Before the service is ready there is no code to show, so the step explains
 * that instead of rendering an empty slot.
 */
@Composable
private fun FirstRunPairingCode(
    session: SessionUiState,
    onRequestNewBrowserPairing: () -> Unit,
) {
    val browserUrl = session.browserUrl.orEmpty()
    val code = session.pairingCode
    val displayable = session.phase == SessionPhase.READY &&
        shouldDisplayPairingCode(
            browserUrl = browserUrl,
            pairingCode = code,
            cloudPairingRegistrationStatus = session.cloudPairingRegistrationStatus,
        )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF13212F)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                displayable && code != null -> {
                    Text(
                        text = stringResource(R.string.first_run_pairing_code_label),
                        color = Color(0xFF9FB3C8),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = formatPairingCodeForDisplay(code),
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(
                        onClick = onRequestNewBrowserPairing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.first_run_pairing_code_new))
                    }
                }

                // A code exists but the cloud entry point has not published it yet.
                code != null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.browser_pairing_preparing_title),
                        color = Color(0xFF9FB3C8),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Codes are single-use and issued on demand, so a running service normally has
                // none published. Offer the same issue action the home screen does.
                session.phase == SessionPhase.READY -> {
                    Text(
                        text = stringResource(R.string.first_run_pairing_code_issue_hint),
                        color = Color(0xFF9FB3C8),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onRequestNewBrowserPairing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.browser_pairing_new_button))
                    }
                }

                else -> Text(
                    text = stringResource(R.string.first_run_pairing_code_waiting),
                    color = Color(0xFF9FB3C8),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
    START_SERVICE,
    SHOW_PAIRING_CODE,
}

/** Welcome is page 1, so the Android Auto server instructions are page 2 of 5. */
internal fun firstRunOnboardingAction(pageIndex: Int): FirstRunOnboardingAction? =
    when (pageIndex) {
        FIRST_RUN_ANDROID_AUTO_PAGE_INDEX -> FirstRunOnboardingAction.OPEN_ANDROID_AUTO_SETTINGS
        FIRST_RUN_SERVICE_PAGE_INDEX -> FirstRunOnboardingAction.START_SERVICE
        FIRST_RUN_BROWSER_PAGE_INDEX -> FirstRunOnboardingAction.SHOW_PAIRING_CODE
        else -> null
    }

private const val FIRST_RUN_ANDROID_AUTO_PAGE_INDEX = 1
private const val FIRST_RUN_SERVICE_PAGE_INDEX = 3
private const val FIRST_RUN_BROWSER_PAGE_INDEX = 4

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
                enabled = premiumEntitled,
                title = stringResource(R.string.service_automation_bluetooth),
                description = state.selectedBluetoothDeviceName?.let { name ->
                    val hint = state.selectedBluetoothAddressHint.orEmpty()
                    stringResource(R.string.service_automation_selected_device, name, hint)
                } ?: stringResource(R.string.service_automation_select_device_first),
                onClick = onSelectBluetoothDevice,
            )
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
    val purchaseButtonContainerColor by animateColorAsState(
        targetValue = if (state.entitled) PremiumGold else MaterialTheme.colorScheme.primary,
        animationSpec = tween(durationMillis = 900),
        label = "premium-purchase-button-background",
    )
    val purchaseButtonContentColor by animateColorAsState(
        targetValue = if (state.entitled) {
            PremiumGoldContent
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = tween(durationMillis = 900),
        label = "premium-purchase-button-content",
    )
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = purchaseButtonContainerColor,
                    contentColor = purchaseButtonContentColor,
                    disabledContainerColor = if (state.entitled) {
                        purchaseButtonContainerColor
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    },
                    disabledContentColor = if (state.entitled) {
                        purchaseButtonContentColor
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                ),
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
    selectedDeviceId: String?,
    onSelected: (String) -> Unit,
    onClearSelection: () -> Unit,
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
                            RadioButton(
                                selected = device.id == selectedDeviceId,
                                onClick = { onSelected(device.id) },
                            )
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
        confirmButton = {
            if (selectedDeviceId != null) {
                TextButton(onClick = onClearSelection) {
                    Text(stringResource(R.string.service_automation_clear_device))
                }
            }
        },
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
    onProjectionDpiChanged: (String, Int) -> Unit,
    onProjectionDpiReset: (String) -> Unit,
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
                        onDensityDpiChanged = { densityDpi ->
                            onProjectionDpiChanged(profile.profileId, densityDpi)
                        },
                        onDensityDpiReset = { onProjectionDpiReset(profile.profileId) },
                    )
                }
            }
            Text(
                text = state.profileStatus,
                color = if (state.profileChangeInProgress) Color(0xFFFFC98B) else Color(0xFFAAB7C7),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = state.dpiStatus.ifBlank {
                    stringResource(R.string.ui_projection_dpi_help)
                },
                color = Color(0xFFAAB7C7),
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
    onDensityDpiChanged: (Int) -> Unit,
    onDensityDpiReset: () -> Unit,
) {
    var draftDensityDpi by rememberSaveable(profile.profileId) {
        mutableStateOf(profile.densityDpi.toString())
    }
    var validationError by rememberSaveable(profile.profileId) {
        mutableStateOf<ProjectionDpiInputError?>(null)
    }
    var inputWasFocused by remember(profile.profileId) { mutableStateOf(false) }
    var suppressNextBlurCommit by remember(profile.profileId) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun applyDensityDpiDraft() {
        when (val validation = validateProjectionDpiDraft(draftDensityDpi)) {
            is ProjectionDpiInputValidation.Valid -> {
                validationError = null
                draftDensityDpi = validation.densityDpi.toString()
                if (validation.densityDpi != profile.densityDpi) {
                    onDensityDpiChanged(validation.densityDpi)
                }
            }

            is ProjectionDpiInputValidation.Invalid -> validationError = validation.error
        }
    }

    LaunchedEffect(profile.densityDpi) {
        draftDensityDpi = profile.densityDpi.toString()
        validationError = null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = profile.enabled, onClick = onClick),
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draftDensityDpi,
                onValueChange = {
                    draftDensityDpi = it
                    validationError = null
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        if (inputWasFocused && !focusState.isFocused) {
                            if (suppressNextBlurCommit) {
                                suppressNextBlurCommit = false
                            } else {
                                applyDensityDpiDraft()
                            }
                        }
                        inputWasFocused = focusState.isFocused
                    },
                enabled = profile.densityControlEnabled,
                label = { Text(stringResource(R.string.ui_projection_dpi_input_label)) },
                suffix = { Text(stringResource(R.string.ui_projection_dpi_unit)) },
                isError = validationError != null,
                supportingText = validationError?.let { error ->
                    {
                        Text(
                            stringResource(
                                when (error) {
                                    ProjectionDpiInputError.REQUIRED ->
                                        R.string.ui_projection_dpi_error_required
                                    ProjectionDpiInputError.NOT_AN_INTEGER ->
                                        R.string.ui_projection_dpi_error_integer
                                    ProjectionDpiInputError.OUT_OF_RANGE ->
                                        R.string.ui_projection_dpi_error_range
                                },
                                ProjectionVideoProfile.MIN_DENSITY_DPI,
                                ProjectionVideoProfile.MAX_DENSITY_DPI,
                            ),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        applyDensityDpiDraft()
                        keyboardController?.hide()
                    },
                ),
                singleLine = true,
            )
            TextButton(
                onClick = {
                    applyDensityDpiDraft()
                    keyboardController?.hide()
                },
                enabled = profile.densityControlEnabled,
            ) {
                Text(stringResource(R.string.ui_projection_dpi_apply))
            }
        }
        if (profile.profileId == ProjectionVideoProfile.FREE_800X480.profileId) {
            Text(
                text = stringResource(
                    R.string.ui_projection_dpi_basic_applied,
                    profile.appliedLandscapeDensityDpi,
                    profile.appliedPortraitDensityDpi,
                ),
                modifier = Modifier.padding(start = 48.dp, top = 2.dp),
                color = Color(0xFF8292A5),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.ui_projection_dpi_range,
                    ProjectionVideoProfile.MIN_DENSITY_DPI,
                    ProjectionVideoProfile.MAX_DENSITY_DPI,
                    profile.recommendedDensityDpi,
                ),
                modifier = Modifier.weight(1f),
                color = Color(0xFF8292A5),
                style = MaterialTheme.typography.labelSmall,
            )
            TextButton(
                onClick = {
                    suppressNextBlurCommit = inputWasFocused
                    draftDensityDpi = profile.recommendedDensityDpi.toString()
                    validationError = null
                    focusManager.clearFocus()
                    onDensityDpiReset()
                },
                enabled = profile.densityControlEnabled &&
                    (profile.densityDpi != profile.recommendedDensityDpi ||
                        draftDensityDpi != profile.recommendedDensityDpi.toString()),
            ) {
                Text(stringResource(R.string.ui_projection_dpi_reset))
            }
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
    localBrowserUrl: String?,
    pairingCode: String?,
    cloudPairingRegistrationStatus: CloudPairingRegistrationStatus?,
    onRequestNewBrowserPairing: () -> Unit,
    onOpenBrowserUrl: (String) -> Unit,
) {
    var revealLocalUrl by rememberSaveable(localBrowserUrl) { mutableStateOf(false) }
    var browserLinkFocused by remember { mutableStateOf(false) }
    val presentation = browserLinkPresentation(
        normalBrowserUrl = browserUrl,
        localBrowserUrl = localBrowserUrl,
        revealLocalUrl = revealLocalUrl,
    )
    val openActionLabel = stringResource(R.string.browser_open_action, presentation.url)
    val linkDescription = if (presentation.isDebugLocal) {
        stringResource(R.string.browser_debug_local_link_action, presentation.url)
    } else {
        openActionLabel
    }
    val activateCurrentLink = {
        onOpenBrowserUrl(presentation.url)
        if (presentation.isDebugLocal) revealLocalUrl = false
    }
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
                text = presentation.url,
                color = if (presentation.isDebugLocal) Color(0xFFFFD54F) else MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .background(
                        color = if (browserLinkFocused) Color(0x3322D3EE) else Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(4.dp)
                    .onFocusChanged { browserLinkFocused = it.isFocused }
                    .onKeyEvent { event ->
                        val activationKey = event.key == Key.Enter ||
                            event.key == Key.NumPadEnter || event.key == Key.Spacebar
                        if (activationKey && event.type == KeyEventType.KeyUp) {
                            activateCurrentLink()
                            true
                        } else {
                            false
                        }
                    }
                    .focusable()
                    .semantics {
                        role = Role.Button
                        contentDescription = linkDescription
                        onClick(label = openActionLabel) {
                            activateCurrentLink()
                            true
                        }
                    }
                    .pointerInput(browserUrl, localBrowserUrl, revealLocalUrl) {
                        detectTapGestures(
                            onPress = {
                                val canRevealLocalUrl = !revealLocalUrl &&
                                    numericLocalBrowserUrlOrNull(localBrowserUrl) != null
                                val releaseBeforeThreshold = if (canRevealLocalUrl) {
                                    withTimeoutOrNull(LOCAL_BROWSER_URL_REVEAL_HOLD_MILLIS) {
                                        tryAwaitRelease()
                                    }
                                } else {
                                    tryAwaitRelease()
                                }
                                when {
                                    canRevealLocalUrl && shouldRevealLocalBrowserUrl(
                                        releaseBeforeThreshold = releaseBeforeThreshold,
                                        localBrowserUrl = localBrowserUrl,
                                    ) -> {
                                        revealLocalUrl = true
                                        // Consume the release that ends the hidden hold. The next
                                        // independent activation opens the yellow IP link once.
                                        tryAwaitRelease()
                                    }
                                    releaseBeforeThreshold == true -> activateCurrentLink()
                                }
                            },
                        )
                    },
            )
            Spacer(Modifier.height(4.dp))
            if (
                shouldDisplayPairingCode(
                    browserUrl = browserUrl,
                    pairingCode = pairingCode,
                    cloudPairingRegistrationStatus = cloudPairingRegistrationStatus,
                )
            ) {
                val visiblePairingCode = requireNotNull(pairingCode)
                Text(stringResource(R.string.browser_pairing_code_title))
                Text(
                    text = formatPairingCodeForDisplay(visiblePairingCode),
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
            } else if (pairingCode != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.browser_pairing_preparing_title),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(
                        when (cloudPairingRegistrationStatus) {
                            CloudPairingRegistrationStatus.RETRY ->
                                R.string.browser_pairing_retry_hint
                            CloudPairingRegistrationStatus.THROTTLED ->
                                R.string.browser_pairing_throttled_hint
                            CloudPairingRegistrationStatus.EXPIRED ->
                                R.string.browser_pairing_refreshing_hint
                            else -> R.string.browser_pairing_preparing_hint
                        },
                    ),
                    color = Color(0xFFA0B6BA),
                    style = MaterialTheme.typography.bodySmall,
                )
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

internal fun shouldDisplayPairingCode(
    browserUrl: String,
    pairingCode: String?,
    cloudPairingRegistrationStatus: CloudPairingRegistrationStatus?,
): Boolean {
    if (pairingCode == null) return false
    val cloudAddress = browserUrl.trim().startsWith("https://", ignoreCase = true)
    return !cloudAddress || cloudPairingRegistrationStatus == CloudPairingRegistrationStatus.READY
}

internal fun formatPairingCodeForDisplay(pairingCode: String): String =
    if (pairingCode.length == 6 || pairingCode.length == 8) {
        val midpoint = pairingCode.length / 2
        "${pairingCode.take(midpoint)} ${pairingCode.drop(midpoint)}"
    } else {
        pairingCode
    }

private const val PROJECTION_WIDTH = 800
private const val PROJECTION_HEIGHT = 480
