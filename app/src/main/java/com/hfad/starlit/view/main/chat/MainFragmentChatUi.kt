package com.hfad.starlit.view.main.chat

import android.app.Dialog
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.hfad.starlit.R
import com.hfad.starlit.adapter.ChatAdapter
import com.hfad.starlit.data.model.chat.ChatMessage
import com.hfad.starlit.data.model.chat.GenerateTaskState
import com.hfad.starlit.data.database.provider.ProviderEntity
import com.hfad.starlit.data.preferences.appearance.AppearanceSettingsStore
import com.hfad.starlit.data.preferences.provider.ActiveModelStore
import com.hfad.starlit.data.preferences.chat.ContextLimitStore
import com.hfad.starlit.databinding.DialogGenerateCodeEditorBinding
import com.hfad.starlit.workspace.AgentWorkspace
import com.hfad.starlit.chat.context.ContextTokenCounter
import com.hfad.starlit.view.glass.LiquidGlass
import com.hfad.starlit.view.chat.ContextLimitSheet
import com.hfad.starlit.view.modelpicker.ModelPickerPanel
import com.hfad.starlit.view.webapp.VirtualAppActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import com.hfad.starlit.view.main.MainFragment
import com.hfad.starlit.view.main.drawer.*

internal fun MainFragment.renderChatMessages(messages: List<ChatMessage>) {
        updateGreetingVisibility(
            messages.isNotEmpty() || currentGenerateTaskState?.harnessEvents?.isNotEmpty() == true
        )
        if (forceScrollToLatestMessage) {
            pendingMessagesWhileScrolling = null
        }
        if (isChatScrollActive && !forceScrollToLatestMessage) {
            pendingMessagesWhileScrolling = messages
            updateCurrentContextTokens(messages)
            return
        }

        val followNewMessages = forceScrollToLatestMessage || shouldFollowNewMessages()
        forceScrollToLatestMessage = false
        val anchor = if (followNewMessages) null else captureChatViewportAnchor()
        val displayMessages = buildDisplayMessages(messages)
        chatAdapter.submitList(displayMessages) {
            if (isChatScrollActive) {
                pendingMessagesWhileScrolling = messages
                return@submitList
            }
            when {
                messages.isNotEmpty() && followNewMessages -> scrollChatToBottom()
                anchor != null -> restoreChatViewportAnchor(anchor)
            }
        }
        updateCurrentContextTokens(messages)
    }

internal fun MainFragment.buildDisplayMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val taskState = currentGenerateTaskState ?: return messages
        if (taskState.sessionId != viewModel.currentSessionId.value) return messages
        if (taskState.harnessEvents.isEmpty()) return messages

        val placeholderId = -taskState.sessionId.coerceAtLeast(1L)
        val visibleMessages = messages.filterNot { message ->
            message.messageId == placeholderId && message.isStreaming && message.content.isBlank()
        }.toMutableList()
        val harnessState = taskState.copy(
            phase = when (taskState.phase) {
                GenerateTaskState.Phase.COMPLETED,
                GenerateTaskState.Phase.ERROR -> taskState.phase
                else -> GenerateTaskState.Phase.REQUESTING_MODEL
            },
            code = "",
            filePath = null,
            status = if (taskState.phase == GenerateTaskState.Phase.ERROR) taskState.status else "",
            diagnostics = emptyList(),
            updatedAt = taskState.harnessEvents.last().timestamp
        )
        val harnessMessage = ChatMessage(
            messageId = harnessMessageId(taskState.sessionId),
            role = ChatMessage.ROLE_HARNESS,
            content = "",
            timestamp = harnessState.updatedAt,
            isStreaming = harnessState.isRunning,
            generateTaskState = harnessState
        )
        val taskFilePath = taskState.filePath?.takeIf(String::isNotBlank)
        val previewIndex = visibleMessages.lastIndex.takeIf { index ->
            index >= 0 && taskFilePath != null &&
                visibleMessages[index].appHtmlPath == taskFilePath
        } ?: -1
        if (previewIndex >= 0) {
            visibleMessages.add(previewIndex, harnessMessage)
        } else {
            visibleMessages.add(harnessMessage)
        }
        return visibleMessages
    }

internal fun MainFragment.harnessMessageId(sessionId: Long): Long {
        return Long.MIN_VALUE + sessionId.coerceAtLeast(1L)
    }

internal fun MainFragment.captureChatViewportAnchor(): ChatViewportAnchor? {
        val layoutManager = chatBinding.rvChat.layoutManager as? LinearLayoutManager ?: return null
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val view = layoutManager.findViewByPosition(position) ?: return null
        val messageId = chatAdapter.currentList.getOrNull(position)?.messageId
        return ChatViewportAnchor(messageId, position, view.top - chatBinding.rvChat.paddingTop)
    }

internal fun MainFragment.restoreChatViewportAnchor(anchor: ChatViewportAnchor) {
        val layoutManager = chatBinding.rvChat.layoutManager as? LinearLayoutManager ?: return
        val lastPosition = (chatAdapter.itemCount - 1).coerceAtLeast(0)
        val anchoredPosition = anchor.messageId
            ?.let { messageId -> chatAdapter.currentList.indexOfFirst { it.messageId == messageId } }
            ?.takeIf { it != -1 }
            ?: anchor.position
        layoutManager.scrollToPositionWithOffset(
            anchoredPosition.coerceIn(0, lastPosition),
            anchor.topOffset
        )
    }

internal fun MainFragment.warmUpContextPromptCache() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val prompt = withContext(Dispatchers.IO) {
                AgentWorkspace.buildSystemPrompt(appContext)
            }
            cachedChatSystemPrompt = prompt
            updateCurrentContextTokens()
        }
    }

internal fun MainFragment.setupChatRecyclerView() {
        chatAdapter = ChatAdapter(
            onDataChanged = { itemCount ->
                updateGreetingVisibility(itemCount > 0)
            },
            onFullscreenClick = { htmlPath ->
                val intent = Intent(requireContext(), VirtualAppActivity::class.java).apply {
                    putExtra(VirtualAppActivity.EXTRA_HTML_PATH, htmlPath)
                }
                startActivity(intent)
            },
            onMessageLongClick = { message ->
                showMessageActions(message)
            },
            onActiveWebViewChanged = { webView ->
                activeAppWebView = webView
            },
            onWebViewReleased = { webView ->
                if (activeAppWebView === webView) {
                    activeAppWebView = null
                }
            }
        )

        chatBinding.rvChat.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    isChatScrollActive = newState != RecyclerView.SCROLL_STATE_IDLE
                    if (!isChatScrollActive) {
                        pendingMessagesWhileScrolling?.let { pendingMessages ->
                            pendingMessagesWhileScrolling = null
                            renderChatMessages(pendingMessages)
                        }
                    }
                }
            })
            // 设置 item 动画（可选）
            itemAnimator = null  // 禁用动画以避免闪烁，或使用自定义动画
        }
        chatBinding.inputContainer.addOnLayoutChangeListener {
            _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val shouldKeepLatestMessageVisible = shouldFollowNewMessages()
            updateChatListScrollBoundary()
            if (shouldKeepLatestMessageVisible) scrollChatToBottom()
        }
        chatAdapter.updateAppearance(AppearanceSettingsStore.getSettings(requireContext()))
    }

internal fun MainFragment.updateGreetingVisibility(hasMessages: Boolean) {
        val targetVisibility = if (hasMessages) View.GONE else View.VISIBLE
        if (chatBinding.flGreeting.visibility == targetVisibility) return
        chatBinding.flGreeting.visibility = targetVisibility
        LiquidGlass.refresh(binding.root)
    }

internal fun MainFragment.renderGenerateTaskState(taskState: GenerateTaskState?) {
        val wasCodePreviewVisible = chatBinding.generateCodeCard.visibility == View.VISIBLE
        val hasCodePreview = taskState?.code?.isNotBlank() == true
        val visibilityChanged = wasCodePreviewVisible != hasCodePreview
        val shouldKeepLatestMessageVisible = visibilityChanged &&
            hasChatAdapter && shouldFollowNewMessages()
        currentGenerateTaskState = taskState
        if (!hasCodePreview) {
            chatBinding.generateCodeCard.visibility = View.GONE
            if (codeEditorSource == CodeEditorSource.GENERATION) {
                codeEditorDialog?.dismiss()
            }
            if (visibilityChanged) updateChatListScrollBoundary()
            if (shouldKeepLatestMessageVisible) scrollChatToBottom()
            return
        }

        chatBinding.generateCodeCard.visibility = View.VISIBLE
        val language = codePreviewLanguage(taskState)
        val metadata = codePreviewMetadata(taskState)
        chatBinding.tvGenerateCodeLanguage.text = language
        chatBinding.tvGenerateCodeTitle.text = if (taskState.projectFiles.size > 1) {
            "项目源码 · ${taskState.projectFiles.size} 文件"
        } else {
            "源码预览"
        }
        chatBinding.tvGenerateCodeStatus.text = metadata
        chatBinding.tvGenerateCodePreview.text = buildGenerateCodePreview(taskState)
        chatBinding.generateCodeCard.contentDescription = "打开源码预览，$language，$metadata"
        updateGenerateCodeEditor(taskState)
        if (visibilityChanged) updateChatListScrollBoundary()
        if (shouldKeepLatestMessageVisible) scrollChatToBottom()
    }

internal fun MainFragment.codePreviewLanguage(taskState: GenerateTaskState): String {
        val code = taskState.code
        val looksLikeDiff = code.lineSequence().take(8).any { line ->
            line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("@@ ")
        }
        if (looksLikeDiff) return "DIFF"
        return taskState.activeFilePath
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
            ?.uppercase(Locale.ROOT)
            ?: taskState.filePath
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
            ?.uppercase(Locale.ROOT)
            ?: "HTML"
    }

internal fun MainFragment.codePreviewLanguage(fileName: String): String {
        return fileName.substringAfterLast('.', missingDelimiterValue = "")
            .takeIf(String::isNotBlank)
            ?.uppercase(Locale.ROOT)
            ?: "TEXT"
    }

internal fun MainFragment.codePreviewMetadata(taskState: GenerateTaskState): String {
        return codePreviewMetadata(taskState.activeFilePath, taskState.code)
    }

internal fun MainFragment.codePreviewMetadata(label: String?, code: String): String {
        val lineCount = if (code.isEmpty()) 0 else code.count { it == '\n' } + 1
        val metrics = "$lineCount 行 · ${code.length} 字符"
        return label?.takeIf(String::isNotBlank)?.let { "$it · $metrics" } ?: metrics
    }

internal fun MainFragment.buildGenerateCodePreview(taskState: GenerateTaskState): String {
        if (taskState.code.isBlank()) {
            return "\$ ${taskState.languageLabel.lowercase(Locale.ROOT)} -- waiting"
        }
        return taskState.code
            .lineSequence()
            .filter { it.isNotBlank() }
            .takeLastLines(3)
            .joinToString("\n")
    }

private fun Sequence<String>.takeLastLines(count: Int): List<String> {
        val lines = ArrayDeque<String>(count)
        forEach { line ->
            if (lines.size == count) lines.removeFirst()
            lines.addLast(line)
        }
        return lines.toList()
    }

internal fun MainFragment.showGenerateCodeEditor() {
        val taskState = currentGenerateTaskState ?: return
        if (codeEditorDialog?.isShowing == true && codeEditorSource == CodeEditorSource.GENERATION) {
            updateGenerateCodeEditor(taskState)
            return
        }

        val title = taskState.activeFilePath
            ?.takeIf(String::isNotBlank)
            ?: taskState.filePath
                ?.let(::File)
                ?.name
                ?.takeIf(String::isNotBlank)
            ?: if (taskState.isModification) "应用修改.diff" else "生成应用.html"
        showCodeViewer(
            source = CodeEditorSource.GENERATION,
            title = title,
            status = codePreviewMetadata(taskState),
            language = codePreviewLanguage(taskState),
            content = taskState.code
        )
    }

internal fun MainFragment.showCodeViewer(
        source: CodeEditorSource,
        title: String,
        status: String,
        language: String,
        content: String
    ) {
        codeEditorDialog?.dismiss()
        val editorBinding = DialogGenerateCodeEditorBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext(), R.style.Theme_Starlit_CodeViewer)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(editorBinding.root)
        editorBinding.btnCloseCodeEditor.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            if (codeEditorDialog === dialog) {
                codeEditorBinding = null
                codeEditorDialog = null
                codeEditorSource = null
            }
        }
        codeEditorBinding = editorBinding
        codeEditorDialog = dialog
        codeEditorSource = source
        editorBinding.tvCodeEditorLines.bindTo(editorBinding.tvCodeEditorContent)
        bindCodeViewer(editorBinding, title, status, language, content)
        dialog.show()
        configureCodeViewerWindow(dialog, editorBinding)
    }

internal fun MainFragment.updateGenerateCodeEditor(taskState: GenerateTaskState) {
        if (codeEditorSource != CodeEditorSource.GENERATION) return
        val editorBinding = codeEditorBinding ?: return
        if (codeEditorDialog?.isShowing != true) return

        val title = taskState.activeFilePath
            ?.takeIf(String::isNotBlank)
            ?: taskState.filePath
            ?.let(::File)
            ?.name
            ?.takeIf(String::isNotBlank)
            ?: if (taskState.isModification) "应用修改.diff" else "生成应用.html"
        bindCodeViewer(
            binding = editorBinding,
            title = title,
            status = codePreviewMetadata(taskState),
            language = codePreviewLanguage(taskState),
            content = taskState.code
        )
    }

internal fun MainFragment.bindCodeViewer(
        binding: DialogGenerateCodeEditorBinding,
        title: String,
        status: String,
        language: String,
        content: String
    ) {
        binding.tvCodeEditorTitle.text = title
        binding.tvCodeEditorStatus.text = status
        binding.tvCodeEditorLanguage.text = language
        binding.tvCodeEditorContent.text = content
        binding.tvCodeEditorLines.refreshLineNumbers()
    }

internal fun MainFragment.configureCodeViewerWindow(
        dialog: Dialog,
        binding: DialogGenerateCodeEditorBinding
    ) {
        val window = dialog.window ?: return
        val surfaceColor = ContextCompat.getColor(requireContext(), R.color.mt_generate_surface)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setBackgroundDrawable(ColorDrawable(surfaceColor))
        window.statusBarColor = surfaceColor
        window.navigationBarColor = surfaceColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                safeInsets.left,
                safeInsets.top,
                safeInsets.right,
                safeInsets.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

internal fun MainFragment.openImagePicker() {
        imagePickerLauncher.launch(arrayOf("image/*"))
    }

internal fun MainFragment.fillInputWithAppPrompt() {
        val prompt = "生成一个应用："
        chatBinding.etInput.setText(prompt)
        chatBinding.etInput.setSelection(prompt.length)
        switchToActiveState()
    }

internal fun MainFragment.fillInputWithMemoryPrompt() {
        val prompt = "请记住："
        chatBinding.etInput.setText(prompt)
        chatBinding.etInput.setSelection(prompt.length)
        switchToActiveState()
    }

internal fun MainFragment.onImagesSelectedFromPicker(uris: List<Uri>) {
        if (uris.isEmpty()) return

        uris.forEach { uri ->
            persistReadPermission(uri)
            if (selectedImageUris.none { it == uri }) {
                selectedImageUris.add(uri)
            }
        }
        renderSelectedImageChips()
        updateCurrentContextTokens()
    }

internal fun MainFragment.persistReadPermission(uri: Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

internal fun MainFragment.renderSelectedImageChips() {
        chatBinding.selectedImageChipGroup.removeAllViews()
        chatBinding.selectedImageChipScroll.visibility =
            if (selectedImageUris.isEmpty()) View.GONE else View.VISIBLE

        selectedImageUris.forEach { uri ->
            val chip = Chip(requireContext()).apply {
                tag = "glass:capsule"
                text = queryDisplayName(uri)
                isCloseIconVisible = true
                isClickable = true
                isCheckable = false
                setTextColor(adaptiveTextColor)
                textSize = 13f
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.argb(120, 255, 255, 255))
                chipStrokeWidth = dp(1).toFloat()
                closeIconTint = android.content.res.ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(adaptiveTextColor, 184)
                )
                setOnCloseIconClickListener {
                    selectedImageUris.remove(uri)
                    renderSelectedImageChips()
                    updateCurrentContextTokens()
                }
            }
            chatBinding.selectedImageChipGroup.addView(chip)
        }
        LiquidGlass.refresh(binding.root)
    }

internal fun MainFragment.clearSelectedImages() {
        selectedImageUris.clear()
        renderSelectedImageChips()
        updateCurrentContextTokens()
    }

internal fun MainFragment.showModelPickerPanel() {
        showModelPickerPanel(
            activeProviderId = ActiveModelStore.getActiveProviderId(requireContext()),
            activeModelName = ActiveModelStore.getActiveModelName(requireContext()),
            providerFilter = { true },
            onSelected = { provider, modelName ->
                ActiveModelStore.setActive(requireContext(), provider.providerId, modelName)
                viewModel.refreshActiveModel()
                Toast.makeText(requireContext(), "已切换到 $modelName", Toast.LENGTH_SHORT).show()
            }
        )
    }

internal fun MainFragment.showModelPickerPanel(
        activeProviderId: Long?,
        activeModelName: String?,
        providerFilter: (ProviderEntity) -> Boolean,
        onSelected: (ProviderEntity, String) -> Unit
    ) {
        ModelPickerPanel(
            context = requireContext(),
            lifecycleOwner = viewLifecycleOwner,
            providerRepository = providerRepository,
            windowYOffset = { chatBinding.inputContainer.height + dp(10) }
        ).show(activeProviderId, activeModelName, providerFilter, onSelected)
    }

internal fun MainFragment.showContextLimitDialog() {
        ContextLimitSheet(
            context = requireContext(),
            activity = requireActivity(),
            contextTokens = { currentContextTokens },
            consumedTokens = { currentConsumedTokens },
            usageIncludesEstimate = { currentTokenUsageIncludesEstimate },
            onLimitChanged = this::updateContextProgressIndicator
        ).show()
    }

internal fun MainFragment.updateCurrentContextTokens(messages: List<ChatMessage>? = null) {
        val sourceMessages = messages ?: viewModel.messages.value.orEmpty()
        currentContextTokens = ContextTokenCounter.estimateChatMessages(
            messages = sourceMessages,
            draftText = chatBinding.etInput.text?.toString().orEmpty(),
            selectedImageCount = selectedImageUris.size,
            systemPrompt = cachedChatSystemPrompt
        )
        updateContextProgressIndicator()
    }

internal fun MainFragment.updateContextProgressIndicator() {
        val limit = ContextLimitStore.getTokenLimit(requireContext())
        val fraction = if (limit <= 0) 0f else currentContextTokens.toFloat() / limit.toFloat()
        chatBinding.ivContextLimit.setProgressFraction(fraction)
        chatBinding.ivContextLimit.contentDescription =
            "上下文限制，预计占用 ${formatNumber(currentContextTokens)} / ${formatNumber(limit)}，" +
                "本会话累计消耗 ${formatConsumedTokens()} token"
    }

internal fun MainFragment.formatNumber(value: Int): String {
        return String.format(Locale.US, "%,d", value)
    }

internal fun MainFragment.formatNumber(value: Long): String {
        return String.format(Locale.US, "%,d", value)
    }

internal fun MainFragment.formatConsumedTokens(): String {
        val prefix = if (currentTokenUsageIncludesEstimate) "≈" else ""
        return prefix + formatNumber(currentConsumedTokens)
    }

internal fun MainFragment.formatPercent(current: Int, limit: Int): String {
        if (limit <= 0) return "0%"
        return String.format(Locale.US, "%.1f%%", current * 100f / limit)
    }

internal fun MainFragment.queryDisplayName(uri: Uri): String {
        return runCatching {
            requireContext().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        null
                    }
                }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "已选择图片"
    }

internal fun MainFragment.switchToActiveState() {
        isInputActive = true
        if (!chatBinding.etInput.hasFocus()) {
            chatBinding.etInput.requestFocus()
        }
        showKeyboard()
    }

internal fun MainFragment.enterInputActiveState() {
        isInputActive = true
    }

internal fun MainFragment.switchToIdleState() {
        if (!isInputActive) return
        isInputActive = false
        hideKeyboard()

        chatBinding.etInput.clearFocus()
    }

internal fun MainFragment.showKeyboard() {
        chatBinding.etInput.let {
            val imm = ContextCompat.getSystemService(
                requireContext(),
                InputMethodManager::class.java
            )
            imm?.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
        }
    }

internal fun MainFragment.hideKeyboard() {
        chatBinding.etInput.let {
            val imm = ContextCompat.getSystemService(
                requireContext(),
                InputMethodManager::class.java
            )
            imm?.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

internal fun MainFragment.updateSendButtonState(taskRunning: Boolean) {
        isTaskRunning = taskRunning
        val backgroundColor = Color.rgb(44, 131, 216)
        val iconRes = if (taskRunning) R.drawable.ic_stop_square else R.drawable.ic_send_up

        chatBinding.ivSend.isEnabled = true
        chatBinding.ivSend.isClickable = true
        chatBinding.ivSend.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        chatBinding.ivSend.setImageResource(iconRes)
        chatBinding.ivSend.imageTintList = ColorStateList.valueOf(Color.WHITE)
        chatBinding.ivSend.contentDescription = if (taskRunning) "停止请求" else "发送"
    }

internal fun MainFragment.sendMessage() {
        if (isTaskRunning) {
            viewModel.stopStreaming()
            return
        }
        val text = chatBinding.etInput.text?.toString()?.trim() ?: ""
        val imageUris = selectedImageUris.toList()
        
        // 检查是否有内容
        if (text.isEmpty() && imageUris.isEmpty()) {
            Toast.makeText(requireContext(), "请输入消息内容", Toast.LENGTH_SHORT).show()
            return
        }
        updateSendButtonState(true)
        forceScrollToLatestMessage = true

        val firstImagePath = imageUris.firstOrNull()?.toString()

        // 清空输入框和图片选择
        chatBinding.etInput.text?.clear()
        clearSelectedImages()
        hideKeyboard()

        // 通过 ViewModel 发送消息（支持多图）
        viewModel.sendMessage(
            content = text.ifEmpty { if (imageUris.isNotEmpty()) "请帮我分析这张图片" else "" },
            imagePath = firstImagePath,
            imageUris = imageUris
        )
    }

internal data class ChatViewportAnchor(
    val messageId: Long?,
    val position: Int,
    val topOffset: Int
)

internal enum class CodeEditorSource {
    GENERATION,
    WORKSPACE_FILE
}
