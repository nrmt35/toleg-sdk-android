package com.belgilabs.toleg

import java.util.Locale

internal data class TolegStrings(
    val payWithToleg: String,
    val amount: String,
    val orderId: String,
    val continueLabel: String,
    val successLabel: String,
    val failureLabel: String,
    val transactionUnknown: String,
    val understood: String,
    val updateRequired: String,
    val update: String,
) {
    companion object {
        private val EN = TolegStrings(
            payWithToleg = "Pay with Töleg",
            amount = "Amount",
            orderId = "Order ID",
            continueLabel = "Continue",
            successLabel = "Payment successful",
            failureLabel = "Payment failed",
            transactionUnknown = "Currently, due to the high volume of payments, your payment status is unclear and will be determined shortly.",
            understood = "Understood",
            updateRequired = "\"Töleg\" app must be the latest version!",
            update = "Update",
        )

        private val TK = TolegStrings(
            payWithToleg = "Töleg bilen töle",
            amount = "Möçber",
            orderId = "Sargyt ID",
            continueLabel = "Dowam et",
            successLabel = "Töleg üstünlikli",
            failureLabel = "Töleg amala aşmady",
            transactionUnknown = "Häzirki wagtda tölegleriň köplügi sebäpli tölegiňiz belli däl, biraz wagtdan belli bolar.",
            understood = "Düşnükli",
            updateRequired = "\"Töleg\" programmasynyň soňky wersiýasy bolmalydyr!",
            update = "Täzele",
        )

        private val RU = TolegStrings(
            payWithToleg = "Оплата через Töleg",
            amount = "Сумма",
            orderId = "Номер заказа",
            continueLabel = "Продолжить",
            successLabel = "Оплата прошла успешно",
            failureLabel = "Оплата не удалась",
            transactionUnknown = "В настоящее время из-за большого количества платежей ваш платёж пока не ясен, он станет известен немного позже",
            understood = "Понятно",
            updateRequired = "Приложение \"Töleg\" должно быть последней версии!",
            update = "Обновить",
        )

        fun of(locale: Locale): TolegStrings = when (locale.language) {
            "tk" -> TK
            "ru" -> RU
            else -> EN
        }
    }
}
