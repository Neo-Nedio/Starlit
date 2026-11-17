package com.hfad.starlit.view.main.drawer

import android.app.Dialog
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hfad.starlit.R
import com.hfad.starlit.adapter.SessionAdapter
import com.hfad.starlit.data.model.chat.ChatMessage
import com.hfad.starlit.data.database.chat.ChatSessionEntity
import com.hfad.starlit.view.glass.LiquidGlass
import com.hfad.starlit.view.settings.SettingsActivity
import com.hfad.starlit.view.main.MainFragment
import com.hfad.starlit.view.main.MainActivity
import com.hfad.starlit.view.main.chat.*

internal fun MainFragment.setupDrawerMenu() {
        val drawerMenu = (activity as? MainActivity)?.findViewById<View>(com.hfad.starlit.R.id.drawerMenu)
        drawerMenu?.findViewById<View>(com.hfad.starlit.R.id.tvClearHistory)?.setOnClickListener {
            showClearHistoryDialog()
        }
        drawerMenu?.findViewById<View>(com.hfad.starlit.R.id.btn_setting)?.setOnClickListener {
            (activity as? MainActivity)?.closeDrawer()
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        drawerMenu?.findViewById<ImageButton>(com.hfad.starlit.R.id.btnArchiveToggle)?.setOnClickListener {
            showArchivedSessions = !showArchivedSessions
            refreshDrawerSessionList()
        }
        drawerMenu?.findViewById<EditText>(com.hfad.starlit.R.id.etSessionSearch)
            ?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    drawerSearchQuery = s?.toString().orEmpty().trim()
                    refreshDrawerSessionList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
    }

internal fun MainFragment.showClearHistoryDialog() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("清空所有会话")
            .setMessage("确定要删除所有历史会话吗？此操作不可恢复。")
            .setPositiveButton("确定") { _, _ ->
                viewModel.deleteAllSessions()
                Toast.makeText(requireContext(), "已清空所有会话", Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.closeDrawer()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        decorateAlertDialog(dialog)
    }

internal fun MainFragment.createNewSession() {
        // 通过 ViewModel 创建空会话
        viewModel.createEmptySession()
        
        // 清空输入框
        chatBinding.etInput.text?.clear()
        
        clearSelectedImages()
        
        Toast.makeText(requireContext(), "已创建新会话", Toast.LENGTH_SHORT).show()
    }

internal fun MainFragment.setupSessionRecyclerView() {
        sessionAdapter = SessionAdapter(
            onSessionClick = { session ->
                // 点击会话：切换到该会话
                viewModel.switchToSession(session.sessionId)
                (activity as? MainActivity)?.closeDrawer()
                Toast.makeText(requireContext(), "已切换到: ${session.title}", Toast.LENGTH_SHORT).show()
            },
            onSessionLongClick = { session ->
                showSessionActions(session)
            }
        )

        val drawerMenu = (activity as? MainActivity)?.findViewById<View>(com.hfad.starlit.R.id.drawerMenu)
        val rvSessions = drawerMenu?.findViewById<androidx.recyclerview.widget.RecyclerView>(com.hfad.starlit.R.id.rvSessions)
        
        rvSessions?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = sessionAdapter
            itemAnimator = null
        }
        refreshDrawerSessionList()
    }

internal fun MainFragment.refreshDrawerSessionList() {
        if (!hasSessionAdapter) return

        val drawerMenu = (activity as? MainActivity)?.findViewById<View>(com.hfad.starlit.R.id.drawerMenu)
        val titleView = drawerMenu?.findViewById<TextView>(com.hfad.starlit.R.id.tvSessionSectionTitle)
        val emptyView = drawerMenu?.findViewById<TextView>(com.hfad.starlit.R.id.tvEmptySessions)
        val archiveButton = drawerMenu?.findViewById<ImageButton>(com.hfad.starlit.R.id.btnArchiveToggle)

        val source = if (drawerSearchQuery.isNotBlank()) {
            (activeSessions + archivedSessions).distinctBy { it.sessionId }
        } else if (showArchivedSessions) {
            archivedSessions
        } else {
            activeSessions
        }
        val visibleSessions = if (drawerSearchQuery.isBlank()) {
            source
        } else {
            source.filter { it.title.contains(drawerSearchQuery, ignoreCase = true) }
        }

        titleView?.text = when {
            drawerSearchQuery.isNotBlank() -> "搜索结果"
            showArchivedSessions -> "归档对话"
            else -> "最近对话"
        }
        emptyView?.text = when {
            drawerSearchQuery.isNotBlank() -> "没有匹配的会话"
            showArchivedSessions -> "暂无归档会话"
            else -> "暂无会话"
        }
        emptyView?.visibility = if (visibleSessions.isEmpty()) View.VISIBLE else View.GONE

        val accentColor = if (showArchivedSessions) {
            ContextCompat.getColor(requireContext(), R.color.mt_primary)
        } else {
            ContextCompat.getColor(requireContext(), R.color.mt_text_primary)
        }
        archiveButton?.imageTintList = ColorStateList.valueOf(accentColor)
        archiveButton?.contentDescription = if (showArchivedSessions) "返回最近对话" else "查看归档"

        sessionAdapter.setRunningSessionIds(runningSessionIds)
        val shouldRefreshGlass = sessionAdapter.currentList != visibleSessions
        sessionAdapter.submitList(visibleSessions) {
            if (!shouldRefreshGlass) return@submitList
            val drawerRoot = drawerMenu ?: return@submitList
            val sessionsView = drawerRoot.findViewById<RecyclerView>(R.id.rvSessions)
            sessionsView.doOnNextLayout {
                LiquidGlass.refresh(drawerRoot)
            }
        }
    }

internal fun MainFragment.showSessionActions(session: ChatSessionEntity) {
        val archiveLabel = if (session.isArchived) "取消归档" else "归档"
        showActionMenu(
            listOf(
                ActionMenuItem(archiveLabel, R.drawable.ic_archive_outline) {
                    val nextArchived = !session.isArchived
                    viewModel.setSessionArchived(session.sessionId, nextArchived)
                    Toast.makeText(
                        requireContext(),
                        if (nextArchived) "已归档会话" else "已取消归档",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                ActionMenuItem("删除", R.drawable.ic_delete_outline) {
                    showDeleteSessionDialog(session)
                }
            )
        )
    }

internal fun MainFragment.showDeleteSessionDialog(session: ChatSessionEntity) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("删除会话")
            .setMessage("确定要删除会话「${session.title}」吗？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteSession(session.sessionId)
                Toast.makeText(requireContext(), "已删除会话", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        decorateAlertDialog(dialog)
    }

internal fun MainFragment.showMessageActions(message: ChatMessage) {
        val items = mutableListOf<ActionMenuItem>()

        if (message.role != ChatMessage.ROLE_USER) {
            items += ActionMenuItem("选字复制", R.drawable.ic_select_text) {
                showSelectableMessageText(message)
            }
        }

        items += ActionMenuItem("删除", R.drawable.ic_delete_outline) {
            confirmDeleteMessage(message)
        }

        if (message.role == ChatMessage.ROLE_USER) {
            items += ActionMenuItem("修改", R.drawable.ic_edit_outline) {
                showEditMessageDialog(message)
            }
        }

        showActionMenu(items)
    }

internal fun MainFragment.showSelectableMessageText(message: ChatMessage) {
        val text = message.content
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "没有可复制的文本", Toast.LENGTH_SHORT).show()
            return
        }

        val textView = TextView(requireContext()).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 15f
            setTextColor(Color.rgb(31, 41, 55))
            setLineSpacing(2f * resources.displayMetrics.density, 1f)
        }
        val paddingHorizontal = (18 * resources.displayMetrics.density).toInt()
        val paddingVertical = (10 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(requireContext()).apply {
            tag = "glass:panel"
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, 0)
            addView(
                textView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("选字复制")
            .setView(container)
            .setPositiveButton("关闭", null)
            .create()
        dialog.show()
        decorateAlertDialog(dialog)
    }

internal fun MainFragment.confirmDeleteMessage(message: ChatMessage) {
        if (message.messageId <= 0) {
            Toast.makeText(requireContext(), "该消息无法删除", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("删除消息")
            .setMessage("确定要删除这条消息吗？")
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteMessage(message.messageId)
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        decorateAlertDialog(dialog)
    }

internal fun MainFragment.showEditMessageDialog(message: ChatMessage) {
        if (message.messageId <= 0) {
            Toast.makeText(requireContext(), "该消息无法修改", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.role != ChatMessage.ROLE_USER) {
            Toast.makeText(requireContext(), "只能修改用户请求", Toast.LENGTH_SHORT).show()
            return
        }

        val editor = EditText(requireContext()).apply {
            tag = "glass:input"
            setText(message.content)
            setSelection(text?.length ?: 0)
            minLines = 4
            maxLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            textSize = 15f
        }

        val paddingHorizontal = (16 * resources.displayMetrics.density).toInt()
        val paddingTop = (8 * resources.displayMetrics.density).toInt()
        val editorContainer = FrameLayout(requireContext()).apply {
            setPadding(paddingHorizontal, paddingTop, paddingHorizontal, 0)
            addView(
                editor,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("修改消息")
            .setView(editorContainer)
            .setNegativeButton("取消", null)
            .setPositiveButton("重新发送", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val newContent = editor.text.toString().trim()
                    if (newContent.isBlank()) {
                        Toast.makeText(requireContext(), "消息不能为空", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    viewModel.editUserMessageAndRegenerate(message.messageId, newContent)
                    Toast.makeText(requireContext(), "已重新发送", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
        }
        dialog.show()
        decorateAlertDialog(dialog)
    }

internal fun MainFragment.showActionMenu(items: List<ActionMenuItem>) {
        if (items.isEmpty()) return

        val hostActivity = activity?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        actionMenuDialog?.dismiss()

        val dialog = Dialog(hostActivity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        val density = resources.displayMetrics.density
        val menu = LinearLayout(hostActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(hostActivity, R.drawable.bg_action_menu)
        }

        items.forEachIndexed { index, item ->
            val row = LinearLayout(hostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                minimumHeight = dp(72)
                foreground = ContextCompat.getDrawable(hostActivity, selectableItemBackgroundRes())
                setOnClickListener {
                    dialog.dismiss()
                    item.onClick()
                }
            }

            val icon = ImageView(hostActivity).apply {
                setImageResource(item.iconRes)
                colorFilter = android.graphics.PorterDuffColorFilter(
                    Color.rgb(17, 24, 39),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            }
            row.addView(
                icon,
                LinearLayout.LayoutParams(dp(30), dp(30))
            )

            val label = TextView(hostActivity).apply {
                text = item.label
                textSize = 22f
                setTextColor(Color.rgb(17, 24, 39))
                includeFontPadding = false
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = dp(24)
                }
            )

            menu.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(72)
                )
            )

            if (index != items.lastIndex) {
                menu.addView(
                    View(hostActivity).apply {
                        background = ContextCompat.getDrawable(hostActivity, R.drawable.bg_action_menu_divider)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt().coerceAtLeast(1)
                    )
                )
            }
        }

        dialog.setContentView(menu)
        dialog.setOnDismissListener {
            if (actionMenuDialog === dialog) {
                actionMenuDialog = null
            }
        }
        actionMenuDialog = dialog
        dialog.show()
        configureFloatingGlassWindow(
            dialog = dialog,
            width = (resources.displayMetrics.widthPixels * 0.72f).toInt(),
            height = ViewGroup.LayoutParams.WRAP_CONTENT,
            blurRadiusDp = 14,
            dimAmount = 0.16f
        )
    }

internal data class ActionMenuItem(
    val label: String,
    val iconRes: Int,
    val onClick: () -> Unit
)
