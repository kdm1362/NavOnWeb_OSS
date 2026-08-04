/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.pebble.tecomheadunit.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.pebble.tecomheadunit.BuildConfig
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-process Google Play Billing owner for NavOnWeb's permanent Premium one-time product.
 *
 * ProductDetails is queried immediately before each purchase flow and is never cached. Ownership is
 * refreshed after the initial Play connection, after purchase callbacks, and on explicit user
 * request. All public state omits purchase tokens, signed payloads and Play debug strings.
 */
class GooglePlayPremiumBilling internal constructor(
    context: Context,
    val productId: String,
    private val entitlementStore: PremiumEntitlementStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : PurchasesUpdatedListener {
    init {
        require(isValidProductId(productId)) { "invalid Google Play product ID" }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build(),
        )
        .enableAutoServiceReconnection()
        .build()
    private val pendingReadyActions = ArrayDeque<ReadyAction>()
    private var connectionStarted = false
    private var initialSetupCompleted = false
    @Volatile
    private var closed = false
    private val cachedEntitlement = entitlementStore.load(productId)
    @Volatile
    private var livePurchaseEvidenceDigest: String? = cachedEntitlement?.purchaseTokenSha256
    internal val isClosed: Boolean
        get() = closed
    internal val currentPurchaseEvidenceDigest: String?
        get() = livePurchaseEvidenceDigest

    private val mutableState = MutableStateFlow(
        PremiumBillingState(
            productId = productId,
            connection = PremiumBillingConnectionState.NOT_STARTED,
            entitlement = if (cachedEntitlement != null) {
                PremiumEntitlementStatus.PREMIUM
            } else {
                PremiumEntitlementStatus.FREE
            },
            entitlementSource = if (cachedEntitlement != null) {
                PremiumEntitlementSource.CACHED_PLAY_PURCHASE
            } else {
                PremiumEntitlementSource.NONE
            },
        ),
    )
    val state: StateFlow<PremiumBillingState> = mutableState.asStateFlow()

    /** Idempotently connects and performs the launch/resume ownership check. */
    fun start() {
        withReady(
            onFailure = { responseCode -> markUnavailable(responseCode) },
            action = {
                refreshPurchasesInternal()
                queryProductPresentation()
            },
        )
    }

    /** "Purchase check" / restore action. A successful empty query revokes the local cache. */
    fun refreshPurchases(
        callback: (PremiumPurchaseCheckResult) -> Unit = {},
    ) {
        withReady(
            onFailure = { responseCode ->
                markUnavailable(responseCode)
                callbackOnMain(PremiumPurchaseCheckResult.Failed(responseCode), callback)
            },
            action = { refreshPurchasesInternal(callback) },
        )
    }

    /** Queries current ownership and fresh ProductDetails before launching Google Play UI. */
    fun launchPurchase(
        activity: Activity,
        callback: (PremiumBillingActionResult) -> Unit = {},
    ) {
        withReady(
            onFailure = { responseCode ->
                callbackOnMain(PremiumBillingActionResult.Failed(responseCode), callback)
            },
            action = {
                queryOwnedPurchases { queryResult, evaluation ->
                    if (queryResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        callbackOnMain(
                            PremiumBillingActionResult.Failed(queryResult.responseCode),
                            callback,
                        )
                        return@queryOwnedPurchases
                    }
                    applySuccessfulOwnershipEvaluation(evaluation)
                    when (evaluation) {
                        is PremiumPurchaseEvaluation.Purchased -> callbackOnMain(
                            PremiumBillingActionResult.PremiumOwned,
                            callback,
                        )
                        PremiumPurchaseEvaluation.Pending -> callbackOnMain(
                            PremiumBillingActionResult.PurchasePending,
                            callback,
                        )
                        PremiumPurchaseEvaluation.Free ->
                            queryFreshProductAndLaunch(activity, callback)
                    }
                }
            },
        )
    }

    /** Intended for process teardown/tests; normal Activities share [PremiumBillingProvider]. */
    fun close() {
        if (closed) return
        closed = true
        runOnMain {
            pendingReadyActions.clear()
            billingClient.endConnection()
            mutableState.value = mutableState.value.copy(
                connection = PremiumBillingConnectionState.CLOSED,
            )
        }
    }

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        runOnMain {
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val records = purchases.orEmpty().map(Purchase::toPremiumPurchaseRecord)
                    val evaluation = PremiumPurchasePolicy.evaluate(productId, records)
                    if (evaluation == PremiumPurchaseEvaluation.Free) {
                        // This callback is a delta, not an authoritative inventory snapshot.
                        refreshPurchasesInternal()
                    } else {
                        applySuccessfulOwnershipEvaluation(evaluation)
                    }
                }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                    refreshPurchasesInternal()
                BillingClient.BillingResponseCode.USER_CANCELED ->
                    mutableState.value = mutableState.value.copy(
                        lastResponseCode = billingResult.responseCode,
                    )
                else -> mutableState.value = mutableState.value.copy(
                    lastResponseCode = billingResult.responseCode,
                )
            }
        }
    }

    private fun refreshPurchasesInternal(
        callback: (PremiumPurchaseCheckResult) -> Unit = {},
    ) {
        queryOwnedPurchases { billingResult, evaluation ->
            val result = if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                applySuccessfulOwnershipEvaluation(evaluation)
                when (evaluation) {
                    is PremiumPurchaseEvaluation.Purchased ->
                        PremiumPurchaseCheckResult.PremiumOwned
                    PremiumPurchaseEvaluation.Pending ->
                        PremiumPurchaseCheckResult.PurchasePending
                    PremiumPurchaseEvaluation.Free -> PremiumPurchaseCheckResult.Free
                }
            } else {
                mutableState.value = mutableState.value.copy(
                    connection = PremiumBillingConnectionState.UNAVAILABLE,
                    lastResponseCode = billingResult.responseCode,
                )
                PremiumPurchaseCheckResult.Failed(billingResult.responseCode)
            }
            callbackOnMain(result, callback)
        }
    }

    private fun queryOwnedPurchases(
        callback: (BillingResult, PremiumPurchaseEvaluation) -> Unit,
    ) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            runOnMain {
                val records = purchases.map(Purchase::toPremiumPurchaseRecord)
                callback(billingResult, PremiumPurchasePolicy.evaluate(productId, records))
            }
        }
    }

    private fun applySuccessfulOwnershipEvaluation(evaluation: PremiumPurchaseEvaluation) {
        when (evaluation) {
            is PremiumPurchaseEvaluation.Purchased -> {
                val purchase = evaluation.purchase
                livePurchaseEvidenceDigest = purchaseTokenSha256(purchase.purchaseToken)
                entitlementStore.recordOwned(
                    productId = productId,
                    purchaseToken = purchase.purchaseToken,
                    observedAtEpochMillis = nowEpochMillis(),
                )
                mutableState.value = mutableState.value.copy(
                    connection = PremiumBillingConnectionState.READY,
                    entitlement = PremiumEntitlementStatus.PREMIUM,
                    entitlementSource = PremiumEntitlementSource.LIVE_PLAY_QUERY,
                    lastResponseCode = BillingClient.BillingResponseCode.OK,
                )
                if (!purchase.acknowledged) acknowledge(purchase.purchaseToken)
                // A commit failure does not hide an entitlement returned by the live Play query,
                // but it deliberately leaves no restart grant in app-private storage.
            }
            PremiumPurchaseEvaluation.Pending -> {
                livePurchaseEvidenceDigest = null
                entitlementStore.clear()
                mutableState.value = mutableState.value.copy(
                    connection = PremiumBillingConnectionState.READY,
                    entitlement = PremiumEntitlementStatus.PENDING,
                    entitlementSource = PremiumEntitlementSource.LIVE_PLAY_QUERY,
                    lastResponseCode = BillingClient.BillingResponseCode.OK,
                )
            }
            PremiumPurchaseEvaluation.Free -> {
                livePurchaseEvidenceDigest = null
                entitlementStore.clear()
                mutableState.value = mutableState.value.copy(
                    connection = PremiumBillingConnectionState.READY,
                    entitlement = PremiumEntitlementStatus.FREE,
                    entitlementSource = PremiumEntitlementSource.LIVE_PLAY_QUERY,
                    lastResponseCode = BillingClient.BillingResponseCode.OK,
                )
            }
        }
    }

    private fun acknowledge(purchaseToken: String) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                runOnMain {
                    mutableState.value = mutableState.value.copy(
                        lastResponseCode = billingResult.responseCode,
                    )
                }
            }
        }
    }

    private fun queryProductPresentation() {
        queryProductDetails { billingResult, product, offer ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                mutableState.value = mutableState.value.copy(
                    lastResponseCode = billingResult.responseCode,
                )
                return@queryProductDetails
            }
            mutableState.value = mutableState.value.copy(
                formattedPrice = offer?.formattedPrice,
                productName = product?.name,
                lastResponseCode = BillingClient.BillingResponseCode.OK,
            )
        }
    }

    private fun queryFreshProductAndLaunch(
        activity: Activity,
        callback: (PremiumBillingActionResult) -> Unit,
    ) {
        queryProductDetails { billingResult, product, offer ->
            if (
                billingResult.responseCode != BillingClient.BillingResponseCode.OK ||
                product == null || offer == null
            ) {
                val result = if (
                    billingResult.responseCode == BillingClient.BillingResponseCode.OK ||
                    billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_UNAVAILABLE
                ) {
                    PremiumBillingActionResult.ProductUnavailable
                } else {
                    PremiumBillingActionResult.Failed(billingResult.responseCode)
                }
                callbackOnMain(result, callback)
                return@queryProductDetails
            }
            val offerToken = offer.offerToken?.takeIf(String::isNotBlank)
            if (offerToken == null) {
                callbackOnMain(PremiumBillingActionResult.ProductUnavailable, callback)
                return@queryProductDetails
            }
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(product)
                .setOfferToken(offerToken)
                .build()
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()
            runOnMain {
                val launchResult = billingClient.launchBillingFlow(activity, flowParams)
                val result = if (
                    launchResult.responseCode == BillingClient.BillingResponseCode.OK
                ) {
                    PremiumBillingActionResult.PurchaseFlowStarted
                } else {
                    PremiumBillingActionResult.Failed(launchResult.responseCode)
                }
                callback(result)
            }
        }
    }

    private fun queryProductDetails(
        callback: (
            BillingResult,
            ProductDetails?,
            ProductDetails.OneTimePurchaseOfferDetails?,
        ) -> Unit,
    ) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        billingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            runOnMain {
                val details = queryResult.productDetailsList.firstOrNull { candidate ->
                    candidate.productId == productId &&
                        candidate.productType == BillingClient.ProductType.INAPP
                }
                val offers = details?.oneTimePurchaseOfferDetailsList.orEmpty()
                val permanentOffer = offers
                    .filter { offer ->
                        offer.rentalDetails == null && !offer.offerToken.isNullOrBlank()
                    }
                    .sortedWith(
                        compareBy<ProductDetails.OneTimePurchaseOfferDetails> { offer ->
                            !offer.offerId.isNullOrBlank()
                        }.thenBy { offer -> offer.priceAmountMicros },
                    )
                    .firstOrNull()
                    ?: details?.oneTimePurchaseOfferDetails
                        ?.takeIf { offer ->
                            offer.rentalDetails == null && !offer.offerToken.isNullOrBlank()
                        }
                callback(billingResult, details, permanentOffer)
            }
        }
    }

    private fun withReady(onFailure: (Int) -> Unit, action: () -> Unit) {
        runOnMain {
            if (closed) {
                onFailure(BillingClient.BillingResponseCode.ERROR)
                return@runOnMain
            }
            if (initialSetupCompleted) {
                action()
                return@runOnMain
            }
            pendingReadyActions.addLast(ReadyAction(onFailure, action))
            if (connectionStarted) return@runOnMain
            connectionStarted = true
            mutableState.value = mutableState.value.copy(
                connection = PremiumBillingConnectionState.CONNECTING,
            )
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    runOnMain { finishInitialSetup(billingResult) }
                }

                override fun onBillingServiceDisconnected() {
                    // enableAutoServiceReconnection reconnects on the next BillingClient API call.
                    runOnMain {
                        mutableState.value = mutableState.value.copy(
                            connection = PremiumBillingConnectionState.UNAVAILABLE,
                            lastResponseCode = BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                        )
                    }
                }
            })
        }
    }

    private fun finishInitialSetup(billingResult: BillingResult) {
        connectionStarted = false
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            initialSetupCompleted = true
            mutableState.value = mutableState.value.copy(
                connection = PremiumBillingConnectionState.READY,
                lastResponseCode = BillingClient.BillingResponseCode.OK,
            )
            while (pendingReadyActions.isNotEmpty()) pendingReadyActions.removeFirst().action()
        } else {
            mutableState.value = mutableState.value.copy(
                connection = PremiumBillingConnectionState.UNAVAILABLE,
                lastResponseCode = billingResult.responseCode,
            )
            while (pendingReadyActions.isNotEmpty()) {
                pendingReadyActions.removeFirst().onFailure(billingResult.responseCode)
            }
        }
    }

    private fun markUnavailable(responseCode: Int) {
        runOnMain {
            mutableState.value = mutableState.value.copy(
                connection = PremiumBillingConnectionState.UNAVAILABLE,
                lastResponseCode = responseCode,
            )
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun <T> callbackOnMain(value: T, callback: (T) -> Unit) {
        runOnMain { callback(value) }
    }

    private data class ReadyAction(
        val onFailure: (Int) -> Unit,
        val action: () -> Unit,
    )
}

object PremiumBillingProvider {
    @Volatile
    private var instance: GooglePlayPremiumBilling? = null

    fun get(context: Context): GooglePlayPremiumBilling =
        instance?.takeUnless(GooglePlayPremiumBilling::isClosed) ?: synchronized(this) {
            instance?.takeUnless(GooglePlayPremiumBilling::isClosed) ?: GooglePlayPremiumBilling(
                context = context.applicationContext,
                productId = BuildConfig.PREMIUM_PRODUCT_ID,
                entitlementStore = PremiumEntitlementStore(context.applicationContext),
            ).also { created -> instance = created }
        }

    internal fun currentPurchaseEvidenceDigest(): String? =
        instance
            ?.takeUnless(GooglePlayPremiumBilling::isClosed)
            ?.currentPurchaseEvidenceDigest

    internal fun isPremiumInMemory(): Boolean = currentPurchaseEvidenceDigest() != null
}

private fun Purchase.toPremiumPurchaseRecord(): PremiumPurchaseRecord = PremiumPurchaseRecord(
    productIds = products.toSet(),
    state = when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> PremiumPurchaseState.PURCHASED
        Purchase.PurchaseState.PENDING -> PremiumPurchaseState.PENDING
        else -> PremiumPurchaseState.OTHER
    },
    purchaseToken = purchaseToken,
    acknowledged = isAcknowledged,
    purchaseTimeEpochMillis = purchaseTime,
)
