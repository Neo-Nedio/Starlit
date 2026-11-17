package com.hfad.starlit.view.glass

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import com.hfad.starlit.R
import kotlin.math.roundToInt

/** Shared sizing, surfaces, and window treatment for floating glass dialogs. */
object GlassDialogStyler {

    fun surface(context: Context, radiusDp: Float): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.argb(248, 255, 255, 255),
            Color.argb(238, 244, 249, 255)
        )
    ).apply {
        cornerRadius = dp(context, radiusDp).toFloat()
        setStroke(dp(context, 1f), Color.argb(220, 255, 255, 255))
    }

    fun roundedStrokeBackground(
        context: Context,
        fillColor: Int,
        strokeColor: Int,
        radius: Float
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        setCornerRadius(dp(context, radius).toFloat())
        setStroke(dp(context, 1), strokeColor)
    }

    fun configureFloatingWindow(
        context: Context,
        dialog: Dialog,
        width: Int,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        gravity: Int = Gravity.CENTER,
        y: Int = 0,
        blurRadiusDp: Int = 14,
        dimAmount: Float = 0.16f
    ) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.MtDialogAnimation)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadiusDp > 0) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
            attributes = attributes.apply {
                this.gravity = gravity
                this.y = y
                this.dimAmount = dimAmount
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurBehindRadius = dp(context, blurRadiusDp)
                }
            }
            setLayout(width, height)
        }
    }

    fun selectableItemBackgroundRes(context: Context): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return typedValue.resourceId
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
