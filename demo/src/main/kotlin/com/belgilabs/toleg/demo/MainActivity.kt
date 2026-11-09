package com.belgilabs.toleg.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.belgilabs.toleg.TolegPayOptions
import com.belgilabs.toleg.TolegPaymentRequest
import com.belgilabs.toleg.TolegPaymentStatus
import com.belgilabs.toleg.TolegSdk
import com.belgilabs.toleg.TolegSdkException
import com.belgilabs.toleg.TolegThemeMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

class MainActivity : Activity() {

    private val scope = MainScope()
    private val toleg = TolegSdk()

    private lateinit var amount: EditText
    private lateinit var orderId: EditText
    private lateinit var status: RadioGroup
    private lateinit var language: Spinner
    private lateinit var theme: Spinner
    private lateinit var showOrderId: Switch
    private lateinit var checkVersion: Switch
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        amount = findViewById(R.id.amount)
        orderId = findViewById(R.id.orderId)
        status = findViewById(R.id.status)
        language = findViewById(R.id.language)
        theme = findViewById(R.id.theme)
        showOrderId = findViewById(R.id.showOrderId)
        checkVersion = findViewById(R.id.checkVersion)
        log = findViewById(R.id.log)

        language.adapter = adapter(LANGUAGES.map { it.first })
        theme.adapter = adapter(TolegThemeMode.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) })
        newOrderId()

        findViewById<Button>(R.id.pay).setOnClickListener { pay() }
        logEvent("Toleg update required: ${TolegSdk.isTolegUpdateRequired(this)}")
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        // Optional: the SDK also detects the return through the activity lifecycle.
        val handled = toleg.handleIntent(intent)
        logEvent("Deep link: $data (handled by SDK: $handled)")
    }

    private fun pay() {
        val tmt = amount.text.toString().replace(',', '.').toDoubleOrNull()
        if (tmt == null || tmt <= 0) {
            amount.error = "Enter an amount"
            return
        }
        val request = TolegPaymentRequest(
            merchantId = MERCHANT_ID,
            amount = (tmt * 100).roundToLong(), // tenne
            returnUrl = RETURN_URL,
            orderId = orderId.text.toString().ifBlank { newOrderId() },
        )
        val options = TolegPayOptions(
            themeMode = TolegThemeMode.entries[theme.selectedItemPosition],
            locale = LANGUAGES[language.selectedItemPosition].second,
            showOrderId = showOrderId.isChecked,
            checkTolegVersion = checkVersion.isChecked,
        )
        logEvent("pay(): ${request.formattedAmount}, order ${request.orderId}")
        logEvent("URL: ${request.buildUrl()}")

        var checks = 0
        scope.launch {
            try {
                val result = toleg.pay(this@MainActivity, request, options) {
                    // Back in the app: ask "your backend" for the order status.
                    checks++
                    logEvent("onReturn #$checks → checking order ${request.orderId}…")
                    delay(1_000)
                    simulatedBackendStatus(checks).also { logEvent("onReturn #$checks ← $it") }
                }
                logEvent("Sheet closed, final status: ${result ?: "none (closed by user)"}")
            } catch (e: TolegSdkException) {
                logEvent("Error: ${e.message}")
            }
            newOrderId()
        }
    }

    /** Stand-in for your backend's order-status endpoint. */
    private fun simulatedBackendStatus(check: Int): TolegPaymentStatus = when (status.checkedRadioButtonId) {
        R.id.statusFailed -> TolegPaymentStatus.FAILED
        R.id.statusUnknown -> TolegPaymentStatus.UNKNOWN
        R.id.statusUnfinished -> if (check < 2) TolegPaymentStatus.UNFINISHED else TolegPaymentStatus.SUCCESS
        else -> TolegPaymentStatus.SUCCESS
    }

    private fun newOrderId(): String =
        "ORDER-${System.currentTimeMillis()}".also { orderId.setText(it) }

    private fun logEvent(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.text = "$time  $message\n${log.text}"
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    override fun onDestroy() {
        toleg.dispose()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        // Replace with the merchant ID issued to you by Toleg.
        const val MERCHANT_ID = "YOUR_MERCHANT_ID"
        const val RETURN_URL = "tolegdemo://payment-result"

        val LANGUAGES = listOf(
            "System" to null,
            "Türkmen (tk)" to Locale("tk"),
            "Русский (ru)" to Locale("ru"),
            "English (en)" to Locale.ENGLISH,
        )
    }
}
