package com.hfad.starlit.view.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.core.widget.TextViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.hfad.starlit.R
import com.hfad.starlit.data.preferences.chat.ContextLimitStore
import com.hfad.starlit.view.glass.LiquidGlass
import java.util.Locale
import kotlin.math.roundToInt

internal class ContextLimitSheet(
    private val context: Context,
    private val activity: FragmentActivity,
    private val contextTokens: () -> Int,
    private val consumedTokens: () -> Long,
    private val usageIncludesEstimate: () -> Boolean,
    private val onLimitChanged: () -> Unit
) {
    fun show() {
        val dialog = BottomSheetDialog(context)
        val content = buildContextLimitSheet()
        dialog.setContentView(content)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.background = ColorDrawable(Color.TRANSPARENT)
        }
        dialog.show()
        LiquidGlass.install(activity, content)
    }

    private fun buildContextLimitSheet(): View {
        var syncing = false
        var currentLimit = ContextLimitStore.getTokenLimit(context)
        lateinit var saveLimitAction: (Int, Boolean) -> Unit

        val scroll = ScrollView(context).apply {
            background = glassDialogSurface(26f)
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(22), dp(12), dp(22), dp(22))
        }
        scroll.addView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            View(context).apply {
                background = roundedBackground(Color.rgb(220, 226, 238), 2.5f)
            },
            LinearLayout.LayoutParams(dp(46), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(26)
            }
        )

        container.addView(
            TextView(context).apply {
                text = "调整上下文阈值"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(45, 52, 68))
                includeFontPadding = false
            }
        )

        container.addView(
            TextView(context).apply {
                text = "当前上下文是下一次请求的预计占用；累计消耗会叠加本会话的每次模型请求。"
                textSize = 15f
                setTextColor(Color.rgb(111, 119, 137))
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )

        container.addView(divider(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            topMargin = dp(26)
            bottomMargin = dp(20)
        })

        val statsRow = LinearLayout(context).apply {
            tag = "glass:card"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        val currentValue = createStatValue(formatNumber(contextTokens()), Color.rgb(45, 52, 68))
        val consumedValue = createStatValue(formatConsumedTokens(), Color.rgb(190, 112, 46))
        val targetValue = createStatValue(formatNumber(currentLimit), Color.rgb(58, 132, 226))
        val percentValue = createStatValue(formatPercent(contextTokens(), currentLimit), Color.rgb(65, 145, 126))
        statsRow.addView(createStatColumn("当前上下文", currentValue), statColumnParams())
        statsRow.addView(verticalDivider())
        statsRow.addView(createStatColumn("累计消耗", consumedValue), statColumnParams())
        statsRow.addView(verticalDivider())
        statsRow.addView(createStatColumn("目标阈值", targetValue), statColumnParams())
        statsRow.addView(verticalDivider())
        statsRow.addView(createStatColumn("占用比例", percentValue), statColumnParams())
        container.addView(statsRow)

        container.addView(divider(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            topMargin = dp(22)
            bottomMargin = dp(28)
        })

        val slider = Slider(context).apply {
            valueFrom = ContextLimitStore.MIN_TOKEN_LIMIT.toFloat()
            valueTo = ContextLimitStore.MAX_TOKEN_LIMIT.toFloat()
            value = currentLimit.toFloat()
        }
        container.addView(slider)

        val optionsGrid = GridLayout(context).apply {
            columnCount = 4
            useDefaultMargins = false
        }
        val optionButtons = mutableMapOf<Int, TextView>()
        ContextLimitStore.TOKEN_OPTIONS.forEach { option ->
            val button = TextView(context).apply {
                tag = "glass:button"
                text = formatCompactToken(option)
                gravity = Gravity.CENTER
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setOnClickListener {
                    saveLimitAction(option, true)
                }
            }
            optionButtons[option] = button
            optionsGrid.addView(
                button,
                GridLayout.LayoutParams().apply {
                    width = dp(70)
                    height = dp(48)
                    setMargins(0, 0, dp(10), dp(10))
                }
            )
        }
        container.addView(
            optionsGrid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
            }
        )

        container.addView(
            TextView(context).apply {
                text = "精确阈值"
                textSize = 15f
                setTextColor(Color.rgb(111, 119, 137))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
                leftMargin = dp(16)
            }
        )

        val exactInput = EditText(context).apply {
            tag = "glass:input"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            textSize = 22f
            setTextColor(Color.rgb(45, 52, 68))
            setText(currentLimit.toString())
            setPadding(dp(18), 0, dp(18), 0)
            background = roundedStrokeBackground(
                fillColor = Color.rgb(244, 247, 255),
                strokeColor = Color.rgb(225, 231, 243),
                radius = 16f
            )
        }
        container.addView(
            exactInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
            ).apply {
                topMargin = dp(4)
            }
        )

        container.addView(
            TextView(context).apply {
                text = "默认 ${formatNumber(ContextLimitStore.DEFAULT_TOKEN_LIMIT)}，" +
                    "范围 ${formatNumber(ContextLimitStore.MIN_TOKEN_LIMIT)} - " +
                    "${formatNumber(ContextLimitStore.MAX_TOKEN_LIMIT)}；≈ 表示供应商未返回 usage 时的估算值"
                textSize = 14f
                setTextColor(Color.rgb(150, 158, 176))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                leftMargin = dp(16)
            }
        )

        val savedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        savedRow.addView(
            TextView(context).apply {
                tag = "glass:circle"
                text = "✓"
                gravity = Gravity.CENTER
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(112, 124, 145))
                background = roundedStrokeBackground(
                    fillColor = Color.TRANSPARENT,
                    strokeColor = Color.rgb(112, 124, 145),
                    radius = 13f
                )
            },
            LinearLayout.LayoutParams(dp(27), dp(27))
        )
        savedRow.addView(
            TextView(context).apply {
                text = "已自动保存"
                textSize = 17f
                setTextColor(Color.rgb(112, 124, 145))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(12)
            }
        )
        container.addView(
            savedRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(22)
            }
        )

        fun refreshSheet(limit: Int) {
            val clamped = limit.coerceIn(
                ContextLimitStore.MIN_TOKEN_LIMIT,
                ContextLimitStore.MAX_TOKEN_LIMIT
            )
            currentLimit = clamped
            currentValue.text = formatNumber(contextTokens())
            consumedValue.text = formatConsumedTokens()
            targetValue.text = formatNumber(clamped)
            percentValue.text = formatPercent(contextTokens(), clamped)
            optionButtons.forEach { (option, button) ->
                val selected = option == clamped
                button.tag = if (selected) "glass:accent" else "glass:button"
                button.setTextColor(if (selected) Color.WHITE else Color.rgb(122, 133, 153))
                button.background = roundedStrokeBackground(
                    fillColor = if (selected) Color.rgb(58, 132, 226) else Color.rgb(245, 247, 252),
                    strokeColor = if (selected) Color.rgb(58, 132, 226) else Color.rgb(228, 233, 243),
                    radius = 24f
                )
            }
            onLimitChanged()
            LiquidGlass.refresh(scroll)
        }

        saveLimitAction = { limit, updateInput ->
            if (!syncing) {
                val clamped = limit.coerceIn(
                    ContextLimitStore.MIN_TOKEN_LIMIT,
                    ContextLimitStore.MAX_TOKEN_LIMIT
                )
                ContextLimitStore.setTokenLimit(context, clamped)
                syncing = true
                if (slider.value.roundToInt() != clamped) {
                    slider.value = clamped.toFloat()
                }
                if (updateInput && exactInput.text?.toString() != clamped.toString()) {
                    exactInput.setText(clamped.toString())
                    exactInput.setSelection(exactInput.text?.length ?: 0)
                }
                syncing = false
                refreshSheet(clamped)
            }
        }

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                saveLimitAction(value.roundToInt(), true)
            }
        }
        exactInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (syncing) return
                val parsed = s?.toString()?.toIntOrNull() ?: return
                saveLimitAction(parsed, false)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshSheet(currentLimit)

        return scroll
    }

    private fun createStatColumn(label: String, valueView: TextView): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(122, 133, 153))
                setSingleLine(true)
            })
            addView(valueView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            })
        }
    }

    private fun createStatValue(textValue: String, color: Int): TextView {
        return TextView(context).apply {
            text = textValue
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            includeFontPadding = false
            setSingleLine(true)
            gravity = Gravity.CENTER
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this,
                12,
                22,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    private fun statColumnParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun divider(): View {
        return View(context).apply {
            background = ColorDrawable(Color.rgb(232, 236, 244))
        }
    }

    private fun verticalDivider(): View {
        return View(context).apply {
            background = ColorDrawable(Color.rgb(232, 236, 244))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(58)).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        }
    }

    private fun roundedBackground(color: Int, radius: Float = 0f, topLeft: Float = radius, topRight: Float = radius): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(
                dp(topLeft).toFloat(), dp(topLeft).toFloat(),
                dp(topRight).toFloat(), dp(topRight).toFloat(),
                0f, 0f,
                0f, 0f
            )
        }
    }

    private fun roundedStrokeBackground(fillColor: Int, strokeColor: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setCornerRadius(dp(radius).toFloat())
            setStroke(dp(1), strokeColor)
        }
    }

    private fun formatNumber(value: Int): String {
        return String.format(Locale.US, "%,d", value)
    }

    private fun formatNumber(value: Long): String {
        return String.format(Locale.US, "%,d", value)
    }

    private fun formatConsumedTokens(): String {
        val prefix = if (usageIncludesEstimate()) "≈" else ""
        return prefix + formatNumber(consumedTokens())
    }

    private fun formatPercent(current: Int, limit: Int): String {
        if (limit <= 0) return "0%"
        return String.format(Locale.US, "%.1f%%", current * 100f / limit)
    }

    private fun formatCompactToken(value: Int): String {
        return if (value >= 1_000_000) {
            "${value / 1_000_000}M"
        } else {
            "${value / 1_000}k"
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun glassDialogSurface(radiusDp: Float): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(248, 255, 255, 255),
                Color.argb(238, 244, 249, 255)
            )
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1f), Color.argb(220, 255, 255, 255))
        }
    }
}
