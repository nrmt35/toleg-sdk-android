package com.belgilabs.toleg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TolegPaymentRequestTest {

    private val request = TolegPaymentRequest(
        merchantId = "abc_DEF-123",
        amount = 12550,
        returnUrl = "yourapp://payment-result",
        orderId = "ORDER-1",
    )

    @Test
    fun buildsTolegUrl() {
        assertEquals(
            "https://tolegapp.com/qrpay/merchant/abc_DEF-123" +
                "?amount=12550&check_no=ORDER-1&returnUrl=yourapp%3A%2F%2Fpayment-result",
            request.buildUrl(),
        )
    }

    @Test
    fun formatsAmount() {
        assertEquals("125.50 TMT", request.formattedAmount)
        assertEquals("125 TMT", request.copy(amount = 12500).formattedAmount)
        assertEquals("0.05 TMT", request.copy(amount = 5).formattedAmount)
    }

    @Test
    fun comparesVersions() {
        assertTrue(TolegAppChecker.isVersionOlder(null, "2.15.2"))
        assertTrue(TolegAppChecker.isVersionOlder("2.15.1", "2.15.2"))
        assertTrue(TolegAppChecker.isVersionOlder("2.9", "2.15.2"))
        assertFalse(TolegAppChecker.isVersionOlder("2.15.2", "2.15.2"))
        assertFalse(TolegAppChecker.isVersionOlder("2.16", "2.15.2"))
        assertFalse(TolegAppChecker.isVersionOlder("3.0.0-beta", "2.15.2"))
    }
}
