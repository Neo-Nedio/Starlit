package com.hfad.starlit.view.modelpicker

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hfad.starlit.R
import com.hfad.starlit.data.database.provider.ProviderEntity
import com.hfad.starlit.data.repository.provider.ProviderRepository
import com.hfad.starlit.view.glass.GlassDialogStyler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal class ModelPickerPanel(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val providerRepository: ProviderRepository,
    private val windowYOffset: () -> Int
) {
    fun show(
        activeProviderId: Long?,
        activeModelName: String?,
        providerFilter: (ProviderEntity) -> Boolean,
        onSelected: (ProviderEntity, String) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val loadingView = buildModelPickerLoadingView()
        dialog.setContentView(loadingView)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        configureModelPickerWindow(dialog)

        lifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                loadModelPickerGroups(providerFilter)
            }
            if (!dialog.isShowing) return@launch
            dialog.setContentView(
                buildModelPickerContent(
                    dialog = dialog,
                    groups = groups,
                    activeProviderId = activeProviderId,
                    activeModelName = activeModelName,
                    onSelected = onSelected
                )
            )
            configureModelPickerWindow(dialog)
        }
    }

    private suspend fun loadModelPickerGroups(
        providerFilter: (ProviderEntity) -> Boolean
    ): List<ModelProviderGroup> {
        val providers = providerRepository.getAllProvidersOnce()
        return providers.filter(providerFilter).map { provider ->
            ModelProviderGroup(
                provider = provider,
                models = providerRepository.getModelsForProviderOnce(provider.providerId)
                    .map { it.modelName }
            )
        }
    }

    private fun configureModelPickerWindow(dialog: Dialog) {
        GlassDialogStyler.configureFloatingWindow(
            context = context,
            dialog = dialog,
            width = (context.resources.displayMetrics.widthPixels - GlassDialogStyler.dp(context, 32))
                .coerceAtLeast(GlassDialogStyler.dp(context, 300)),
            height = ViewGroup.LayoutParams.WRAP_CONTENT,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            y = windowYOffset(),
            blurRadiusDp = 12,
            dimAmount = 0.08f
        )
    }

    private fun buildModelPickerLoadingView(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GlassDialogStyler.surface(context, 26f)
            setPadding(
                GlassDialogStyler.dp(context, 24),
                GlassDialogStyler.dp(context, 34),
                GlassDialogStyler.dp(context, 24),
                GlassDialogStyler.dp(context, 34)
            )
            minimumHeight = modelPickerPanelHeight()
            addView(
                TextView(context).apply {
                    text = "正在加载模型..."
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(112, 124, 145))
                }
            )
        }
    }

    private fun buildModelPickerContent(
        dialog: Dialog,
        groups: List<ModelProviderGroup>,
        activeProviderId: Long?,
        activeModelName: String?,
        onSelected: (ProviderEntity, String) -> Unit
    ): View {
        val expandedProviderIds = groups.map { it.provider.providerId }.toMutableSet()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDialogStyler.surface(context, 26f)
            setPadding(
                GlassDialogStyler.dp(context, 16),
                GlassDialogStyler.dp(context, 16),
                GlassDialogStyler.dp(context, 16),
                GlassDialogStyler.dp(context, 14)
            )
        }

        root.addView(
            TextView(context).apply {
                text = "选择模型"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.mt_text_primary))
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = GlassDialogStyler.dp(context, 14)
                marginStart = GlassDialogStyler.dp(context, 4)
            }
        )

        val searchInput = EditText(context).apply {
            hint = "搜索模型 ID"
            textSize = 15f
            setSingleLine(true)
            setTextColor(Color.rgb(45, 52, 68))
            setHintTextColor(Color.rgb(160, 170, 188))
            background = GlassDialogStyler.roundedStrokeBackground(
                context,
                fillColor = Color.argb(235, 247, 250, 255),
                strokeColor = Color.argb(220, 216, 226, 240),
                radius = 16f
            )
            setPadding(GlassDialogStyler.dp(context, 14), 0, GlassDialogStyler.dp(context, 14), 0)
            compoundDrawablePadding = GlassDialogStyler.dp(context, 10)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search_outline, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(
            searchInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                GlassDialogStyler.dp(context, 54)
            )
        )

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                listContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                modelPickerListHeight()
            ).apply {
                topMargin = GlassDialogStyler.dp(context, 12)
            }
        )

        fun render(query: String) {
            listContainer.removeAllViews()
            val normalizedQuery = query.trim().lowercase(Locale.getDefault())
            val filteredGroups = groups.map { group ->
                if (normalizedQuery.isBlank()) {
                    group
                } else {
                    group.copy(
                        models = group.models.filter {
                            it.lowercase(Locale.getDefault()).contains(normalizedQuery)
                        }
                    )
                }
            }.filter { normalizedQuery.isBlank() || it.models.isNotEmpty() }

            if (filteredGroups.isEmpty()) {
                listContainer.addView(buildModelPickerEmptyView())
                return
            }

            filteredGroups.forEach { group ->
                val isExpanded = group.provider.providerId in expandedProviderIds
                listContainer.addView(
                    buildModelProviderHeader(group, isExpanded) {
                        val providerId = group.provider.providerId
                        if (providerId in expandedProviderIds) {
                            expandedProviderIds.remove(providerId)
                        } else {
                            expandedProviderIds.add(providerId)
                        }
                        render(searchInput.text?.toString().orEmpty())
                    }
                )

                if (isExpanded) {
                    if (group.models.isEmpty()) {
                        listContainer.addView(buildEmptyProviderModelsRow())
                    } else {
                        group.models.forEach { modelName ->
                            val selected = activeProviderId == group.provider.providerId &&
                                activeModelName == modelName
                            listContainer.addView(
                                buildModelPickerModelRow(
                                    modelName = modelName,
                                    selected = selected
                                ) {
                                    onSelected(group.provider, modelName)
                                    dialog.dismiss()
                                }
                            )
                        }
                    }
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render("")

        return root
    }

    private fun buildModelProviderHeader(
        group: ModelProviderGroup,
        expanded: Boolean,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                GlassDialogStyler.dp(context, 10),
                GlassDialogStyler.dp(context, 14),
                GlassDialogStyler.dp(context, 8),
                GlassDialogStyler.dp(context, 10)
            )
            foreground = ContextCompat.getDrawable(context, GlassDialogStyler.selectableItemBackgroundRes(context))
            setOnClickListener { onClick() }
        }

        row.addView(
            TextView(context).apply {
                text = group.provider.name
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(97, 113, 137))
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(
            TextView(context).apply {
                text = group.models.size.toString()
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(150, 162, 182))
                includeFontPadding = false
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(GlassDialogStyler.dp(context, 42), LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        row.addView(
            ImageView(context).apply {
                setImageResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)
                imageTintList = ColorStateList.valueOf(Color.rgb(150, 162, 182))
            },
            LinearLayout.LayoutParams(GlassDialogStyler.dp(context, 24), GlassDialogStyler.dp(context, 24))
        )

        return row
    }

    private fun buildModelPickerModelRow(
        modelName: String,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(GlassDialogStyler.dp(context, 18), 0, GlassDialogStyler.dp(context, 12), 0)
            foreground = ContextCompat.getDrawable(context, GlassDialogStyler.selectableItemBackgroundRes(context))
            background = if (selected) {
                GlassDialogStyler.roundedStrokeBackground(
                    context,
                    fillColor = Color.argb(110, 229, 241, 255),
                    strokeColor = Color.argb(150, 83, 146, 236),
                    radius = 14f
                )
            } else {
                ColorDrawable(Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
        }

        row.addView(
            TextView(context).apply {
                text = modelName
                textSize = 15f
                setTextColor(if (selected) Color.rgb(36, 101, 196) else Color.rgb(92, 108, 132))
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        if (selected) {
            row.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_check_blue)
                    imageTintList = ColorStateList.valueOf(Color.rgb(44, 131, 216))
                },
                LinearLayout.LayoutParams(GlassDialogStyler.dp(context, 22), GlassDialogStyler.dp(context, 22)).apply {
                    marginStart = GlassDialogStyler.dp(context, 10)
                }
            )
        }

        return FrameLayout(context).apply {
            setPadding(0, GlassDialogStyler.dp(context, 2), 0, GlassDialogStyler.dp(context, 2))
            addView(
                row,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    GlassDialogStyler.dp(context, 46)
                )
            )
        }
    }

    private fun modelPickerListHeight(): Int {
        return (context.resources.displayMetrics.heightPixels * 0.46f).toInt()
            .coerceAtLeast(GlassDialogStyler.dp(context, 260))
    }

    private fun modelPickerPanelHeight(): Int {
        return GlassDialogStyler.dp(context, 14) +
            GlassDialogStyler.dp(context, 54) +
            GlassDialogStyler.dp(context, 12) +
            modelPickerListHeight() +
            GlassDialogStyler.dp(context, 12)
    }

    private fun buildEmptyProviderModelsRow(): View {
        return TextView(context).apply {
            text = "该 Provider 暂无可选模型"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(160, 170, 188))
            setPadding(0, GlassDialogStyler.dp(context, 12), 0, GlassDialogStyler.dp(context, 18))
        }
    }

    private fun buildModelPickerEmptyView(): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                GlassDialogStyler.dp(context, 12),
                GlassDialogStyler.dp(context, 42),
                GlassDialogStyler.dp(context, 12),
                GlassDialogStyler.dp(context, 42)
            )
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_package_empty)
                    alpha = 0.55f
                },
                LinearLayout.LayoutParams(GlassDialogStyler.dp(context, 54), GlassDialogStyler.dp(context, 54))
            )
            addView(
                TextView(context).apply {
                    text = "没有找到可选模型"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(150, 162, 182))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = GlassDialogStyler.dp(context, 12)
                }
            )
        }
    }


    private data class ModelProviderGroup(
        val provider: ProviderEntity,
        val models: List<String>
    )
}
