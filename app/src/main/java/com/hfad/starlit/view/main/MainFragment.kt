package com.hfad.starlit.view.main
import com.hfad.starlit.view.main.chat.*
import com.hfad.starlit.view.main.desktop.*
import com.hfad.starlit.view.main.workspace.*
import com.hfad.starlit.view.main.drawer.*
import com.hfad.starlit.view.main.appearance.*
import com.hfad.starlit.view.main.voice.*
import com.hfad.starlit.view.main.chat.CodeEditorSource

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.hfad.starlit.R
import com.hfad.starlit.adapter.ChatAdapter
import com.hfad.starlit.adapter.DesktopAppAdapter
import com.hfad.starlit.adapter.SessionAdapter
import com.hfad.starlit.adapter.WorkspaceFileAdapter
import com.hfad.starlit.data.model.chat.ChatMessage
import com.hfad.starlit.data.model.chat.GenerateTaskState
import com.hfad.starlit.data.api.voice.VoiceInputConfig
import com.hfad.starlit.data.api.voice.WavAudioRecorder
import com.hfad.starlit.data.database.AppDatabase
import com.hfad.starlit.data.database.chat.ChatSessionEntity
import com.hfad.starlit.databinding.FragmentMainBinding
import com.hfad.starlit.databinding.DialogGenerateCodeEditorBinding
import com.hfad.starlit.databinding.LayoutChatPageBinding
import com.hfad.starlit.databinding.LayoutDesktopPageBinding
import com.hfad.starlit.databinding.LayoutWorkspacePageBinding
import com.hfad.starlit.data.repository.provider.ProviderRepository
import com.hfad.starlit.tool.impl.CameraPhotoBridge
import com.hfad.starlit.tool.impl.CameraPhotoHost
import com.hfad.starlit.view.glass.LiquidGlass
import com.hfad.starlit.view.glass.GlassDialogStyler
import com.hfad.starlit.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainFragment : Fragment(), CameraPhotoBridge.Host {

    internal companion object {
        internal const val PAGE_DESKTOP = 0
        internal const val PAGE_CHAT = 1
        internal const val PAGE_WORKSPACE = 2
    }

    private var _binding: FragmentMainBinding? = null
    internal val binding get() = _binding!!
    internal var _chatBinding: LayoutChatPageBinding? = null
    internal val chatBinding get() = _chatBinding!!
    private var _workspaceBinding: LayoutWorkspacePageBinding? = null
    internal val workspaceBinding get() = _workspaceBinding!!
    internal var _desktopBinding: LayoutDesktopPageBinding? = null
    internal val desktopBinding get() = _desktopBinding!!
    internal var isInputActive = false
    internal var currentPagerPage = PAGE_CHAT
    private var pagerCallback: ViewPager2.OnPageChangeCallback? = null
    internal lateinit var mainPager: ViewPager2
    private var topBarBaseHeight = 0
    private var inputContainerBasePaddingBottom = 0
    private var lastImeVisible = false
    private var lastSystemBarBottomInset = 0
    private var lastAppliedBottomInset = Int.MIN_VALUE
    internal var currentContextTokens = 0
    internal var currentConsumedTokens = 0L
    internal var currentTokenUsageIncludesEstimate = false
    internal var cachedChatSystemPrompt: String? = null
    internal var forceScrollToLatestMessage = false
    internal var isChatScrollActive = false
    internal var pendingMessagesWhileScrolling: List<ChatMessage>? = null
    private var latestChatMessages: List<ChatMessage> = emptyList()
    private var latestChatMessagesSessionId: Long? = null
    internal var isTaskRunning = false
    internal val selectedImageUris = mutableListOf<Uri>()
    internal var activeAppWebView: WebView? = null
    private lateinit var cameraPhotoHost: CameraPhotoHost
    internal var voiceRecorder: WavAudioRecorder? = null
    internal var voiceInputFile: File? = null
    internal var voiceRecordingConfig: VoiceInputConfig? = null
    internal var isVoiceRecording = false
    internal var isVoiceTranscribing = false
    internal var pendingVoiceStartAfterPermission = false
    private var notificationPermissionRequestInFlight = false
    internal val providerRepository by lazy {
        ProviderRepository(AppDatabase.getDatabase(requireContext().applicationContext).providerDao())
    }

    // ViewModel
    internal val viewModel: ChatViewModel by viewModels()

    // 聊天消息适配器
    internal lateinit var chatAdapter: ChatAdapter

    // 会话列表适配器
    internal lateinit var sessionAdapter: SessionAdapter

    // workspace 文件树适配器
    internal lateinit var workspaceFileAdapter: WorkspaceFileAdapter

    // 桌面应用适配器
    internal lateinit var desktopAppAdapter: DesktopAppAdapter

    internal val hasChatAdapter: Boolean get() = ::chatAdapter.isInitialized
    internal val hasSessionAdapter: Boolean get() = ::sessionAdapter.isInitialized
    internal val hasWorkspaceFileAdapter: Boolean get() = ::workspaceFileAdapter.isInitialized
    internal val hasDesktopAppAdapter: Boolean get() = ::desktopAppAdapter.isInitialized

    internal var activeSessions: List<ChatSessionEntity> = emptyList()
    internal var archivedSessions: List<ChatSessionEntity> = emptyList()
    internal var runningSessionIds: Set<Long> = emptySet()
    private var generateTaskStates: Map<Long, GenerateTaskState> = emptyMap()
    internal var currentGenerateTaskState: GenerateTaskState? = null
    internal var codeEditorDialog: Dialog? = null
    internal var codeEditorBinding: DialogGenerateCodeEditorBinding? = null
    internal var codeEditorSource: CodeEditorSource? = null
    internal var actionMenuDialog: Dialog? = null
    internal var drawerSearchQuery: String = ""
    internal var showArchivedSessions: Boolean = false
    internal var adaptiveTextColor: Int = Color.rgb(32, 37, 53)

    internal val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        onImagesSelectedFromPicker(uris)
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPhotoHost.onCameraPhotoResult(success)
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPhotoHost.onCameraPermissionResult(granted)
    }

    internal val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingVoiceStartAfterPermission) {
            pendingVoiceStartAfterPermission = false
            startVoiceInputWithConfiguredModel()
        } else if (!granted) {
            pendingVoiceStartAfterPermission = false
            Toast.makeText(requireContext(), "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationPermissionRequestInFlight = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraPhotoHost = CameraPhotoHost(
            activity = requireActivity(),
            takePictureLauncher = takePictureLauncher,
            requestPermissionLauncher = requestCameraPermissionLauncher,
            webViewProvider = { activeAppWebView }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        _chatBinding = LayoutChatPageBinding.inflate(inflater)
        _workspaceBinding = LayoutWorkspacePageBinding.inflate(inflater)
        _desktopBinding = LayoutDesktopPageBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainPager = obtainMainPager()
        binding.topBar.bringToFront()
        setupAppBar()
        setupMainPager()
        setupWorkspacePage()
        setupDesktopPage()
        
        // 初始化聊天 RecyclerView
        setupChatRecyclerView()
        CameraPhotoBridge.attach(this)
        
        initInsets()
        
        // 观察 ViewModel 数据
        observeViewModel()
        warmUpContextPromptCache()

        // 初始化模型名称显示
        viewModel.refreshActiveModel()

        chatBinding.etInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                enterInputActiveState()
            } else if (isInputActive) {
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(100)
                    val currentChatBinding = _chatBinding ?: return@launch
                    if (!currentChatBinding.etInput.hasFocus()) {
                        switchToIdleState()
                    }
                }
            }
        }

        chatBinding.ivAdd.setOnClickListener {
            openImagePicker()
        }

        chatBinding.ivContextLimit.setOnClickListener {
            showContextLimitDialog()
        }

        chatBinding.ivModelPicker.setOnClickListener {
            showModelPickerPanel()
        }

        chatBinding.ivVoiceInput.setOnClickListener {
            onVoiceInputClicked()
        }
        chatBinding.ivVoiceInput.setOnLongClickListener {
            if (isVoiceRecording || isVoiceTranscribing) {
                Toast.makeText(requireContext(), "请先结束当前语音输入", Toast.LENGTH_SHORT).show()
            } else {
                showMimoVoiceKeyDialog(startAfterActivation = false)
            }
            true
        }

        chatBinding.generateCodeCard.setOnClickListener {
            showGenerateCodeEditor()
        }

        chatBinding.chipStartTask.setOnClickListener {
            fillInputWithAppPrompt()
        }

        chatBinding.chipLongTermMemory.setOnClickListener {
            fillInputWithMemoryPrompt()
        }

        chatBinding.rvChat.setOnClickListener {
            chatBinding.etInput.clearFocus()
        }

        // 输入框点击：显示输入态
        chatBinding.etInput.setOnClickListener {
            switchToActiveState()
        }

        // 发送按钮点击：发送消息
        chatBinding.ivSend.setOnClickListener {
            sendMessage()
        }

        // 输入框回车键监听：发送消息
        chatBinding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        chatBinding.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCurrentContextTokens()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateCurrentContextTokens()
        updateSendButtonState(false)
        updateVoiceInputButtonState()
        applyWallpaper()

        LiquidGlass.refresh(binding.root)

        binding.root.post {
            if (_binding == null || !isAdded) return@post
            setupSessionRecyclerView()
            setupDrawerMenu()
            LiquidGlass.refresh(binding.root)
        }
    }

    override fun requestCameraPhoto(callbackName: String?) {
        cameraPhotoHost.requestCameraPhoto(callbackName)
    }

    private fun obtainMainPager(): ViewPager2 {
        findMainPager(binding.root)?.let { return it }

        return ViewPager2(requireContext()).apply {
            id = View.generateViewId()
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.mt_background))
            layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            binding.root.addView(this)
        }
    }

    private fun findMainPager(view: View): ViewPager2? {
        if (view is ViewPager2) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findMainPager(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun initInsets() {
        topBarBaseHeight = binding.topBar.layoutParams.height.takeIf { it > 0 } ?: dp(72)
        inputContainerBasePaddingBottom = chatBinding.inputContainer.paddingBottom
        updatePageTopScrollBoundaries(topBarBaseHeight)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            applyAppBarInsets(insets)
            applyChatInsets(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyAppBarInsets(insets)
                    applyChatInsets(insets)
                    return insets
                }
            }
        )
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun applyAppBarInsets(insets: WindowInsetsCompat) {
        val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        val targetHeight = topBarBaseHeight + topInset
        val params = binding.topBar.layoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            binding.topBar.layoutParams = params
        }
        if (binding.topBar.paddingTop != topInset) {
            binding.topBar.updatePadding(top = topInset)
        }
        updatePageTopScrollBoundaries(targetHeight)
    }

    private fun updatePageTopScrollBoundaries(topBarHeight: Int) {
        val chatPaddingTop = topBarHeight + dp(10)
        if (chatBinding.rvChat.paddingTop != chatPaddingTop) {
            chatBinding.rvChat.updatePadding(top = chatPaddingTop)
        }

        val desktopPaddingTop = topBarHeight + dp(16)
        if (desktopBinding.rvDesktopApps.paddingTop != desktopPaddingTop) {
            desktopBinding.rvDesktopApps.updatePadding(top = desktopPaddingTop)
        }

        val workspacePathParams = workspaceBinding.workspacePathBar.layoutParams as ConstraintLayout.LayoutParams
        val workspaceMarginTop = topBarHeight + dp(20)
        if (workspacePathParams.topMargin != workspaceMarginTop) {
            workspacePathParams.topMargin = workspaceMarginTop
            workspaceBinding.workspacePathBar.layoutParams = workspacePathParams
        }
    }

    private fun applyChatInsets(insets: WindowInsetsCompat) {
        val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val rawSystemBarBottomInset = systemBarsInsets.bottom
        if (rawSystemBarBottomInset > 0) {
            lastSystemBarBottomInset = rawSystemBarBottomInset
        }
        val systemBarBottomInset = if (rawSystemBarBottomInset > 0) {
            rawSystemBarBottomInset
        } else {
            lastSystemBarBottomInset
        }
        val imeBottomInset = imeInsets.bottom
        val bottomInset = if (imeVisible || imeBottomInset > systemBarBottomInset) {
            (imeBottomInset - systemBarBottomInset).coerceAtLeast(systemBarBottomInset)
        } else {
            systemBarBottomInset
        }
        val targetPaddingBottom = inputContainerBasePaddingBottom + bottomInset

        if (chatBinding.inputContainer.paddingBottom != targetPaddingBottom) {
            chatBinding.inputContainer.updatePadding(bottom = targetPaddingBottom)
        }
        updateChatListScrollBoundary()

        if (imeVisible && (!lastImeVisible || lastAppliedBottomInset != bottomInset)) {
            scrollChatToBottom()
        }

        lastImeVisible = imeVisible
        lastAppliedBottomInset = bottomInset
    }

    internal fun updateChatListScrollBoundary() {
        val generateCardInset = if (chatBinding.generateCodeCard.visibility == View.VISIBLE) {
            val layoutParams = chatBinding.generateCodeCard.layoutParams as? ViewGroup.MarginLayoutParams
            val cardHeight = layoutParams?.height?.takeIf { it > 0 }
                ?: chatBinding.generateCodeCard.height
            cardHeight + (layoutParams?.bottomMargin ?: 0)
        } else {
            0
        }
        val targetPaddingBottom = dp(8) + chatBinding.inputContainer.height + generateCardInset
        if (chatBinding.rvChat.paddingBottom != targetPaddingBottom) {
            chatBinding.rvChat.updatePadding(bottom = targetPaddingBottom)
        }
    }

    internal fun scrollChatToBottom() {
        if (!::chatAdapter.isInitialized) return
        val messageCount = chatAdapter.itemCount
        if (messageCount <= 0) return
        chatBinding.rvChat.post {
            scrollChatByRemainingDistanceToBottom()
            chatBinding.rvChat.post {
                scrollChatByRemainingDistanceToBottom()
            }
        }
    }

    private fun scrollChatByRemainingDistanceToBottom() {
        val rvChat = chatBinding.rvChat
        val distanceToBottom = rvChat.computeVerticalScrollRange() -
                rvChat.computeVerticalScrollOffset() -
                rvChat.computeVerticalScrollExtent()
        if (distanceToBottom > 0) {
            rvChat.scrollBy(0, distanceToBottom)
        }
    }

    internal fun shouldFollowNewMessages(): Boolean {
        val itemCount = chatAdapter.itemCount
        if (itemCount <= 0) return true
        val distanceToBottom = chatBinding.rvChat.computeVerticalScrollRange() -
                chatBinding.rvChat.computeVerticalScrollOffset() -
                chatBinding.rvChat.computeVerticalScrollExtent()
        return distanceToBottom <= dp(96)
    }


    private fun setupMainPager() {
        mainPager.adapter = StaticPagerAdapter(
            listOf(desktopBinding.root, chatBinding.root, workspaceBinding.root)
        )
        mainPager.offscreenPageLimit = 3
        // 初始页保持 chat（index = 1），桌面在左、workspace 在右
        mainPager.setCurrentItem(PAGE_CHAT, false)
        currentPagerPage = PAGE_CHAT

        pagerCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                updateAppBarTitleSlide(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentPagerPage = position
                updateAppBarAction(position)
                if (position == PAGE_DESKTOP) {
                    refreshDesktopApps()
                }
            }
        }
        mainPager.registerOnPageChangeCallback(pagerCallback!!)
        binding.appBarCapsule.post {
            currentPagerPage = mainPager.currentItem
            updateAppBarAction(currentPagerPage)
            updateAppBarTitleSlide(currentPagerPage, 0f)
        }
    }


    /**
     * 设置侧边栏菜单
     */

    /**
     * 显示清空历史确认对话框
     */

    /**
     * 创建新会话
     */

    /**
     * 初始化会话列表 RecyclerView
     */


    /**
     * 显示会话操作菜单
     */

    /**
     * 显示删除会话确认对话框
     */


    internal fun decorateAlertDialog(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.window?.setWindowAnimations(R.style.MtDialogAnimation)
    }

    internal fun glassDialogSurface(radiusDp: Float): GradientDrawable {
        return GlassDialogStyler.surface(requireContext(), radiusDp)
    }

    internal fun configureFloatingGlassWindow(
        dialog: Dialog,
        width: Int,
        height: Int,
        gravity: Int = Gravity.CENTER,
        y: Int = 0,
        blurRadiusDp: Int = 14,
        dimAmount: Float = 0.16f
    ) {
        GlassDialogStyler.configureFloatingWindow(
            context = requireContext(),
            dialog = dialog,
            width = width,
            height = height,
            gravity = gravity,
            y = y,
            blurRadiusDp = blurRadiusDp,
            dimAmount = dimAmount
        )
    }

    internal fun dp(value: Int): Int {
        return GlassDialogStyler.dp(requireContext(), value)
    }

    internal fun dp(value: Float): Int {
        return GlassDialogStyler.dp(requireContext(), value)
    }

    internal fun selectableItemBackgroundRes(): Int {
        return GlassDialogStyler.selectableItemBackgroundRes(requireContext())
    }

    /**
     * 初始化聊天 RecyclerView
     */


    /**
     * 观察 ViewModel 数据变化
     */
    private fun observeViewModel() {
        // 观察消息列表
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            latestChatMessages = messages
            latestChatMessagesSessionId = viewModel.currentSessionId.value
            renderChatMessages(messages)
        }

        viewModel.currentSessionTokenUsage.observe(viewLifecycleOwner) { usage ->
            currentConsumedTokens = usage.totalTokens
            currentTokenUsageIncludesEstimate = usage.includesEstimate
            updateContextProgressIndicator()
        }

        // 观察所有会话列表
        lifecycleScope.launch {
            viewModel.allSessions.collect { sessions ->
                activeSessions = sessions
                refreshDrawerSessionList()
            }
        }

        // 观察已归档会话列表
        lifecycleScope.launch {
            viewModel.archivedSessions.collect { sessions ->
                archivedSessions = sessions
                refreshDrawerSessionList()
            }
        }

        // 观察加载状态
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            updateSendButtonState(isLoading)
        }

        viewModel.runningSessionIds.observe(viewLifecycleOwner) { sessionIds ->
            runningSessionIds = sessionIds
            if (::sessionAdapter.isInitialized) {
                sessionAdapter.setRunningSessionIds(sessionIds)
            }
            val currentSessionId = viewModel.currentSessionId.value
            updateSendButtonState(currentSessionId != null && currentSessionId in sessionIds)
        }

        viewModel.isGeneratingApp.observe(viewLifecycleOwner) { isGeneratingApp ->
            setKeepScreenOn(isGeneratingApp)
            if (isGeneratingApp) ensureHarnessNotificationPermission()
        }

        viewModel.generateTaskStates.observe(viewLifecycleOwner) { states ->
            generateTaskStates = states
            renderGenerateTaskState(states[viewModel.currentSessionId.value])
            if (::chatAdapter.isInitialized) {
                renderChatMessages(latestChatMessages)
            }
        }

        // 观察错误消息
        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        // 观察当前会话 ID
        viewModel.currentSessionId.observe(viewLifecycleOwner) { sessionId ->
            pendingMessagesWhileScrolling = null
            forceScrollToLatestMessage = true
            if (latestChatMessagesSessionId != sessionId) {
                latestChatMessages = emptyList()
                latestChatMessagesSessionId = sessionId
            }
            updateSendButtonState(sessionId != null && sessionId in runningSessionIds)
            renderGenerateTaskState(sessionId?.let(generateTaskStates::get))
            if (::chatAdapter.isInitialized) {
                renderChatMessages(latestChatMessages)
            }
            // 可以在这里更新 UI，显示当前会话信息
        }

        // 观察当前活跃模型名称
        viewModel.activeModelName.observe(viewLifecycleOwner) { modelName ->
            chatBinding.etInput.hint = modelName
                ?.takeIf { it.isNotBlank() }
                ?.let { "当前使用${it}模型提供服务" }
                ?: "请先配置模型"
            binding.chatTab.contentDescription = modelName
                ?.let { "聊天机器人，当前模型 $it" }
                ?: "聊天机器人，请配置模型"
        }

        // 观察未配置模型事件
        viewModel.noModelConfigured.observe(viewLifecycleOwner) { noModel ->
            if (noModel) {
                Toast.makeText(requireContext(), "请先配置模型后再使用", Toast.LENGTH_LONG).show()
                viewModel.clearNoModelConfigured()
            }
        }

        viewModel.appGenerated.observe(viewLifecycleOwner) { htmlPath ->
            if (htmlPath != null) {
                refreshWorkspaceTree()
                viewModel.clearAppGenerated()
            }
        }
    }


    internal fun roundedBackground(color: Int, radius: Float = 0f, topLeft: Float = radius, topRight: Float = radius): GradientDrawable {
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

    internal fun roundedStrokeBackground(fillColor: Int, strokeColor: Int, radius: Float): GradientDrawable {
        return GlassDialogStyler.roundedStrokeBackground(requireContext(), fillColor, strokeColor, radius)
    }


    /**
     * 切换到输入态
     */

    /**
     * 切换到搜索态
     */


    /**
     * 显示键盘
     */

    /**
     * 隐藏键盘
     */


    /**
     * 发送消息
     */

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.dispatchHarnessNotificationIntent()
        viewModel.refreshActiveModel()
        refreshWorkspaceTree()
        refreshDesktopApps()
        applyWallpaper()
    }

    internal fun openHarnessSessionFromNotification(sessionId: Long) {
        viewModel.switchToSession(sessionId)
        if (::mainPager.isInitialized) {
            mainPager.setCurrentItem(PAGE_CHAT, false)
        }
    }

    override fun onDestroyView() {
        actionMenuDialog?.dismiss()
        actionMenuDialog = null
        codeEditorDialog?.dismiss()
        codeEditorBinding = null
        codeEditorDialog = null
        codeEditorSource = null
        super.onDestroyView()
        cancelVoiceInput()
        CameraPhotoBridge.detach(this)
        activeAppWebView = null
        setKeepScreenOn(false)
        if (::mainPager.isInitialized) {
            pagerCallback?.let { mainPager.unregisterOnPageChangeCallback(it) }
            mainPager.adapter = null
        }
        pagerCallback = null
        _workspaceBinding = null
        _chatBinding = null
        _desktopBinding = null
        _binding = null
    }

    private fun setKeepScreenOn(keepScreenOn: Boolean) {
        val window = activity?.window ?: return
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun ensureHarnessNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (notificationPermissionRequestInFlight) return
        notificationPermissionRequestInFlight = true
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }


    private class StaticPagerAdapter(
        private val pages: List<View>
    ) : RecyclerView.Adapter<StaticPagerAdapter.PageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT
                )
            }
            return PageViewHolder(container)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val page = pages[position]
            (page.parent as? ViewGroup)?.removeView(page)
            holder.container.removeAllViews()
            holder.container.addView(
                page,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        override fun getItemCount(): Int = pages.size

        class PageViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
    }

}
