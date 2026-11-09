package com.belgilabs.toleg

import android.content.Context
import android.content.res.Configuration

/** Color scheme of the payment sheet. [SYSTEM] follows the host activity's night mode. */
enum class TolegThemeMode { SYSTEM, LIGHT, DARK }

internal data class TolegColors(
    val background: Int,
    val surface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val errorText: Int,
    val divider: Int,
    val closeIcon: Int,
    val error: Int,
) {
    companion object {
        const val PRIMARY_BLUE = 0xFF4AACEF.toInt()
        const val ON_PRIMARY = 0xFFFFFFFF.toInt()
        const val UPDATE_ERROR = 0xFFFCB743.toInt()
        const val SCRIM = 0x8A000000.toInt()

        val LIGHT = TolegColors(
            background = 0xFFFFFFFF.toInt(),
            surface = 0xFFF8F8F8.toInt(),
            primaryText = 0xFF252525.toInt(),
            secondaryText = 0xFF808080.toInt(),
            errorText = 0xFFE73628.toInt(),
            divider = 0xFFEBEBEB.toInt(),
            closeIcon = 0xFF636366.toInt(),
            error = 0xFFE5484D.toInt(),
        )

        val DARK = TolegColors(
            background = 0xFF1C1C1E.toInt(),
            surface = 0xFF2C2C2E.toInt(),
            primaryText = 0xFFFFFFFF.toInt(),
            secondaryText = 0xFF8E8E93.toInt(),
            errorText = 0xFFE73628.toInt(),
            divider = 0xFF3A3A3C.toInt(),
            closeIcon = 0xFFAEAEB2.toInt(),
            error = 0xFFFF6166.toInt(),
        )

        fun resolve(context: Context, mode: TolegThemeMode): TolegColors {
            val dark = when (mode) {
                TolegThemeMode.LIGHT -> false
                TolegThemeMode.DARK -> true
                TolegThemeMode.SYSTEM ->
                    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
            }
            return if (dark) DARK else LIGHT
        }
    }
}
