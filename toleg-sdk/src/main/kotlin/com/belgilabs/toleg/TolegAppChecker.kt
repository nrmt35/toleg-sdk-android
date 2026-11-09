package com.belgilabs.toleg

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object TolegAppChecker {
    const val PACKAGE_NAME = "com.belgilabs.tmcellpay"
    const val MIN_VERSION = "2.15.2"
    const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=$PACKAGE_NAME"

    /** `true` when Toleg is missing or older than [MIN_VERSION]. */
    fun isUpdateRequired(context: Context): Boolean =
        isVersionOlder(installedVersion(context), MIN_VERSION)

    fun installedVersion(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE_NAME, 0)
        }
        info.versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** `true` when [installed] is null or semantically lower than [minimum] (dotted numeric). */
    fun isVersionOlder(installed: String?, minimum: String): Boolean {
        if (installed == null) return true
        val a = parts(installed)
        val b = parts(minimum)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x < y) return true
            if (x > y) return false
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.split('.').map { it.trim().takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
