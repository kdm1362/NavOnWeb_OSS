/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.IBinder
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import com.android.billingclient.api.BillingClient
import com.pebble.tecomheadunit.automation.AutomationDispatchResult
import com.pebble.tecomheadunit.automation.AutomationServiceController
import com.pebble.tecomheadunit.automation.AutomationServiceControllerProvider
import com.pebble.tecomheadunit.automation.AutomationTriggerMode
import com.pebble.tecomheadunit.automation.HotspotAutomationMonitorService
import com.pebble.tecomheadunit.automation.ProjectionStartCancellationSignal
import com.pebble.tecomheadunit.automation.bluetooth.AndroidBondedBluetoothDeviceCatalog
import com.pebble.tecomheadunit.automation.bluetooth.BluetoothAutomationStore
import com.pebble.tecomheadunit.automation.bluetooth.BluetoothConnectPermission
import com.pebble.tecomheadunit.automation.bluetooth.BluetoothCompanionAssociationManager
import com.pebble.tecomheadunit.automation.bluetooth.BluetoothCompanionPresenceManager
import com.pebble.tecomheadunit.automation.bluetooth.BondedBluetoothDeviceCatalogResult
import com.pebble.tecomheadunit.automation.bluetooth.CompanionAssociationEvent
import com.pebble.tecomheadunit.automation.bluetooth.CompanionAssociationFailure
import com.pebble.tecomheadunit.automation.bluetooth.CompanionAssociationSubmissionResult
import com.pebble.tecomheadunit.automation.bluetooth.CompanionPresenceObservationFailure
import com.pebble.tecomheadunit.automation.bluetooth.CompanionPresenceObservationResult
import com.pebble.tecomheadunit.automation.bluetooth.SelectedBluetoothDevice
import com.pebble.tecomheadunit.billing.GooglePlayPremiumBilling
import com.pebble.tecomheadunit.billing.PremiumAccessGate
import com.pebble.tecomheadunit.billing.PremiumBillingActionResult
import com.pebble.tecomheadunit.billing.PremiumBillingConnectionState
import com.pebble.tecomheadunit.billing.PremiumBillingProvider
import com.pebble.tecomheadunit.billing.PremiumBillingResponsePolicy
import com.pebble.tecomheadunit.billing.PremiumBillingState
import com.pebble.tecomheadunit.billing.PremiumEntitlementStatus
import com.pebble.tecomheadunit.billing.PremiumEntitlementSource
import com.pebble.tecomheadunit.billing.PremiumLicensePresentationStore
import com.pebble.tecomheadunit.billing.PremiumPurchaseCheckResult
import com.pebble.tecomheadunit.diagnostics.AppDiagnostics
import com.pebble.tecomheadunit.diagnostics.DiagnosticEventCode
import com.pebble.tecomheadunit.diagnostics.DiagnosticLogSummary
import com.pebble.tecomheadunit.diagnostics.upload.DescriptionFailure
import com.pebble.tecomheadunit.diagnostics.upload.DiagnosticReportUploadManager
import com.pebble.tecomheadunit.diagnostics.upload.DiagnosticUploadApproval
import com.pebble.tecomheadunit.diagnostics.upload.DiagnosticUploadScheduler
import com.pebble.tecomheadunit.diagnostics.upload.DiagnosticUploadStatusSnapshot
import com.pebble.tecomheadunit.diagnostics.upload.DiagnosticUploadSubmissionResult
import com.pebble.tecomheadunit.openauto.AndroidAutoSettingsNavigator
import com.pebble.tecomheadunit.openauto.AndroidProjectionDpiPreferenceStore
import com.pebble.tecomheadunit.openauto.HeadUnitServerGuidanceGate
import com.pebble.tecomheadunit.openauto.ProjectionDpiSettingResult
import com.pebble.tecomheadunit.openauto.ProjectionDpiSettingsManager
import com.pebble.tecomheadunit.openauto.ProjectionDpiSettingsSnapshot
import com.pebble.tecomheadunit.openauto.ProjectionProfileRequestResult
import com.pebble.tecomheadunit.openauto.ProjectionProfileSnapshot
import com.pebble.tecomheadunit.openauto.sensor.AndroidNightModeLocationSource
import com.pebble.tecomheadunit.openauto.sensor.ProjectionNightModeLocation
import com.pebble.tecomheadunit.service.ProjectionService
import com.pebble.tecomheadunit.service.ProjectionStartupStore
import com.pebble.tecomheadunit.service.shouldRequestProjectionStartOnCreate
import com.pebble.tecomheadunit.session.SessionController
import com.pebble.tecomheadunit.session.SessionPhase
import com.pebble.tecomheadunit.ui.ProjectionControlUiState
import com.pebble.tecomheadunit.ui.AndroidProjectionControlTextResolver
import com.pebble.tecomheadunit.ui.PremiumPurchaseUiState
import com.pebble.tecomheadunit.ui.PremiumPurchaseConfirmationDialog
import com.pebble.tecomheadunit.ui.BluetoothDeviceOptionUi
import com.pebble.tecomheadunit.ui.FirstRunOnboardingStore
import com.pebble.tecomheadunit.ui.ServiceAutomationUiState
import com.pebble.tecomheadunit.ui.TecomHeadUnitApp
import com.pebble.tecomheadunit.ui.WebRtcCodecPreferenceOption
import com.pebble.tecomheadunit.ui.WebRtcCodecPreferenceStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var pendingBenchStart = false
    private var pendingStartCancellationGeneration = 0L
    private var projectionServiceBound = false
    private var projectionBinder: ProjectionService.LocalBinder? = null
    private var projectionSurfaceLease: ProjectionSurfaceLease? = null
    private var serviceUiRefreshJob: Job? = null
    private var locationTimeoutJob: Job? = null
    private var locationCancellation: CancellationSignal? = null
    private var locationRequestGeneration = 0L
    private lateinit var codecPreferenceStore: WebRtcCodecPreferenceStore
    private lateinit var projectionDpiSettingsManager: ProjectionDpiSettingsManager
    private lateinit var firstRunOnboardingStore: FirstRunOnboardingStore
    private lateinit var nightModeLocationSource: AndroidNightModeLocationSource
    private lateinit var diagnosticUploadManager: DiagnosticReportUploadManager
    private lateinit var automationController: AutomationServiceController
    private lateinit var bluetoothAutomationStore: BluetoothAutomationStore
    private lateinit var bondedBluetoothDeviceCatalog: AndroidBondedBluetoothDeviceCatalog
    private lateinit var bluetoothCompanionAssociationManager: BluetoothCompanionAssociationManager
    private lateinit var bluetoothCompanionPresenceManager: BluetoothCompanionPresenceManager
    private lateinit var premiumBilling: GooglePlayPremiumBilling
    private lateinit var premiumLicensePresentationStore: PremiumLicensePresentationStore
    private val headUnitServerGuidanceGate = HeadUnitServerGuidanceGate()
    private val projectionProfileSnapshot = MutableStateFlow<ProjectionProfileSnapshot?>(null)
    private val serviceConnected = MutableStateFlow(false)
    private val profileFeedback = MutableStateFlow("")
    private val projectionDpiSettings = MutableStateFlow(ProjectionDpiSettingsSnapshot.recommended())
    private val projectionDpiFeedback = MutableStateFlow("")
    private val codecPreference = MutableStateFlow(WebRtcCodecPreferenceOption.AUTO)
    private val codecFeedback = MutableStateFlow("")
    private val diagnosticLogSummary = MutableStateFlow(DiagnosticLogSummary.EMPTY)
    private val diagnosticLogFeedback = MutableStateFlow("")
    private val diagnosticUploadStatus = MutableStateFlow(DiagnosticUploadStatusSnapshot.EMPTY)
    private val serviceAutomation = MutableStateFlow(ServiceAutomationUiState())
    private val premiumPurchaseInProgress = MutableStateFlow(false)
    private val premiumPurchaseRefreshInProgress = MutableStateFlow(false)
    private val premiumBillingFeedback = MutableStateFlow("")
    private val premiumPurchaseConfirmationDialog = MutableStateFlow(
        PremiumPurchaseConfirmationDialog.HIDDEN,
    )
    private val premiumLicenseConfirmationPending = MutableStateFlow(false)
    private val firstRunOnboardingRequired = MutableStateFlow(false)
    private var pendingAutomationMode: AutomationTriggerMode? = null
    private var premiumBillingStarted = false
    private var premiumPurchaseFlowLaunched = false
    private var premiumPurchaseConfirmationJob: Job? = null
    private var pendingPremiumLicenseEvidenceDigest: String? = null
    private var lastObservedPremiumEntitlement: Boolean? = null
    private var reopenBluetoothPickerAfterPermission = false
    private var lastDiagnosticSummaryRefreshEpochMillis = 0L

    private val projectionServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ProjectionService.LocalBinder ?: return
            projectionBinder = binder
            serviceConnected.value = true
            refreshServiceUi(binder)
            synchronizeSavedProjectionDpi(binder)
            startServiceUiRefresh(binder)
            projectionSurfaceLease
                ?.takeIf { it.surface.isValid }
                ?.let { binder.attachProjectionSurface(it.surface, it.generation) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            projectionBinder = null
            serviceConnected.value = false
            serviceUiRefreshJob?.cancel()
            serviceUiRefreshJob = null
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingStartIsCurrent()) {
            continueBenchStartWithLocation()
        } else if (!granted) {
            SessionController.error(getString(R.string.notification_permission_start_denied))
            headUnitServerGuidanceGate.disarm()
            clearPendingStart()
        } else {
            headUnitServerGuidanceGate.disarm()
            clearPendingStart()
        }
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!pendingStartIsCurrent()) {
            headUnitServerGuidanceGate.disarm()
            clearPendingStart()
            return@registerForActivityResult
        }
        if (granted) {
            refreshLocationAndStart()
        } else {
            ProjectionNightModeLocation.clear()
            projectionBinder?.notifyNightModeEnvironmentChanged()
            finishPendingBenchStart()
        }
    }

    private val bluetoothConnectPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && reopenBluetoothPickerAfterPermission) {
            reopenBluetoothPickerAfterPermission = false
            showBluetoothDevicePicker()
        } else {
            reopenBluetoothPickerAfterPermission = false
            refreshServiceAutomationUi(feedback = getString(R.string.automation_bluetooth_permission_denied))
        }
    }

    private val automationNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requestedMode = pendingAutomationMode
        pendingAutomationMode = null
        if (requestedMode != null && PremiumAccessGate.isPremium(this)) {
            applyAutomationMode(
                mode = requestedMode,
                warning = if (granted) null else getString(R.string.automation_notification_permission_denied),
            )
        } else if (requestedMode != null) {
            refreshServiceAutomationUi(
                feedback = getString(R.string.service_automation_premium_required),
            )
        }
    }

    private val companionAssociationConfirmation = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            refreshServiceAutomationUi(feedback = getString(R.string.automation_companion_confirmation_cancelled))
            return@registerForActivityResult
        }
        // API 33+ completes through Callback.onAssociationCreated. Older releases only return
        // the confirmation result, so presence observation is started from the selected address.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            reportCompanionPresenceResult(bluetoothCompanionPresenceManager.startObserving())
        }
    }

    private val diagnosticLogExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { destination ->
        if (destination == null) {
            diagnosticLogFeedback.value = getString(R.string.diagnostic_export_cancelled)
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val content = AppDiagnostics.exportText()
                require(content.isNotBlank()) { "empty log" }
                contentResolver.openOutputStream(destination, "w")?.use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                } ?: error("output unavailable")
            }
            withContext(Dispatchers.Main) {
                diagnosticLogFeedback.value = getString(
                    if (result.isSuccess) {
                        R.string.diagnostic_export_succeeded
                    } else {
                        R.string.diagnostic_export_failed
                    },
                )
                refreshDiagnosticLogSummary(force = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDiagnostics.initialize(applicationContext)
        codecPreferenceStore = WebRtcCodecPreferenceStore(this)
        projectionDpiSettingsManager = ProjectionDpiSettingsManager(
            AndroidProjectionDpiPreferenceStore(this),
        )
        projectionDpiSettings.value = projectionDpiSettingsManager.snapshot()
        firstRunOnboardingStore = FirstRunOnboardingStore(this)
        firstRunOnboardingRequired.value = firstRunOnboardingStore.shouldShow()
        nightModeLocationSource = AndroidNightModeLocationSource(this)
        diagnosticUploadManager = DiagnosticReportUploadManager(this)
        automationController = AutomationServiceControllerProvider.get(this)
        bluetoothAutomationStore = BluetoothAutomationStore(this)
        bondedBluetoothDeviceCatalog = AndroidBondedBluetoothDeviceCatalog(this)
        bluetoothCompanionAssociationManager = BluetoothCompanionAssociationManager(this)
        bluetoothCompanionPresenceManager = BluetoothCompanionPresenceManager(this)
        premiumBilling = PremiumBillingProvider.get(this)
        premiumLicensePresentationStore = PremiumLicensePresentationStore(this)
        observePremiumBillingState()
        enforceAutomationEntitlement(PremiumAccessGate.isPremium(this))
        observeDiagnosticUploadWork()
        observeHeadUnitServerFailure()
        restorePendingDiagnosticUploads()
        codecPreference.value = codecPreferenceStore.load()
        refreshDiagnosticLogSummary(force = true)
        refreshServiceAutomationUi()
        setContent {
            val profileSnapshot by projectionProfileSnapshot.collectAsStateWithLifecycle()
            val bound by serviceConnected.collectAsStateWithLifecycle()
            val currentProfileFeedback by profileFeedback.collectAsStateWithLifecycle()
            val currentProjectionDpiSettings by projectionDpiSettings.collectAsStateWithLifecycle()
            val currentProjectionDpiFeedback by projectionDpiFeedback.collectAsStateWithLifecycle()
            val currentCodecPreference by codecPreference.collectAsStateWithLifecycle()
            val currentCodecFeedback by codecFeedback.collectAsStateWithLifecycle()
            val currentDiagnosticLogSummary by diagnosticLogSummary.collectAsStateWithLifecycle()
            val currentDiagnosticLogFeedback by diagnosticLogFeedback.collectAsStateWithLifecycle()
            val currentDiagnosticUploadStatus by diagnosticUploadStatus.collectAsStateWithLifecycle()
            val currentServiceAutomation by serviceAutomation.collectAsStateWithLifecycle()
            val currentPremiumBilling by premiumBilling.state.collectAsStateWithLifecycle()
            val purchaseInProgress by premiumPurchaseInProgress.collectAsStateWithLifecycle()
            val purchaseRefreshInProgress by
                premiumPurchaseRefreshInProgress.collectAsStateWithLifecycle()
            val currentPremiumFeedback by premiumBillingFeedback.collectAsStateWithLifecycle()
            val currentPurchaseConfirmationDialog by
                premiumPurchaseConfirmationDialog.collectAsStateWithLifecycle()
            val licenseConfirmationPending by
                premiumLicenseConfirmationPending.collectAsStateWithLifecycle()
            val showFirstRunOnboarding by
                firstRunOnboardingRequired.collectAsStateWithLifecycle()
            val premiumEntitled = effectivePremiumEntitlement(currentPremiumBilling)
            val premiumPurchaseUi = PremiumPurchaseUiState(
                entitled = premiumEntitled,
                productAvailable = currentPremiumBilling.formattedPrice?.isNotBlank() == true,
                formattedPrice = currentPremiumBilling.formattedPrice,
                purchaseInProgress = purchaseInProgress,
                purchaseRefreshInProgress = purchaseRefreshInProgress,
                statusMessage = currentPremiumFeedback.ifBlank {
                    premiumBillingStatusMessage(currentPremiumBilling)
                },
                confirmationDialog = currentPurchaseConfirmationDialog,
                licenseConfirmationPending = licenseConfirmationPending,
            )
            TecomHeadUnitApp(
                projectionControl = ProjectionControlUiState.from(
                    snapshot = profileSnapshot,
                    serviceBound = bound,
                    profileFeedback = currentProfileFeedback,
                    dpiSettings = currentProjectionDpiSettings,
                    dpiFeedback = currentProjectionDpiFeedback,
                    codecPreference = currentCodecPreference,
                    codecFeedback = currentCodecFeedback,
                    textResolver = AndroidProjectionControlTextResolver(this@MainActivity),
                    premiumEntitled = premiumEntitled,
                ),
                diagnosticLogs = currentDiagnosticLogSummary,
                diagnosticLogMessage = currentDiagnosticLogFeedback,
                diagnosticUploadAvailable = BuildConfig.SUPABASE_URL.isNotBlank() &&
                    BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_"),
                diagnosticUploadStatus = currentDiagnosticUploadStatus,
                serviceAutomation = currentServiceAutomation,
                premiumPurchase = premiumPurchaseUi,
                firstRunOnboardingRequired = showFirstRunOnboarding,
                onFirstRunOnboardingCompleted = ::completeFirstRunOnboarding,
                onOpenAndroidAutoSettings = ::showHeadUnitServerGuidance,
                onStart = ::requestProjectionStart,
                onStop = ::cancelPendingStartAndStop,
                onRequestNewBrowserPairing = ::requestNewBrowserPairing,
                onProjectionProfileSelected = ::requestProjectionProfile,
                onProjectionDpiChanged = ::saveProjectionDpi,
                onProjectionDpiReset = ::resetProjectionDpi,
                onWebRtcCodecSelected = ::saveWebRtcCodecPreference,
                onExportDiagnosticLogs = ::requestDiagnosticLogExport,
                onUploadDiagnosticLogs = ::submitApprovedDiagnosticLogUpload,
                onCancelPendingDiagnosticUploads = ::cancelAllPendingDiagnosticUploads,
                onClearDiagnosticLogs = ::clearDiagnosticLogs,
                onAutomationModeSelected = ::requestAutomationMode,
                onSelectBluetoothDevice = ::requestBluetoothDeviceSelection,
                onBluetoothDeviceSelected = ::selectBluetoothDevice,
                onClearBluetoothDeviceSelection = ::clearBluetoothDeviceSelection,
                onDismissBluetoothPicker = ::dismissBluetoothDevicePicker,
                onUnlockPremium = ::requestPremiumPurchase,
                onRefreshPurchases = ::requestPremiumPurchaseRefresh,
                onDismissPremiumPurchaseConfirmation =
                    ::dismissPremiumPurchaseConfirmation,
                onPremiumLicenseConfirmationPresented =
                    ::markPremiumLicenseConfirmationPresented,
                onProjectionSurfaceAvailable = ::onProjectionSurfaceAvailable,
                onProjectionSurfaceDestroyed = ::onProjectionSurfaceDestroyed,
            )
        }
        val automationRetry =
            intent?.action == com.pebble.tecomheadunit.automation.AndroidAutomationStartFallback.ACTION_RETRY_AUTOMATION
        if (
            shouldRequestProjectionStartOnCreate(
                isAutomationRetry = automationRetry,
                hasSuccessfulProjection = ProjectionStartupStore(this).shouldAutoStart(),
                isProjectionSessionActive = SessionController.state.value.phase.let { phase ->
                    phase == SessionPhase.STARTING || phase == SessionPhase.READY
                },
                hasPremiumEntitlement = PremiumAccessGate.isPremium(this),
            )
        ) {
            requestProjectionStart()
        }
    }

    private fun completeFirstRunOnboarding() {
        firstRunOnboardingStore.markComplete()
        firstRunOnboardingRequired.value = false
    }

    override fun onStart() {
        super.onStart()
        refreshServiceAutomationUi()
        HotspotAutomationMonitorService.refresh(this)
        refreshDiagnosticUploadStatus()
        projectionServiceBound = bindService(
            Intent(this, ProjectionService::class.java),
            projectionServiceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onResume() {
        super.onResume()
        if (premiumBillingStarted) {
            if (premiumPurchaseFlowLaunched || premiumPurchaseInProgress.value) {
                premiumPurchaseFlowLaunched = false
                beginPremiumPurchaseConfirmation(manualRefresh = false)
            } else {
                // Keep the offline entitlement cache authoritative only until Play is reachable.
                premiumBilling.refreshPurchases()
            }
        } else {
            premiumBillingStarted = true
            premiumBilling.start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == com.pebble.tecomheadunit.automation.AndroidAutomationStartFallback.ACTION_RETRY_AUTOMATION) {
            if (PremiumAccessGate.isPremium(this)) {
                requestProjectionStart()
            } else {
                refreshServiceAutomationUi(
                    feedback = getString(R.string.service_automation_premium_required),
                )
            }
        }
    }

    override fun onStop() {
        serviceUiRefreshJob?.cancel()
        serviceUiRefreshJob = null
        projectionSurfaceLease?.let { lease ->
            projectionBinder?.detachProjectionSurface(lease.generation)
        }
        projectionBinder = null
        serviceConnected.value = false
        if (projectionServiceBound) {
            unbindService(projectionServiceConnection)
            projectionServiceBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        locationRequestGeneration += 1L
        locationTimeoutJob?.cancel()
        locationTimeoutJob = null
        locationCancellation?.cancel()
        locationCancellation = null
        super.onDestroy()
    }

    private fun onProjectionSurfaceAvailable(surface: Surface) {
        if (!surface.isValid) return
        val current = projectionSurfaceLease
        if (current?.surface === surface) {
            projectionBinder?.attachProjectionSurface(surface, current.generation)
            return
        }

        current?.let { projectionBinder?.detachProjectionSurface(it.generation) }
        val lease = ProjectionSurfaceLease(
            surface = surface,
            generation = SURFACE_GENERATIONS.incrementAndGet(),
        )
        projectionSurfaceLease = lease
        projectionBinder?.attachProjectionSurface(surface, lease.generation)
    }

    private fun onProjectionSurfaceDestroyed(surface: Surface) {
        val current = projectionSurfaceLease ?: return
        if (current.surface !== surface) return
        projectionBinder?.detachProjectionSurface(current.generation)
        projectionSurfaceLease = null
    }

    private fun startServiceUiRefresh(binder: ProjectionService.LocalBinder) {
        serviceUiRefreshJob?.cancel()
        serviceUiRefreshJob = lifecycleScope.launch {
            while (isActive && projectionBinder === binder) {
                refreshServiceUi(binder)
                delay(SERVICE_UI_REFRESH_MILLIS)
            }
        }
    }

    private fun refreshServiceUi(binder: ProjectionService.LocalBinder) {
        runCatching { binder.projectionProfileSnapshot() }
            .onSuccess { snapshot ->
                val previous = projectionProfileSnapshot.value
                projectionProfileSnapshot.value = snapshot
                if (previous?.isAutomaticReconnectPending == true &&
                    !snapshot.isAutomaticReconnectPending
                ) {
                    profileFeedback.value = if (previous.activeProfile != snapshot.activeProfile) {
                        getString(
                            R.string.projection_profile_applied_immediately,
                            snapshot.activeProfile.width,
                            snapshot.activeProfile.height,
                        )
                    } else {
                        getString(R.string.projection_profile_auto_apply_failed)
                    }
                }
            }
        refreshDiagnosticLogSummary()
    }

    /** Applies a setting saved while the Activity was unbound to an already-running session. */
    private fun synchronizeSavedProjectionDpi(binder: ProjectionService.LocalBinder) {
        val activeProfile = runCatching { binder.projectionProfileSnapshot().activeProfile }
            .getOrNull()
            ?: return
        binder.notifyProjectionDpiPreferenceChanged(
            activeProfile.profileId,
            projectionDpiSettingsManager.densityDpi(activeProfile),
        )
    }

    private fun requestProjectionProfile(profileId: String) {
        val binder = projectionBinder
        if (binder == null) {
            profileFeedback.value = getString(R.string.projection_service_connect_first)
            return
        }
        val result = runCatching { binder.requestProjectionProfile(profileId) }
            .getOrElse {
                profileFeedback.value = getString(R.string.projection_profile_request_failed)
                return
            }
        when (result) {
            is ProjectionProfileRequestResult.Accepted -> {
                projectionProfileSnapshot.value = result.snapshot
                profileFeedback.value = if (result.snapshot.activeProfile == result.snapshot.requestedProfile) {
                    getString(
                        R.string.projection_profile_applied,
                        result.snapshot.activeProfile.width,
                        result.snapshot.activeProfile.height,
                    )
                } else {
                    getString(
                        R.string.projection_profile_reconnecting,
                        result.snapshot.requestedProfile.width,
                        result.snapshot.requestedProfile.height,
                    )
                }
                AppDiagnostics.record(
                    DiagnosticEventCode.PROJECTION_PROFILE_CHANGED,
                    fields = mapOf(
                        "result" to "accepted",
                        "profile" to result.snapshot.requestedProfile.name,
                    ),
                )
            }
            is ProjectionProfileRequestResult.NotEntitled -> {
                projectionProfileSnapshot.value = result.snapshot
                profileFeedback.value = getString(R.string.projection_profile_not_entitled)
                AppDiagnostics.record(
                    DiagnosticEventCode.PROJECTION_PROFILE_CHANGED,
                    fields = mapOf("result" to "not_entitled"),
                )
            }
            is ProjectionProfileRequestResult.PersistenceFailed -> {
                projectionProfileSnapshot.value = result.snapshot
                profileFeedback.value = getString(R.string.projection_profile_persistence_failed)
                AppDiagnostics.record(
                    DiagnosticEventCode.PROJECTION_PROFILE_CHANGED,
                    fields = mapOf("result" to "persistence_failed"),
                )
            }
            ProjectionProfileRequestResult.UnknownProfile -> {
                profileFeedback.value = getString(R.string.projection_profile_unknown)
            }
        }
    }

    private fun saveProjectionDpi(profileId: String, densityDpi: Int) {
        handleProjectionDpiResult(
            profileId = profileId,
            result = projectionDpiSettingsManager.setDensityDpi(profileId, densityDpi),
            reset = false,
        )
    }

    private fun resetProjectionDpi(profileId: String) {
        handleProjectionDpiResult(
            profileId = profileId,
            result = projectionDpiSettingsManager.resetToRecommended(profileId),
            reset = true,
        )
    }

    private fun handleProjectionDpiResult(
        profileId: String,
        result: ProjectionDpiSettingResult,
        reset: Boolean,
    ) {
        when (result) {
            is ProjectionDpiSettingResult.Accepted -> {
                projectionDpiSettings.value = result.snapshot
                val profile = com.pebble.tecomheadunit.openauto.ProjectionVideoProfile
                    .fromProfileId(profileId)
                    ?: return
                val densityDpi = result.snapshot.densityDpi(profile)
                val reconnecting = result.changed &&
                    projectionBinder?.notifyProjectionDpiPreferenceChanged(
                        profileId,
                        densityDpi,
                    ) == true
                projectionDpiFeedback.value = getString(
                    when {
                        reconnecting -> R.string.projection_dpi_saved_reconnecting
                        reset -> R.string.projection_dpi_reset_saved
                        else -> R.string.projection_dpi_saved
                    },
                    densityDpi,
                )
            }

            is ProjectionDpiSettingResult.PersistenceFailed -> {
                projectionDpiSettings.value = result.snapshot
                projectionDpiFeedback.value = getString(R.string.projection_dpi_save_failed)
            }

            ProjectionDpiSettingResult.Invalid,
            ProjectionDpiSettingResult.UnknownProfile,
            -> projectionDpiFeedback.value = getString(R.string.projection_dpi_invalid)
        }
    }

    private fun saveWebRtcCodecPreference(preference: WebRtcCodecPreferenceOption) {
        val normalized = preference.takeIf { it.isSelectable } ?: WebRtcCodecPreferenceOption.AUTO
        if (codecPreferenceStore.save(normalized)) {
            codecPreference.value = normalized
            projectionBinder?.notifyWebRtcCodecPreferenceChanged()
            codecFeedback.value = getString(
                R.string.codec_saved,
                getString(normalized.displayNameRes),
            )
            AppDiagnostics.record(
                DiagnosticEventCode.WEBRTC_CODEC_CHANGED,
                fields = mapOf("codec" to normalized.name, "result" to "saved"),
            )
        } else {
            codecFeedback.value = getString(R.string.codec_save_failed)
            AppDiagnostics.record(
                DiagnosticEventCode.WEBRTC_CODEC_CHANGED,
                fields = mapOf("codec" to normalized.name, "result" to "persistence_failed"),
            )
        }
        refreshDiagnosticLogSummary(force = true)
    }

    private fun requestDiagnosticLogExport() {
        if (AppDiagnostics.summary().fileCount == 0) {
            diagnosticLogFeedback.value = getString(R.string.diagnostic_no_logs_to_save)
            return
        }
        diagnosticLogExport.launch("tecom-diagnostics-${System.currentTimeMillis()}.jsonl")
    }

    private fun submitApprovedDiagnosticLogUpload(userDescription: String) {
        val approval = DiagnosticUploadApproval.confirmedByUser(userDescription)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { diagnosticUploadManager.submitApproved(approval) }
                .getOrElse { DiagnosticUploadSubmissionResult.PersistenceFailed }
            val uploadStatus = diagnosticUploadManager.status()
            withContext(Dispatchers.Main) {
                diagnosticUploadStatus.value = uploadStatus
                diagnosticLogFeedback.value = when (result) {
                    is DiagnosticUploadSubmissionResult.Queued ->
                        getString(R.string.diagnostic_upload_queued)
                    is DiagnosticUploadSubmissionResult.InvalidDescription -> when (result.reason) {
                        DescriptionFailure.EMPTY ->
                            getString(R.string.diagnostic_description_required)
                        DescriptionFailure.TOO_MANY_CODE_POINTS,
                        DescriptionFailure.TOO_MANY_UTF8_BYTES ->
                            getString(R.string.diagnostic_description_too_long)
                        DescriptionFailure.DISALLOWED_CONTROL_CHARACTER ->
                            getString(R.string.diagnostic_description_invalid_character)
                    }
                    DiagnosticUploadSubmissionResult.NoDiagnosticLogs ->
                        getString(R.string.diagnostic_no_logs_to_upload)
                    DiagnosticUploadSubmissionResult.OutboxFull ->
                        getString(R.string.diagnostic_outbox_full)
                    is DiagnosticUploadSubmissionResult.RateLimited -> {
                        val remainingMinutes = maxOf(
                            1L,
                            (result.retryAfter.toMillis() + 59_999L) / 60_000L,
                        )
                        getString(R.string.diagnostic_rate_limited, remainingMinutes)
                    }
                    DiagnosticUploadSubmissionResult.PersistenceFailed ->
                        getString(R.string.diagnostic_persistence_failed)
                    DiagnosticUploadSubmissionResult.SchedulingFailed ->
                        getString(R.string.diagnostic_scheduling_failed)
                }
                refreshDiagnosticLogSummary(force = true)
            }
        }
    }

    private fun restorePendingDiagnosticUploads() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recovery = diagnosticUploadManager.restorePendingUploads()
            val uploadStatus = diagnosticUploadManager.status()
            withContext(Dispatchers.Main) {
                diagnosticUploadStatus.value = uploadStatus
                if (recovery.failedCount > 0) {
                    diagnosticLogFeedback.value = getString(
                        R.string.diagnostic_recovery_failed,
                        recovery.failedCount,
                    )
                }
            }
        }
    }

    private fun cancelAllPendingDiagnosticUploads() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cancellation = diagnosticUploadManager.cancelAllPending()
            val uploadStatus = diagnosticUploadManager.status()
            withContext(Dispatchers.Main) {
                diagnosticUploadStatus.value = uploadStatus
                diagnosticLogFeedback.value = when {
                    cancellation.requestedCount == 0 ->
                        getString(R.string.diagnostic_cancel_none)
                    cancellation.failedCount == 0 ->
                        getString(
                            R.string.diagnostic_cancel_succeeded,
                            cancellation.removedCount,
                        )
                    else ->
                        getString(
                            R.string.diagnostic_cancel_partial,
                            cancellation.removedCount,
                            cancellation.failedCount,
                        )
                }
            }
        }
    }

    private fun observeDiagnosticUploadWork() {
        val workManager = WorkManager.getInstance(applicationContext)
        val refreshObserver: (List<androidx.work.WorkInfo>) -> Unit = {
            refreshDiagnosticUploadStatus()
        }
        workManager.getWorkInfosByTagLiveData(DiagnosticUploadScheduler.WORK_TAG)
            .observe(this, refreshObserver)
        workManager.getWorkInfosByTagLiveData(DiagnosticUploadScheduler.EXPIRY_WORK_TAG)
            .observe(this, refreshObserver)
    }

    private fun refreshDiagnosticUploadStatus() {
        if (!::diagnosticUploadManager.isInitialized) return
        lifecycleScope.launch(Dispatchers.IO) {
            val uploadStatus = diagnosticUploadManager.status()
            withContext(Dispatchers.Main) {
                diagnosticUploadStatus.value = uploadStatus
            }
        }
    }

    private fun clearDiagnosticLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { AppDiagnostics.clear() }
            withContext(Dispatchers.Main) {
                diagnosticLogFeedback.value = getString(
                    if (result.isSuccess) {
                        R.string.diagnostic_clear_succeeded
                    } else {
                        R.string.diagnostic_clear_failed
                    },
                )
                refreshDiagnosticLogSummary(force = true)
            }
        }
    }

    private fun refreshDiagnosticLogSummary(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDiagnosticSummaryRefreshEpochMillis < LOG_SUMMARY_REFRESH_MILLIS) return
        lastDiagnosticSummaryRefreshEpochMillis = now
        diagnosticLogSummary.value = AppDiagnostics.summary()
    }

    private fun requestAutomationMode(mode: AutomationTriggerMode) {
        if (mode != AutomationTriggerMode.NONE && !PremiumAccessGate.isPremium(this)) {
            pendingAutomationMode = null
            refreshServiceAutomationUi(
                feedback = getString(R.string.service_automation_premium_required),
            )
            return
        }
        if (mode == AutomationTriggerMode.BLUETOOTH && bluetoothAutomationStore.load().selectedDevice == null) {
            requestBluetoothDeviceSelection()
            return
        }
        if (mode == AutomationTriggerMode.HOTSPOT && Build.VERSION.SDK_INT < 36) {
            refreshServiceAutomationUi(feedback = getString(R.string.automation_hotspot_requires_android_16))
            return
        }
        if (
            mode != AutomationTriggerMode.NONE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingAutomationMode = mode
            automationNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        applyAutomationMode(mode)
    }

    private fun applyAutomationMode(mode: AutomationTriggerMode, warning: String? = null) {
        if (mode != AutomationTriggerMode.NONE && !PremiumAccessGate.isPremium(this)) {
            refreshServiceAutomationUi(
                feedback = getString(R.string.service_automation_premium_required),
            )
            return
        }
        val previousMode = automationController.snapshot().mode
        val result = automationController.selectMode(mode)
        if (result is AutomationDispatchResult.Failed) {
            refreshServiceAutomationUi(feedback = getString(R.string.automation_save_failed))
            return
        }
        if (previousMode == AutomationTriggerMode.BLUETOOTH && mode != AutomationTriggerMode.BLUETOOTH) {
            bluetoothCompanionPresenceManager.stopObserving()
        }
        val monitorApplied = HotspotAutomationMonitorService.refresh(this)
        val feedback = warning ?: when {
            mode == AutomationTriggerMode.HOTSPOT && !monitorApplied ->
                getString(R.string.automation_hotspot_monitor_failed)
            mode == AutomationTriggerMode.NONE -> getString(R.string.automation_disabled)
            mode == AutomationTriggerMode.BLUETOOTH -> ensureBluetoothPresenceObservation()
            else -> getString(R.string.automation_hotspot_enabled)
        }
        refreshServiceAutomationUi(feedback = feedback, pickerVisible = false)
    }

    private fun requestBluetoothDeviceSelection() {
        if (!PremiumAccessGate.isPremium(this)) {
            refreshServiceAutomationUi(
                feedback = getString(R.string.service_automation_premium_required),
                pickerVisible = false,
            )
            return
        }
        val permission = BluetoothConnectPermission.runtimePermission()
        if (permission != null && !BluetoothConnectPermission.isGranted(this)) {
            reopenBluetoothPickerAfterPermission = true
            bluetoothConnectPermission.launch(permission)
            return
        }
        showBluetoothDevicePicker()
    }

    private fun showBluetoothDevicePicker() {
        when (val result = bondedBluetoothDeviceCatalog.load()) {
            is BondedBluetoothDeviceCatalogResult.Available -> {
                val options = result.devices.map { device ->
                    BluetoothDeviceOptionUi(
                        id = device.address,
                        name = device.displayName,
                        addressHint = device.addressHint,
                    )
                }
                val feedback = if (options.isEmpty()) {
                    getString(R.string.automation_no_bonded_devices)
                } else {
                    ""
                }
                refreshServiceAutomationUi(
                    feedback = feedback,
                    pickerVisible = true,
                    devices = options,
                )
            }
            BondedBluetoothDeviceCatalogResult.PermissionRequired -> {
                reopenBluetoothPickerAfterPermission = true
                BluetoothConnectPermission.runtimePermission()?.let(bluetoothConnectPermission::launch)
            }
            BondedBluetoothDeviceCatalogResult.BluetoothUnavailable ->
                refreshServiceAutomationUi(feedback = getString(R.string.automation_bluetooth_unavailable))
            BondedBluetoothDeviceCatalogResult.BluetoothDisabled ->
                refreshServiceAutomationUi(feedback = getString(R.string.automation_bluetooth_disabled))
            BondedBluetoothDeviceCatalogResult.PlatformFailure ->
                refreshServiceAutomationUi(feedback = getString(R.string.automation_bluetooth_catalog_failed))
        }
    }

    private fun selectBluetoothDevice(deviceId: String) {
        val selected = (bondedBluetoothDeviceCatalog.load() as? BondedBluetoothDeviceCatalogResult.Available)
            ?.devices
            ?.firstOrNull { it.address == deviceId }
        if (selected == null) {
            refreshServiceAutomationUi(
                feedback = getString(R.string.automation_bluetooth_device_missing),
                pickerVisible = false,
            )
            return
        }
        val previousSelection = bluetoothAutomationStore.load().selectedDevice
        val resetBluetoothGeneration =
            automationController.snapshot().mode == AutomationTriggerMode.BLUETOOTH
        val stored = bluetoothAutomationStore.selectDevice(
            SelectedBluetoothDevice(
                address = selected.address,
                displayName = selected.displayName,
            ),
        )
        if (!stored) {
            refreshServiceAutomationUi(
                feedback = getString(R.string.automation_save_failed),
                pickerVisible = false,
            )
            return
        }
        if (resetBluetoothGeneration) {
            val reset = automationController.resetSelectedModeSignal(AutomationTriggerMode.BLUETOOTH)
            if (reset is AutomationDispatchResult.Failed) {
                if (previousSelection == null) {
                    bluetoothAutomationStore.clearSelection()
                } else {
                    bluetoothAutomationStore.selectDevice(previousSelection)
                }
                refreshServiceAutomationUi(
                    feedback = getString(R.string.automation_save_failed),
                    pickerVisible = false,
                )
                return
            }
        }
        previousSelection?.let(bluetoothCompanionPresenceManager::stopObserving)
        requestAutomationMode(AutomationTriggerMode.BLUETOOTH)
    }

    private fun ensureBluetoothPresenceObservation(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return getString(R.string.automation_bluetooth_compatibility_enabled)
        }
        return when (val result = bluetoothCompanionPresenceManager.startObserving()) {
            CompanionPresenceObservationResult.Started ->
                getString(R.string.automation_bluetooth_companion_enabled)
            CompanionPresenceObservationResult.Stopped ->
                getString(R.string.automation_bluetooth_enabled)
            is CompanionPresenceObservationResult.Failed -> when (result.reason) {
                CompanionPresenceObservationFailure.ASSOCIATION_REQUIRED -> {
                    requestBluetoothCompanionAssociation()
                    getString(R.string.automation_companion_confirmation_required)
                }
                CompanionPresenceObservationFailure.NO_SELECTED_DEVICE ->
                    getString(R.string.automation_bluetooth_device_missing)
                else -> getString(R.string.automation_bluetooth_compatibility_enabled)
            }
        }
    }

    private fun clearBluetoothDeviceSelection() {
        if (!PremiumAccessGate.isPremium(this)) {
            refreshServiceAutomationUi(
                feedback = getString(R.string.service_automation_premium_required),
                pickerVisible = false,
            )
            return
        }
        val selected = bluetoothAutomationStore.load().selectedDevice
        if (selected == null) {
            refreshServiceAutomationUi(pickerVisible = false)
            return
        }
        val previousMode = automationController.snapshot().mode
        if (previousMode == AutomationTriggerMode.BLUETOOTH) {
            val disabled = automationController.selectMode(AutomationTriggerMode.NONE)
            if (disabled is AutomationDispatchResult.Failed) {
                refreshServiceAutomationUi(
                    feedback = getString(R.string.automation_save_failed),
                    pickerVisible = false,
                )
                return
            }
        }
        if (!bluetoothAutomationStore.clearSelection()) {
            if (previousMode == AutomationTriggerMode.BLUETOOTH) {
                automationController.selectMode(AutomationTriggerMode.BLUETOOTH)
            }
            refreshServiceAutomationUi(
                feedback = getString(R.string.automation_save_failed),
                pickerVisible = false,
            )
            return
        }
        bluetoothCompanionPresenceManager.stopObserving(selected)
        HotspotAutomationMonitorService.refresh(this)
        refreshServiceAutomationUi(
            feedback = getString(
                if (previousMode == AutomationTriggerMode.BLUETOOTH) {
                    R.string.service_automation_device_cleared_and_disabled
                } else {
                    R.string.service_automation_device_cleared
                },
            ),
            pickerVisible = false,
            devices = emptyList(),
        )
    }

    private fun requestBluetoothCompanionAssociation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        when (
            val submission = bluetoothCompanionAssociationManager.associateSelectedDevice(
                callbackExecutor = mainExecutor,
            ) { event ->
                when (event) {
                    is CompanionAssociationEvent.ConfirmationRequired -> {
                        companionAssociationConfirmation.launch(
                            IntentSenderRequest.Builder(event.intentSender).build(),
                        )
                    }
                    is CompanionAssociationEvent.Created -> {
                        reportCompanionPresenceResult(
                            bluetoothCompanionPresenceManager.startObserving(),
                        )
                    }
                    is CompanionAssociationEvent.Failed -> {
                        refreshServiceAutomationUi(
                            feedback = getString(R.string.automation_companion_failed_fallback),
                        )
                    }
                }
            }
        ) {
            CompanionAssociationSubmissionResult.Submitted -> Unit
            is CompanionAssociationSubmissionResult.Rejected -> {
                val feedback = when (submission.reason) {
                    CompanionAssociationFailure.NO_SELECTED_DEVICE ->
                        getString(R.string.automation_bluetooth_device_missing)
                    else -> getString(R.string.automation_companion_failed_fallback)
                }
                refreshServiceAutomationUi(feedback = feedback)
            }
        }
    }

    private fun reportCompanionPresenceResult(result: CompanionPresenceObservationResult) {
        val feedback = when (result) {
            CompanionPresenceObservationResult.Started ->
                getString(R.string.automation_bluetooth_companion_enabled)
            CompanionPresenceObservationResult.Stopped ->
                getString(R.string.automation_bluetooth_compatibility_enabled)
            is CompanionPresenceObservationResult.Failed ->
                getString(R.string.automation_companion_failed_fallback)
        }
        refreshServiceAutomationUi(feedback = feedback)
    }

    private fun dismissBluetoothDevicePicker() {
        serviceAutomation.value = serviceAutomation.value.copy(bluetoothPickerVisible = false)
    }

    private fun refreshServiceAutomationUi(
        feedback: String? = null,
        pickerVisible: Boolean? = null,
        devices: List<BluetoothDeviceOptionUi>? = null,
    ) {
        if (!::automationController.isInitialized || !::bluetoothAutomationStore.isInitialized) return
        val selected = bluetoothAutomationStore.load().selectedDevice
        val current = serviceAutomation.value
        serviceAutomation.value = current.copy(
            mode = automationController.snapshot().mode,
            selectedBluetoothDeviceId = selected?.address,
            selectedBluetoothDeviceName = selected?.displayName,
            selectedBluetoothAddressHint = selected?.address
                ?.takeLast(8)
                ?.padStart(17, '•'),
            bluetoothDevices = devices ?: current.bluetoothDevices,
            bluetoothPickerVisible = pickerVisible ?: current.bluetoothPickerVisible,
            hotspotSupported = Build.VERSION.SDK_INT >= 36,
            feedback = feedback ?: current.feedback,
        )
    }

    private fun observePremiumBillingState() {
        lifecycleScope.launch {
            premiumBilling.state.collect { state ->
                val entitled = effectivePremiumEntitlement(billingState = state)

                when (state.entitlement) {
                    PremiumEntitlementStatus.PREMIUM -> {
                        finishPremiumPurchaseConfirmation()
                        maybeSchedulePremiumLicenseConfirmation(state)
                    }
                    PremiumEntitlementStatus.PENDING -> {
                        if (!premiumPurchaseConfirmationActive()) {
                            premiumBillingFeedback.value =
                                getString(R.string.billing_purchase_pending)
                        }
                    }
                    PremiumEntitlementStatus.FREE -> {
                        if (premiumPurchaseConfirmationActive()) {
                            when (state.lastResponseCode) {
                                BillingClient.BillingResponseCode.USER_CANCELED -> {
                                    clearPremiumPurchaseConfirmation()
                                    premiumBillingFeedback.value =
                                        getString(R.string.billing_purchase_cancelled)
                                }
                                null,
                                BillingClient.BillingResponseCode.OK,
                                -> Unit
                                else -> {
                                    if (
                                        state.lastPurchaseFailureResponseCode?.let(
                                            PremiumBillingResponsePolicy::isConfirmedPurchaseFailure,
                                        ) == true
                                    ) {
                                        showPremiumPurchaseFailure()
                                    }
                                }
                            }
                        }
                    }
                }

                applyEffectivePremiumEntitlement(entitled)
            }
        }
    }

    private fun requestPremiumPurchase() {
        if (
            debugPremiumBenchEnabled() ||
            premiumBilling.state.value.isPremium
        ) {
            clearPremiumPurchaseConfirmation()
            premiumBillingFeedback.value = if (debugPremiumBenchEnabled()) {
                getString(R.string.billing_debug_bench_active)
            } else {
                getString(R.string.billing_purchase_active)
            }
            return
        }
        if (premiumPurchaseInProgress.value || premiumPurchaseRefreshInProgress.value) return
        premiumPurchaseInProgress.value = true
        premiumPurchaseFlowLaunched = false
        premiumPurchaseConfirmationDialog.value = PremiumPurchaseConfirmationDialog.HIDDEN
        premiumBillingFeedback.value = ""
        premiumBilling.launchPurchase(this) { result ->
            when (result) {
                PremiumBillingActionResult.PurchaseFlowStarted -> {
                    premiumPurchaseFlowLaunched = true
                    premiumBillingFeedback.value = getString(R.string.billing_purchase_opened)
                }
                PremiumBillingActionResult.PremiumOwned -> {
                    finishPremiumPurchaseConfirmation()
                    maybeSchedulePremiumLicenseConfirmation(premiumBilling.state.value)
                }
                PremiumBillingActionResult.PurchasePending -> {
                    beginPremiumPurchaseConfirmation(manualRefresh = false)
                }
                PremiumBillingActionResult.UserCancelled -> {
                    clearPremiumPurchaseConfirmation()
                    premiumBillingFeedback.value = getString(R.string.billing_purchase_cancelled)
                }
                PremiumBillingActionResult.ProductUnavailable -> {
                    clearPremiumPurchaseConfirmation()
                    premiumBillingFeedback.value = getString(R.string.billing_product_unavailable)
                }
                is PremiumBillingActionResult.Failed -> {
                    if (
                        PremiumBillingResponsePolicy.isConfirmedPurchaseFailure(
                            result.responseCode,
                        )
                    ) {
                        showPremiumPurchaseFailure()
                    } else {
                        beginPremiumPurchaseConfirmation(manualRefresh = false)
                    }
                }
            }
        }
    }

    private fun requestPremiumPurchaseRefresh() {
        if (premiumPurchaseInProgress.value || premiumPurchaseRefreshInProgress.value) return
        beginPremiumPurchaseConfirmation(manualRefresh = true)
    }

    private fun beginPremiumPurchaseConfirmation(manualRefresh: Boolean) {
        if (premiumBilling.state.value.isPremium) {
            finishPremiumPurchaseConfirmation()
            maybeSchedulePremiumLicenseConfirmation(premiumBilling.state.value)
            return
        }
        premiumPurchaseConfirmationJob?.cancel()
        if (manualRefresh) {
            premiumPurchaseRefreshInProgress.value = true
        } else {
            premiumPurchaseInProgress.value = true
        }
        premiumPurchaseConfirmationDialog.value =
            PremiumPurchaseConfirmationDialog.VERIFYING
        premiumBillingFeedback.value = getString(R.string.billing_confirmation_verifying)
        premiumPurchaseConfirmationJob = lifecycleScope.launch {
            val delayedStateJob = launch {
                delay(PREMIUM_PURCHASE_INITIAL_CHECK_MILLIS)
                if (
                    premiumPurchaseConfirmationActive() &&
                    premiumPurchaseConfirmationDialog.value ==
                    PremiumPurchaseConfirmationDialog.VERIFYING
                ) {
                    premiumPurchaseConfirmationDialog.value =
                        PremiumPurchaseConfirmationDialog.DELAYED
                    premiumBillingFeedback.value =
                        getString(R.string.billing_confirmation_delayed)
                }
            }
            try {
                while (isActive && premiumPurchaseConfirmationActive()) {
                    val checkResult =
                        withTimeoutOrNull(PREMIUM_PURCHASE_QUERY_TIMEOUT_MILLIS) {
                            awaitPremiumPurchaseCheck()
                        }
                    when (checkResult) {
                        PremiumPurchaseCheckResult.PremiumOwned -> {
                            finishPremiumPurchaseConfirmation()
                            maybeSchedulePremiumLicenseConfirmation(premiumBilling.state.value)
                            return@launch
                        }
                        is PremiumPurchaseCheckResult.Failed -> {
                            if (
                                PremiumBillingResponsePolicy.isConfirmedPurchaseFailure(
                                    checkResult.responseCode,
                                )
                            ) {
                                showPremiumPurchaseFailure()
                                return@launch
                            }
                        }
                        PremiumPurchaseCheckResult.Free,
                        PremiumPurchaseCheckResult.PurchasePending,
                        null,
                        -> Unit
                    }
                    delay(PREMIUM_PURCHASE_POLL_MILLIS)
                }
            } finally {
                delayedStateJob.cancel()
            }
        }
    }

    private suspend fun awaitPremiumPurchaseCheck(): PremiumPurchaseCheckResult =
        suspendCancellableCoroutine { continuation ->
            premiumBilling.refreshPurchases { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    private fun premiumPurchaseConfirmationActive(): Boolean =
        premiumPurchaseInProgress.value || premiumPurchaseRefreshInProgress.value

    private fun finishPremiumPurchaseConfirmation() {
        premiumPurchaseConfirmationJob?.cancel()
        premiumPurchaseConfirmationJob = null
        premiumPurchaseFlowLaunched = false
        premiumPurchaseInProgress.value = false
        premiumPurchaseRefreshInProgress.value = false
        premiumPurchaseConfirmationDialog.value = PremiumPurchaseConfirmationDialog.HIDDEN
        premiumBillingFeedback.value = getString(R.string.billing_purchase_active)
    }

    private fun clearPremiumPurchaseConfirmation() {
        premiumPurchaseConfirmationJob?.cancel()
        premiumPurchaseConfirmationJob = null
        premiumPurchaseFlowLaunched = false
        premiumPurchaseInProgress.value = false
        premiumPurchaseRefreshInProgress.value = false
        premiumPurchaseConfirmationDialog.value = PremiumPurchaseConfirmationDialog.HIDDEN
    }

    private fun showPremiumPurchaseFailure() {
        premiumPurchaseConfirmationJob?.cancel()
        premiumPurchaseConfirmationJob = null
        premiumPurchaseFlowLaunched = false
        premiumPurchaseInProgress.value = false
        premiumPurchaseRefreshInProgress.value = false
        premiumPurchaseConfirmationDialog.value = PremiumPurchaseConfirmationDialog.FAILED
        premiumBillingFeedback.value = getString(R.string.billing_confirmation_failed)
    }

    private fun dismissPremiumPurchaseConfirmation() {
        val canDismiss = when (premiumPurchaseConfirmationDialog.value) {
            PremiumPurchaseConfirmationDialog.DELAYED,
            PremiumPurchaseConfirmationDialog.FAILED,
            -> true
            PremiumPurchaseConfirmationDialog.HIDDEN,
            PremiumPurchaseConfirmationDialog.VERIFYING,
            -> false
        }
        if (!canDismiss) return
        clearPremiumPurchaseConfirmation()
    }

    private fun maybeSchedulePremiumLicenseConfirmation(state: PremiumBillingState) {
        if (state.entitlementSource != PremiumEntitlementSource.LIVE_PLAY_QUERY) return
        val evidenceDigest = PremiumBillingProvider.currentPurchaseEvidenceDigest() ?: return
        if (pendingPremiumLicenseEvidenceDigest == evidenceDigest) return
        if (!premiumLicensePresentationStore.shouldPresent(evidenceDigest)) return
        pendingPremiumLicenseEvidenceDigest = evidenceDigest
        premiumLicenseConfirmationPending.value = true
    }

    private fun markPremiumLicenseConfirmationPresented() {
        pendingPremiumLicenseEvidenceDigest?.let { evidenceDigest ->
            if (premiumLicensePresentationStore.recordPresented(evidenceDigest)) {
                pendingPremiumLicenseEvidenceDigest = null
            }
        }
        premiumLicenseConfirmationPending.value = false
    }

    private fun premiumBillingStatusMessage(state: PremiumBillingState): String = when {
        debugPremiumBenchEnabled() -> getString(R.string.billing_debug_bench_active)
        state.connection == PremiumBillingConnectionState.NOT_STARTED ||
            state.connection == PremiumBillingConnectionState.CONNECTING ->
            getString(R.string.billing_connecting)
        state.connection == PremiumBillingConnectionState.UNAVAILABLE ||
            state.connection == PremiumBillingConnectionState.CLOSED ->
            getString(R.string.billing_unavailable)
        state.entitlement == PremiumEntitlementStatus.PREMIUM ->
            getString(R.string.billing_purchase_active)
        state.entitlement == PremiumEntitlementStatus.PENDING ->
            getString(R.string.billing_purchase_pending)
        else -> getString(R.string.premium_status_free)
    }

    private fun enforceAutomationEntitlement(entitled: Boolean) {
        if (entitled || !::automationController.isInitialized) return
        val automationWasConfigured =
            automationController.snapshot().mode != AutomationTriggerMode.NONE ||
                pendingAutomationMode != null ||
                reopenBluetoothPickerAfterPermission ||
                serviceAutomation.value.bluetoothPickerVisible
        pendingAutomationMode = null
        reopenBluetoothPickerAfterPermission = false
        if (automationController.snapshot().mode != AutomationTriggerMode.NONE) {
            automationController.selectMode(AutomationTriggerMode.NONE)
        }
        if (::bluetoothCompanionPresenceManager.isInitialized) {
            bluetoothCompanionPresenceManager.stopObserving()
        }
        HotspotAutomationMonitorService.refresh(this)
        refreshServiceAutomationUi(
            feedback = if (automationWasConfigured) {
                getString(R.string.service_automation_premium_required)
            } else {
                serviceAutomation.value.feedback
            },
            pickerVisible = false,
        )
    }

    private fun handlePremiumEntitlementTransition(entitled: Boolean) {
        val phase = SessionController.state.value.phase
        val sessionActive = phase == SessionPhase.STARTING || phase == SessionPhase.READY
        if (!entitled) {
            if (sessionActive) {
                clearPendingStart()
                ProjectionService.stop(this)
            }
            return
        }
        if (!sessionActive) return

        // Projection/audio tier is resolved when the service session starts. Rebuild that
        // transport once after purchase so high-resolution profiles and stereo become available.
        clearPendingStart()
        ProjectionService.stop(this)
        lifecycleScope.launch {
            repeat(ENTITLEMENT_RESTART_ATTEMPTS) {
                delay(ENTITLEMENT_RESTART_RETRY_MILLIS)
                val currentPhase = SessionController.state.value.phase
                if (currentPhase != SessionPhase.STARTING && currentPhase != SessionPhase.READY) {
                    if (PremiumAccessGate.isPremium(this@MainActivity)) requestProjectionStart()
                    return@launch
                }
            }
            premiumBillingFeedback.value = getString(R.string.billing_purchase_active)
        }
    }

    private fun effectivePremiumEntitlement(
        billingState: PremiumBillingState = premiumBilling.state.value,
    ): Boolean = billingState.isPremium || debugPremiumBenchEnabled()

    private fun applyEffectivePremiumEntitlement(entitled: Boolean) {
        val previous = lastObservedPremiumEntitlement
        lastObservedPremiumEntitlement = entitled
        enforceAutomationEntitlement(entitled)
        if (previous != null && previous != entitled) {
            handlePremiumEntitlementTransition(entitled)
        }
    }

    private fun debugPremiumBenchEnabled(): Boolean =
        BuildConfig.DEBUG && BuildConfig.ENABLE_PREMIUM_PROJECTION_BENCH

    private fun requestProjectionStart() {
        headUnitServerGuidanceGate.arm()
        pendingStartCancellationGeneration = ProjectionStartCancellationSignal.snapshot()
        pendingBenchStart = true

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        continueBenchStartWithLocation()
    }

    private fun cancelPendingStartAndStop() {
        headUnitServerGuidanceGate.disarm()
        clearPendingStart()
        ProjectionService.stop(this)
    }

    private fun observeHeadUnitServerFailure() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionController.state.collect { state ->
                    if (headUnitServerGuidanceGate.consume(state.androidAutoConnection)) {
                        showHeadUnitServerGuidance()
                    }
                }
            }
        }
    }

    private fun showHeadUnitServerGuidance() {
        val settingsOpened = AndroidAutoSettingsNavigator.open(this)
        val message = if (settingsOpened) {
            R.string.android_auto_head_unit_server_start_guidance
        } else {
            R.string.android_auto_settings_open_failed
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun requestNewBrowserPairing() {
        projectionBinder?.requestNewBrowserPairing()
    }

    private fun clearPendingStart() {
        pendingBenchStart = false
        locationRequestGeneration += 1L
        locationTimeoutJob?.cancel()
        locationTimeoutJob = null
        locationCancellation?.cancel()
        locationCancellation = null
    }

    private fun pendingStartIsCurrent(): Boolean = pendingBenchStart &&
        ProjectionStartCancellationSignal.isCurrent(pendingStartCancellationGeneration)

    private fun continueBenchStartWithLocation() {
        if (!pendingStartIsCurrent()) {
            headUnitServerGuidanceGate.disarm()
            clearPendingStart()
            return
        }
        if (!nightModeLocationSource.hasPermission()) {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return
        }
        refreshLocationAndStart()
    }

    private fun refreshLocationAndStart() {
        if (!pendingStartIsCurrent()) {
            headUnitServerGuidanceGate.disarm()
            clearPendingStart()
            return
        }
        nightModeLocationSource.bestLastKnownLocation()?.let { location ->
            ProjectionNightModeLocation.update(location)
        }
        projectionBinder?.notifyNightModeEnvironmentChanged()

        locationRequestGeneration += 1L
        val generation = locationRequestGeneration
        locationCancellation?.cancel()
        locationCancellation = nightModeLocationSource.requestCurrentLocation { location ->
            if (location != null) {
                ProjectionNightModeLocation.update(location)
                projectionBinder?.notifyNightModeEnvironmentChanged()
            }
            if (generation == locationRequestGeneration && pendingStartIsCurrent()) {
                locationTimeoutJob?.cancel()
                locationTimeoutJob = null
                locationCancellation = null
                finishPendingBenchStart()
            }
        }
        if (locationCancellation == null) {
            finishPendingBenchStart()
            return
        }
        locationTimeoutJob?.cancel()
        locationTimeoutJob = lifecycleScope.launch {
            delay(LOCATION_FIX_WAIT_MILLIS)
            if (generation == locationRequestGeneration && pendingStartIsCurrent()) {
                locationCancellation?.cancel()
                locationCancellation = null
                finishPendingBenchStart()
            }
        }
    }

    private fun finishPendingBenchStart() {
        if (!pendingStartIsCurrent()) {
            headUnitServerGuidanceGate.disarm()
            clearPendingStart()
            return
        }
        val startCancellationGeneration = pendingStartCancellationGeneration
        clearPendingStart()
        ProjectionService.start(
            context = this,
            benchConfirmed = true,
            startCancellationGeneration = startCancellationGeneration,
        )
    }

    private data class ProjectionSurfaceLease(
        val surface: Surface,
        val generation: Long,
    )

    private companion object {
        val SURFACE_GENERATIONS = AtomicLong(0L)
        const val SERVICE_UI_REFRESH_MILLIS = 750L
        const val LOG_SUMMARY_REFRESH_MILLIS = 5_000L
        const val LOCATION_FIX_WAIT_MILLIS = 2_500L
        const val PREMIUM_PURCHASE_INITIAL_CHECK_MILLIS = 5_000L
        const val PREMIUM_PURCHASE_QUERY_TIMEOUT_MILLIS = 4_000L
        const val PREMIUM_PURCHASE_POLL_MILLIS = 1_500L
        const val ENTITLEMENT_RESTART_ATTEMPTS = 16
        const val ENTITLEMENT_RESTART_RETRY_MILLIS = 250L
    }
}
