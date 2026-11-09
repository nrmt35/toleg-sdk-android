package com.belgilabs.toleg

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal enum class TolegSheetState { IDLE, LOADING, SUCCESS, FAILURE, UNKNOWN, UPDATE_REQUIRED }

/**
 * Toleg payment bottom sheet:
 * header, merchant row, amount / order id box and a state-dependent action area.
 */
internal class TolegPaymentSheet(
    activity: Activity,
    private val request: TolegPaymentRequest,
    private val colors: TolegColors,
    private val strings: TolegStrings,
    private val showOrderId: Boolean,
    private val onContinue: () -> Unit,
    private val onUnknownAcknowledged: () -> Unit,
    private val onUpdate: () -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val handler = Handler(Looper.getMainLooper())
    private val fonts = TolegFonts.get(activity)
    private val hostWindow = activity.window
    private lateinit var scrim: View
    private lateinit var sheetHolder: FrameLayout
    private lateinit var card: LinearLayout
    private lateinit var actionSpacer: View
    private lateinit var actionContainer: FrameLayout
    private var state = TolegSheetState.IDLE
    private var closing = false
    private var closedNotified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(true) // system back closes the sheet
        setCanceledOnTouchOutside(false) // taps outside do not close it
        setContentView(buildContent())
        window?.let(::setUpWindow)
        setOnDismissListener { notifyClosed() }
        render(state)
    }

    /**
     * Full-screen transparent window drawn behind the system bars. The dim is our own [scrim]
     * view (not FLAG_DIM_BEHIND) so it can fade in and out together with the sheet.
     */
    @Suppress("DEPRECATION")
    private fun setUpWindow(window: android.view.Window) = window.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(MATCH_PARENT, MATCH_PARENT)
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        setWindowAnimations(0)
        statusBarColor = Color.TRANSPARENT
        navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            setDecorFitsSystemWindows(false)
            // Keep the host's status / navigation bar icon colors.
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val hostAppearance = hostWindow?.insetsController?.systemBarsAppearance ?: 0
            insetsController?.setSystemBarsAppearance(hostAppearance and mask, mask)
        } else {
            val hostLight = (hostWindow?.decorView?.systemUiVisibility ?: 0) and
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                hostLight
        }
    }

    override fun onStart() {
        super.onStart()
        // Position the sheet off-screen before its first frame, then slide it up.
        sheetHolder.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                sheetHolder.viewTreeObserver.removeOnPreDrawListener(this)
                sheetHolder.translationY = sheetHolder.height.toFloat()
                sheetHolder.animate()
                    .translationY(0f)
                    .setDuration(OPEN_DURATION)
                    .setInterpolator(EMPHASIZED_DECELERATE)
                    .withLayer()
                    .start()
                scrim.animate()
                    .alpha(1f)
                    .setDuration(OPEN_DURATION - 100)
                    .setInterpolator(STANDARD_DECELERATE)
                    .start()
                return true
            }
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        close()
    }

    /** Animated close; [onClosed] fires once the sheet is gone. */
    fun close() {
        if (closing || !isShowing) {
            if (!isShowing) notifyClosed()
            return
        }
        closing = true
        handler.removeCallbacksAndMessages(null)
        scrim.animate().cancel()
        scrim.animate()
            .alpha(0f)
            .setDuration(CLOSE_DURATION)
            .setInterpolator(STANDARD_ACCELERATE)
            .start()
        sheetHolder.animate().cancel()
        sheetHolder.animate()
            .translationY(sheetHolder.height.toFloat())
            .setDuration(CLOSE_DURATION)
            .setInterpolator(EMPHASIZED_ACCELERATE)
            .withLayer()
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dismissSafely()
                }
            })
            .start()
    }

    /** Immediate close without animation (host activity going away). */
    fun dismissNow() {
        handler.removeCallbacksAndMessages(null)
        dismissSafely()
        notifyClosed()
    }

    private fun dismissSafely() {
        try {
            if (isShowing) dismiss()
        } catch (_: IllegalArgumentException) {
            // Window already detached from its activity.
        }
        notifyClosed()
    }

    private fun notifyClosed() {
        if (closedNotified) return
        closedNotified = true
        handler.removeCallbacksAndMessages(null)
        onClosed()
    }

    fun render(newState: TolegSheetState) {
        state = newState
        if (!::actionContainer.isInitialized) return

        actionSpacer.layoutParams.height = dp(if (newState == TolegSheetState.UPDATE_REQUIRED) 8 else 20)
        actionSpacer.requestLayout()
        actionContainer.removeAllViews()
        actionContainer.addView(
            when (newState) {
                TolegSheetState.IDLE, TolegSheetState.LOADING -> continueButton(newState == TolegSheetState.LOADING)
                TolegSheetState.SUCCESS -> statusView(R.drawable.toleg_ic_check, TolegColors.PRIMARY_BLUE, strings.successLabel)
                TolegSheetState.FAILURE -> statusView(R.drawable.toleg_ic_close, colors.error, strings.failureLabel)
                TolegSheetState.UNKNOWN -> unknownView()
                TolegSheetState.UPDATE_REQUIRED -> updateRequiredView()
            },
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        if (newState == TolegSheetState.SUCCESS || newState == TolegSheetState.FAILURE) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ close() }, 1500)
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildContent(): View {
        val root = FrameLayout(context)

        // Taps outside the sheet do nothing, but must not reach the host.
        scrim = View(context).apply {
            setBackgroundColor(withAlpha(Color.BLACK, SCRIM_ALPHA))
            alpha = 0f // faded in to 1 in onStart
            isClickable = true
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        sheetHolder = FrameLayout(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
        }
        root.addView(sheetHolder, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            sheetHolder.setPadding(dp(8), dp(8), dp(8), dp(8) + bottom)
            insets
        }

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(colors.background, dp(28).toFloat())
            setPadding(dp(16), dp(16), dp(16), dp(20))
            isClickable = true
        }
        sheetHolder.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        card.addView(header())
        card.addView(space(20))
        card.addView(merchantRow())
        card.addView(space(8))
        card.addView(infoBox())
        actionSpacer = space(20)
        card.addView(actionSpacer)
        actionContainer = FrameLayout(context)
        card.addView(actionContainer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun header(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            text(strings.payWithToleg, 22f, fonts.semiBold, colors.primaryText),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        val close = FrameLayout(context).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(colors.closeIcon, 0.2f)),
                oval(colors.surface),
                oval(Color.WHITE),
            )
            contentDescription = "Close"
            isClickable = true
            setOnClickListener { close() }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.toleg_ic_close)
                    imageTintList = ColorStateList.valueOf(colors.closeIcon)
                },
                FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER),
            )
        }
        addView(close, LinearLayout.LayoutParams(dp(32), dp(32)))
    }

    private fun merchantRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.toleg_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(TolegColors.PRIMARY_BLUE, dp(14).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(space(0, width = 8))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Tmcell Töleg", 16f, fonts.semiBold, colors.primaryText))
                addView(text("Altyn Asyr CJSC", 14f, fonts.medium, colors.secondaryText))
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
    }

    private fun infoBox(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(colors.surface, dp(16).toFloat())
        addView(labelValue(strings.amount, request.formattedAmount, fonts.semiBold))
        if (showOrderId) {
            addView(
                View(context).apply { setBackgroundColor(colors.divider) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                },
            )
            addView(labelValue(strings.orderId, request.orderId, fonts.regular))
        }
    }

    private fun labelValue(label: String, value: String, valueFont: Typeface): TextView {
        val builder = SpannableStringBuilder()
        builder.append("$label: ", TypefaceSpan(fonts.regular), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(ForegroundColorSpan(colors.secondaryText), 0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = builder.length
        builder.append(value)
        builder.setSpan(TypefaceSpan(valueFont), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(ForegroundColorSpan(colors.primaryText), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextView(context).apply {
            text = builder
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = fonts.regular
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
    }

    // --------------------------------------------------------------- actions

    private fun continueButton(loading: Boolean): View {
        val button = pillButton(strings.continueLabel) { if (state == TolegSheetState.IDLE) onContinue() }
        if (loading) {
            button.isEnabled = false
            button.background = pill(withAlpha(TolegColors.PRIMARY_BLUE, 0.6f))
            button.removeAllViews()
            button.addView(
                ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(TolegColors.ON_PRIMARY)
                },
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER),
            )
        }
        return button
    }

    private fun statusView(icon: Int, badgeColor: Int, label: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val badge = FrameLayout(context).apply {
            background = oval(badgeColor)
            addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(TolegColors.ON_PRIMARY)
                },
                FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER),
            )
            scaleX = 0f
            scaleY = 0f
            alpha = 0f
        }
        addView(badge, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(space(12))
        addView(text(label, 17f, fonts.semiBold, colors.primaryText).apply { gravity = Gravity.CENTER })
        badge.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(1.70158f))
            .start()
    }

    private fun unknownView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(strings.transactionUnknown, 15f, fonts.regular, colors.errorText))
        addView(space(20))
        addView(
            pillButton(strings.understood) {
                onUnknownAcknowledged()
                close()
            },
        )
    }

    private fun updateRequiredView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val banner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(withAlpha(TolegColors.UPDATE_ERROR, 0.1f), dp(16).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.toleg_ic_warning)
                    imageTintList = ColorStateList.valueOf(TolegColors.UPDATE_ERROR)
                },
                LinearLayout.LayoutParams(dp(20), dp(20)),
            )
            addView(space(0, width = 8))
            addView(
                text(strings.updateRequired, 14f, fonts.medium, TolegColors.UPDATE_ERROR),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
        }
        addView(banner)
        addView(space(18))
        addView(pillButton(strings.update) { onUpdate() })
    }

    // --------------------------------------------------------------- helpers

    private fun pillButton(label: String, onClick: () -> Unit): FrameLayout = FrameLayout(context).apply {
        background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            pill(TolegColors.PRIMARY_BLUE),
            pill(Color.WHITE),
        )
        isClickable = true
        isFocusable = true
        minimumHeight = dp(48)
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, dp(48))
        setOnClickListener { onClick() }
        addView(
            text(label, 16f, fonts.semiBold, TolegColors.ON_PRIMARY).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER),
        )
    }

    private fun text(value: String, sizeSp: Float, font: Typeface, color: Int) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = font
        setTextColor(color)
        includeFontPadding = true
    }

    private fun space(heightDp: Int, width: Int = 0) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(if (width > 0) dp(width) else MATCH_PARENT, dp(heightDp))
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun pill(color: Int) = rounded(color, dp(999).toFloat())

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val SCRIM_ALPHA = 0.54f
        const val OPEN_DURATION = 400L
        const val CLOSE_DURATION = 250L

        // Material 3 motion curves.
        val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        val STANDARD_DECELERATE = PathInterpolator(0f, 0f, 0f, 1f)
        val STANDARD_ACCELERATE = PathInterpolator(0.3f, 0f, 1f, 1f)
    }

    private class TypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(paint: Paint) {
            paint.typeface = typeface
        }
    }
}

/** Inter font family bundled with the SDK (only the weights the sheet uses). */
internal class TolegFonts private constructor(
    val regular: Typeface,
    val medium: Typeface,
    val semiBold: Typeface,
) {
    companion object {
        @Volatile
        private var instance: TolegFonts? = null

        fun get(context: Context): TolegFonts = instance ?: synchronized(this) {
            instance ?: load(context.applicationContext).also { instance = it }
        }

        private fun load(context: Context): TolegFonts {
            fun font(name: String, fallback: Typeface): Typeface = try {
                Typeface.createFromAsset(context.assets, "toleg_sdk/fonts/$name")
            } catch (_: RuntimeException) {
                fallback
            }
            return TolegFonts(
                regular = font("Inter-Regular.ttf", Typeface.DEFAULT),
                medium = font("Inter-Medium.ttf", Typeface.DEFAULT),
                semiBold = font("Inter-SemiBold.ttf", Typeface.DEFAULT_BOLD),
            )
        }
    }
}
