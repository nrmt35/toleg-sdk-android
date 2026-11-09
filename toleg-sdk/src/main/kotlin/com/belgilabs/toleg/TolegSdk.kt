package com.belgilabs.toleg

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Called each time the user comes back to the app after being sent to Toleg
 * (deep-link redirect or manual app switch), and on every poll while the payment
 * is [TolegPaymentStatus.UNFINISHED]. Fetch the order status from your backend
 * using the `orderId` you passed and return it. If it throws (e.g. no network),
 * the SDK treats the payment as UNFINISHED and retries on the next poll.
 */
typealias TolegReturnHandler = suspend () -> TolegPaymentStatus

/**
 * Options for [TolegSdk.pay].
 *
 * @property themeMode sheet color scheme; [TolegThemeMode.SYSTEM] follows the activity.
 * @property locale sheet language (`en`, `tk`, `ru`); defaults to the activity locale, falling back to English.
 * @property showOrderId show the order id row in the sheet.
 * @property refreshIntervalMillis polling interval while the status is UNFINISHED, clamped to 20–60 s.
 * @property checkTolegVersion show the "update Toleg" state when Toleg is missing or older than 2.15.2.
 *   Leave `true` in production; set `false` only to test the flow on a device without Toleg.
 */
data class TolegPayOptions @JvmOverloads constructor(
    val themeMode: TolegThemeMode = TolegThemeMode.SYSTEM,
    val locale: Locale? = null,
    val showOrderId: Boolean = true,
    val refreshIntervalMillis: Long = TolegSdk.MIN_REFRESH_INTERVAL_MILLIS,
    val checkTolegVersion: Boolean = true,
)

/** Callback-style API for Java or non-coroutine callers. All methods run on the main thread. */
interface TolegPaymentCallback {
    /** Same as [TolegReturnHandler]: check your backend, then call [resolver] exactly once. */
    fun onReturn(resolver: TolegStatusResolver)

    /** The sheet closed. [finalStatus] is SUCCESS / FAILED / UNKNOWN, or null when the user closed it first. */
    fun onFinished(finalStatus: TolegPaymentStatus?) {}

    /** Toleg could not be opened. The sheet is already closed. */
    fun onError(error: TolegSdkException) {}
}

fun interface TolegStatusResolver {
    fun resolve(status: TolegPaymentStatus)
}

/**
 * Accepts payments through the Toleg app (app-to-app).
 *
 * ```
 * private val toleg = TolegSdk()
 *
 * lifecycleScope.launch {
 *     val status = toleg.pay(this@CheckoutActivity, request) {
 *         api.orderStatus(request.orderId) // -> TolegPaymentStatus
 *     }
 * }
 * ```
 */
class TolegSdk {

    private var session: PaymentSession? = null

    /**
     * Shows the Toleg payment sheet over [activity] and suspends until it closes.
     *
     * @return the final status shown to the user (SUCCESS, FAILED or UNKNOWN),
     *   or `null` when the user closed the sheet before the payment resolved.
     * @throws TolegSdkException when the Toleg deep link cannot be opened.
     */
    suspend fun pay(
        activity: Activity,
        request: TolegPaymentRequest,
        options: TolegPayOptions = TolegPayOptions(),
        onReturn: TolegReturnHandler,
    ): TolegPaymentStatus? = withContext(Dispatchers.Main.immediate) {
        session?.cancel()
        val updateRequired = options.checkTolegVersion && TolegAppChecker.isUpdateRequired(activity)
        val current = PaymentSession(activity, request, options, onReturn, updateRequired)
        session = current
        try {
            current.run()
        } finally {
            if (session === current) session = null
        }
    }

    /** Callback variant of [pay] for Java callers. Must be called on the main thread. */
    @JvmOverloads
    fun pay(
        activity: Activity,
        request: TolegPaymentRequest,
        options: TolegPayOptions = TolegPayOptions(),
        callback: TolegPaymentCallback,
    ) {
        MainScope().launch {
            try {
                val status = pay(activity, request, options) {
                    suspendCancellableCoroutine { cont ->
                        callback.onReturn { status -> if (cont.isActive) cont.resume(status) }
                    }
                }
                callback.onFinished(status)
            } catch (e: TolegSdkException) {
                callback.onError(e)
            } finally {
                cancel()
            }
        }
    }

    /**
     * Optional: forward deep links from `onCreate` / `onNewIntent`. Returning to the app
     * is detected automatically through the activity lifecycle, so this only speeds
     * up the check when the intent matches the active request's `returnUrl`.
     *
     * @return `true` when the intent belonged to the active Toleg payment.
     */
    fun handleIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        return session?.handleUri(data) ?: false
    }

    /** Closes any open sheet and stops all timers. */
    fun dispose() {
        session?.cancel()
        session = null
    }

    companion object {
        const val MIN_REFRESH_INTERVAL_MILLIS = 20_000L
        const val MAX_REFRESH_INTERVAL_MILLIS = 60_000L
        internal const val UNFINISHED_TIMEOUT_MILLIS = 5 * 60_000L

        // Toleg's deep link back delivers both an intent and a resume; this window
        // collapses them into a single onReturn call.
        internal const val RETURN_DEBOUNCE_MILLIS = 600L

        /** `true` when Toleg is missing or older than the minimum supported version. */
        @JvmStatic
        fun isTolegUpdateRequired(activity: Activity): Boolean = TolegAppChecker.isUpdateRequired(activity)

        internal fun matchesReturnUrl(incoming: Uri, expected: Uri): Boolean {
            if (!incoming.scheme.equals(expected.scheme, ignoreCase = true)) return false
            val host = expected.host.orEmpty()
            if (host.isNotEmpty() && !incoming.host.equals(host, ignoreCase = true)) return false
            val path = expected.path.orEmpty()
            if (path.isNotEmpty() && incoming.path != path) return false
            return true
        }
    }
}

/** One payment attempt: owns the sheet, lifecycle hook, debounce and polling timers. */
private class PaymentSession(
    private val activity: Activity,
    private val request: TolegPaymentRequest,
    private val options: TolegPayOptions,
    private val onReturn: TolegReturnHandler,
    updateRequired: Boolean,
) : Application.ActivityLifecycleCallbacks {

    private val scope: CoroutineScope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshInterval = options.refreshIntervalMillis.coerceIn(
        TolegSdk.MIN_REFRESH_INTERVAL_MILLIS,
        TolegSdk.MAX_REFRESH_INTERVAL_MILLIS,
    )

    private var state = if (updateRequired) TolegSheetState.UPDATE_REQUIRED else TolegSheetState.IDLE
    private lateinit var sheet: TolegPaymentSheet
    private var continuation: CancellableContinuation<TolegPaymentStatus?>? = null

    // True from the moment Toleg is launched until a final status or dismissal.
    private var awaitingReturn = false
    private var handlingReturn = false
    private var resolved = false
    private var returnJob: Job? = null
    private var pollJob: Job? = null
    private var deadlineJob: Job? = null
    private var finalStatus: TolegPaymentStatus? = null
    private var error: TolegSdkException? = null
    private var finished = false

    suspend fun run(): TolegPaymentStatus? = suspendCancellableCoroutine { cont ->
        continuation = cont
        cont.invokeOnCancellation { mainHandler.post { cancel() } }
        start()
    }

    private fun start() {
        if (activity.isFinishing || activity.isDestroyed) {
            finish()
            return
        }
        val locale = options.locale ?: activity.resources.configuration.locales[0] ?: Locale.getDefault()
        sheet = TolegPaymentSheet(
            activity = activity,
            request = request,
            colors = TolegColors.resolve(activity, options.themeMode),
            strings = TolegStrings.of(locale),
            showOrderId = options.showOrderId,
            onContinue = ::onContinue,
            onUnknownAcknowledged = ::cancelPolling,
            onUpdate = ::openPlayStore,
            onClosed = ::finish,
        )
        activity.application.registerActivityLifecycleCallbacks(this)
        sheet.show()
        sheet.render(state)
    }

    /** Closes the sheet immediately (new payment started, activity destroyed, caller cancelled). */
    fun cancel() {
        if (finished) return
        if (::sheet.isInitialized) sheet.dismissNow() else finish()
    }

    fun handleUri(uri: Uri): Boolean {
        if (resolved || !TolegSdk.matchesReturnUrl(uri, Uri.parse(request.returnUrl))) return false
        scheduleReturn()
        return true
    }

    private fun setState(newState: TolegSheetState) {
        state = newState
        if (::sheet.isInitialized && !finished) sheet.render(newState)
    }

    private fun onContinue() {
        if (state != TolegSheetState.IDLE) return
        setState(TolegSheetState.LOADING)
        val uri = request.buildUri()
        // NEW_TASK keeps Toleg out of the host's task, so the returnUrl deep link lands on the
        // existing (singleTop) activity instead of stacking a new one on top of Toleg.
        val view = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            try {
                // Open Toleg directly: if its tolegapp.com app link isn't verified on the
                // device, a plain https intent would go to the browser instead.
                activity.startActivity(Intent(view).setPackage(TolegAppChecker.PACKAGE_NAME))
            } catch (_: ActivityNotFoundException) {
                activity.startActivity(view) // Toleg not installed: let the browser handle it
            }
            awaitingReturn = true
        } catch (e: ActivityNotFoundException) {
            setState(TolegSheetState.IDLE)
            error = TolegSdkException("Could not launch Toleg deeplink: $uri", e)
            sheet.dismissNow()
        }
    }

    private fun openPlayStore() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TolegAppChecker.PLAY_STORE_URL)))
        } catch (_: ActivityNotFoundException) {
            // No store or browser available; nothing else to do.
        }
    }

    // ------------------------------------------------------------ returning

    private fun scheduleReturn() {
        if (!awaitingReturn || resolved || finished) return
        returnJob?.cancel()
        returnJob = scope.launch {
            delay(TolegSdk.RETURN_DEBOUNCE_MILLIS)
            completeReturn()
        }
    }

    private suspend fun completeReturn() {
        if (!awaitingReturn || handlingReturn || resolved) return
        handlingReturn = true
        try {
            handleStatus(checkStatus())
        } finally {
            handlingReturn = false
        }
    }

    /** Runs the host's handler; a thrown error (e.g. network) counts as UNFINISHED so polling retries. */
    private suspend fun checkStatus(): TolegPaymentStatus = try {
        onReturn()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TolegPaymentStatus.UNFINISHED
    }

    private fun handleStatus(status: TolegPaymentStatus) {
        if (resolved || finished) return
        when (status) {
            TolegPaymentStatus.SUCCESS -> resolve(status, TolegSheetState.SUCCESS)
            TolegPaymentStatus.FAILED -> resolve(status, TolegSheetState.FAILURE)
            TolegPaymentStatus.UNKNOWN -> resolve(status, TolegSheetState.UNKNOWN)
            TolegPaymentStatus.UNFINISHED -> {
                // Keep awaiting so reopening the app re-checks, and poll until the deadline.
                setState(TolegSheetState.LOADING)
                startPolling()
            }
        }
    }

    private fun resolve(status: TolegPaymentStatus, sheetState: TolegSheetState) {
        cancelPolling()
        awaitingReturn = false
        resolved = true
        finalStatus = status
        setState(sheetState)
    }

    private fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (true) {
                delay(refreshInterval)
                if (resolved || handlingReturn) continue
                handlingReturn = true
                try {
                    handleStatus(checkStatus())
                } finally {
                    handlingReturn = false
                }
            }
        }
        deadlineJob = scope.launch {
            delay(TolegSdk.UNFINISHED_TIMEOUT_MILLIS)
            // Did not resolve in time: show the "unknown" message.
            handleStatus(TolegPaymentStatus.UNKNOWN)
        }
    }

    private fun cancelPolling() {
        pollJob?.cancel()
        pollJob = null
        deadlineJob?.cancel()
        deadlineJob = null
    }

    private fun finish() {
        if (finished) return
        finished = true
        awaitingReturn = false
        returnJob?.cancel()
        cancelPolling()
        scope.cancel()
        activity.application.unregisterActivityLifecycleCallbacks(this)
        val cont = continuation ?: return
        continuation = null
        if (!cont.isActive) return
        val failure = error
        if (failure != null) cont.resumeWithException(failure) else cont.resume(finalStatus)
    }

    // ------------------------------------------------------------ lifecycle

    override fun onActivityResumed(activity: Activity) {
        // Coming back to the app (deep link or manual switch) while a payment is in flight.
        scheduleReturn()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity === this.activity) cancel()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
