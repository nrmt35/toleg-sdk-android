package com.belgilabs.toleg

import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

/**
 * The result the host app returns from the `onReturn` handler.
 *
 * | Status       | Behavior                                               |
 * |--------------|--------------------------------------------------------|
 * | SUCCESS      | Shows the success animation and closes the sheet        |
 * | FAILED       | Shows the failure animation and closes the sheet        |
 * | UNKNOWN      | Shows an info message; user taps "Understood" to close  |
 * | UNFINISHED   | Keeps loading; the SDK polls until resolved or timed out |
 */
enum class TolegPaymentStatus { SUCCESS, FAILED, UNFINISHED, UNKNOWN }

/**
 * A payment to be made through the Toleg app.
 *
 * @property merchantId merchant id issued by Toleg.
 * @property amount amount in tenne (1 TMT = 100 tenne), e.g. `12550` = 125.50 TMT.
 * @property returnUrl deep link Toleg opens after payment, e.g. `yourapp://payment-result`.
 *   Its scheme must be registered by the host app (intent filter on Android).
 * @property orderId unique order identifier from your backend.
 */
data class TolegPaymentRequest(
    val merchantId: String,
    val amount: Long,
    val returnUrl: String,
    val orderId: String,
) {
    init {
        require(merchantId.isNotBlank()) { "merchantId must not be blank" }
        require(amount > 0) { "amount must be positive (in tenne)" }
        require(orderId.isNotBlank()) { "orderId must not be blank" }
        require(returnUrl.contains("://")) { "returnUrl must be a deep link like yourapp://payment-result" }
    }

    /** The Toleg deep link as a string. Opened by Toleg when installed, otherwise by the browser. */
    fun buildUrl(): String {
        val query = listOf(
            "amount" to amount.toString(),
            "check_no" to orderId,
            "returnUrl" to returnUrl,
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return "$BASE_URL/${encode(merchantId)}?$query"
    }

    /** The Toleg deep link as a [Uri]. */
    fun buildUri(): Uri = Uri.parse(buildUrl())

    /** Human-readable amount, e.g. "125.50 TMT" or "125 TMT". */
    val formattedAmount: String
        get() {
            val value = if (amount % 100 == 0L) {
                (amount / 100).toString()
            } else {
                String.format(Locale.US, "%.2f", amount / 100.0)
            }
            return "$value TMT"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val BASE_URL = "https://tolegapp.com/qrpay/merchant"
    }
}

/** Thrown when the Toleg deep link cannot be opened. */
class TolegSdkException(message: String, cause: Throwable? = null) : Exception(message, cause)
