package com.hfad.starlit.viewmodel
import com.hfad.starlit.viewmodel.generation.*
import com.hfad.starlit.viewmodel.harness.*

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.hfad.starlit.data.model.chat.ChatMessage
import com.hfad.starlit.data.model.chat.GenerateTaskState
import com.hfad.starlit.data.model.chat.SessionTokenUsage
import com.hfad.starlit.data.api.chat.model.ApiConfig
import com.hfad.starlit.data.api.chat.model.ApiMessage
import com.hfad.starlit.data.api.chat.model.ChatCallConfig
import com.hfad.starlit.data.api.chat.service.ChatApiMessageBuilder
import com.hfad.starlit.data.api.chat.service.ChatCallConfigResolver
import com.hfad.starlit.data.api.chat.model.ChatRequest
import com.hfad.starlit.data.api.chat.service.StreamingApiService
import com.hfad.starlit.data.database.AppDatabase
import com.hfad.starlit.data.database.chat.ChatMessageEntity
import com.hfad.starlit.data.database.chat.ChatSessionEntity
import com.hfad.starlit.data.logging.api.ApiDiagnosticContext
import com.hfad.starlit.data.logging.chat.HarnessConversationStore
import com.hfad.starlit.data.logging.harness.record
import com.hfad.starlit.data.repository.chat.ChatRepository
import com.hfad.starlit.service.HarnessForegroundServiceController
import com.hfad.starlit.service.HarnessProgress
import com.hfad.starlit.service.HarnessProgressStatus
import com.hfad.starlit.workspace.AgentWorkspace
import com.hfad.starlit.intent.AppIntentDetector
import com.hfad.starlit.chat.errors.ErrorAnalyzer
import com.hfad.starlit.chat.media.ImageUtils
import com.hfad.starlit.webapp.project.WebAppProjectDraftManager
import com.hfad.starlit.webapp.project.WebAppProjectResolver
import com.hfad.starlit.webapp.project.WebAppProjectSnapshotKind
import com.hfad.starlit.webapp.project.WebAppProjectWorkspace
import com.hfad.starlit.webapp.project.WebProjectFileTool
import com.hfad.starlit.viewmodel.harness.HarnessTraceRecorder
import com.hfad.starlit.viewmodel.chat.ChatStreamingUiController
import com.hfad.starlit.viewmodel.chat.SessionTokenUsageController
import com.hfad.starlit.viewmodel.chat.StreamingSessionState
import com.hfad.starlit.viewmodel.generation.GenerateTaskStateController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    internal val repository = ChatRepository(AppDatabase.getDatabase(application).chatDao())
    private val providerRepository = com.hfad.starlit.data.repository.provider.ProviderRepository(
        AppDatabase.getDatabase(application).providerDao()
    )
    internal val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentSessionId = MutableLiveData<Long?>()
    val currentSessionId: LiveData<Long?> = _currentSessionId

    private val _messages = MutableLiveData<List<ChatMessage>>()
    val messages: LiveData<List<ChatMessage>> = _messages

    val allSessions: Flow<List<ChatSessionEntity>> = repository.getAllSessions()

    val archivedSessions: Flow<List<ChatSessionEntity>> = repository.getArchivedSessions()

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _runningSessionIds = MutableLiveData<Set<Long>>(emptySet())
    val runningSessionIds: LiveData<Set<Long>> = _runningSessionIds

    private val _isGeneratingApp = MutableLiveData(false)
    val isGeneratingApp: LiveData<Boolean> = _isGeneratingApp

    internal val _generateTaskStates = MutableLiveData<Map<Long, GenerateTaskState>>(emptyMap())
    val generateTaskStates: LiveData<Map<Long, GenerateTaskState>> = _generateTaskStates

    internal val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    internal val _appGenerated = MutableLiveData<String?>()
    val appGenerated: LiveData<String?> = _appGenerated

    private val _activeModelName = MutableLiveData<String?>(null)
    val activeModelName: LiveData<String?> = _activeModelName

    private val _noModelConfigured = MutableLiveData(false)
    val noModelConfigured: LiveData<Boolean> = _noModelConfigured

    private val _currentSessionTokenUsage = MutableLiveData(SessionTokenUsage())
    val currentSessionTokenUsage: LiveData<SessionTokenUsage> = _currentSessionTokenUsage
    private var currentTokenUsageSessionId: Long? = null

    private var messagesJob: Job? = null
    private val streamingStates = mutableMapOf<Long, StreamingSessionState>()
    internal val appGenerationProgressIntervalMs = 1_500L
    private val streamingUiUpdateIntervalMs = 120L
    private val reconnectWindowMs = 5_000L
    private val reconnectCountdownIntervalMs = 1_000L
    private val requestInterruptedFallback = "出错了，请稍后重试。"
    private val requestFailedSuffix = "\n\n出错了，请稍后重试。"
    private val assistantStreamingPlaceholder = "\u200B"
    private var serviceProgressStates: Map<String, HarnessProgress> = emptyMap()
    private val sessionTokenUsageController by lazy(LazyThreadSafetyMode.NONE) {
        SessionTokenUsageController(
            repository = repository,
            ioScope = fallbackScope,
            isSessionCurrent = { sessionId ->
                _currentSessionId.value == sessionId &&
                    currentTokenUsageSessionId == sessionId
            },
            currentUsage = { _currentSessionTokenUsage.value },
            publishUsage = { _currentSessionTokenUsage.value = it }
        )
    }
    private val streamingUiController by lazy(LazyThreadSafetyMode.NONE) {
        ChatStreamingUiController(
            currentSessionId = { _currentSessionId.value },
            messages = { _messages.value },
            publishMessages = { _messages.value = it },
            updateIntervalMs = streamingUiUpdateIntervalMs,
            placeholderContent = assistantStreamingPlaceholder
        )
    }

    init {
        AgentWorkspace.ensureWorkspace(application)
        refreshActiveModel()
        observeHarnessServiceProgress()
    }

    private fun observeHarnessServiceProgress() {
        viewModelScope.launch {
            HarnessForegroundServiceController.states.collect { states ->
                serviceProgressStates = states
                states.values.forEach(::mergeDetachedHarnessProgress)
                updateSessionLoadingIndicators()
            }
        }
    }

    private fun mergeDetachedHarnessProgress(progress: HarnessProgress) {
        val sessionId = progress.sessionId ?: return
        if (streamingStates.containsKey(sessionId)) return
        val current = _generateTaskStates.value.orEmpty()[sessionId]
        if (current != null && current.updatedAt >= progress.updatedAt) return

        val phase = when (progress.status) {
            HarnessProgressStatus.SUCCEEDED -> GenerateTaskState.Phase.COMPLETED
            HarnessProgressStatus.FAILED,
            HarnessProgressStatus.CANCELLED -> GenerateTaskState.Phase.ERROR
            HarnessProgressStatus.RUNNING -> progress.stage.toGenerateTaskPhase()
        }
        val outcome = when (progress.status) {
            HarnessProgressStatus.RUNNING -> if (
                progress.message.contains("重试") || progress.message.contains("修复")
            ) {
                GenerateTaskState.Outcome.RETRYING
            } else {
                GenerateTaskState.Outcome.RUNNING
            }
            HarnessProgressStatus.SUCCEEDED -> GenerateTaskState.Outcome.PASSED
            HarnessProgressStatus.FAILED,
            HarnessProgressStatus.CANCELLED -> GenerateTaskState.Outcome.FAILED
        }
        val eventStage = progress.stage?.let { stage ->
            runCatching { GenerateTaskState.Stage.valueOf(stage) }.getOrNull()
        }
        val base = current ?: GenerateTaskState(
            sessionId = sessionId,
            phase = phase,
            status = progress.message
        )
        var projected = base.copy(
            phase = phase,
            status = progress.message,
            harnessIteration = maxOf(base.harnessIteration, progress.iteration),
            diagnostics = progress.diagnostics,
            updatedAt = progress.updatedAt
        )
        val lastEvent = projected.harnessEvents.lastOrNull()
        if (eventStage != null && (
                lastEvent?.stage != eventStage ||
                    lastEvent.operation != progress.operation ||
                    lastEvent.outcome != outcome ||
                    lastEvent.message != progress.message
                )
        ) {
            projected = projected.appendHarnessEvent(
                GenerateTaskState.HarnessEvent(
                    stage = eventStage,
                    outcome = outcome,
                    message = progress.message,
                    iteration = progress.iteration,
                    operation = progress.operation,
                    diagnostics = progress.diagnostics,
                    timestamp = progress.updatedAt
                )
            )
        }
        val allStates = _generateTaskStates.value.orEmpty().toMutableMap()
        allStates[sessionId] = projected
        _generateTaskStates.value = allStates
    }

    internal fun String?.toGenerateTaskPhase(): GenerateTaskState.Phase {
        return when (this) {
            GenerateTaskState.Stage.INPUT.name -> GenerateTaskState.Phase.SANITIZING
            GenerateTaskState.Stage.PROMPT.name -> GenerateTaskState.Phase.PROMPTING
            GenerateTaskState.Stage.MODEL.name -> GenerateTaskState.Phase.REQUESTING_MODEL
            GenerateTaskState.Stage.TOOL.name -> GenerateTaskState.Phase.APPLYING_TOOL
            GenerateTaskState.Stage.BUILD.name -> GenerateTaskState.Phase.BUILDING
            GenerateTaskState.Stage.INSPECT.name -> GenerateTaskState.Phase.INSPECTING
            GenerateTaskState.Stage.SELF_TEST.name -> GenerateTaskState.Phase.SELF_TESTING
            GenerateTaskState.Stage.TEST.name -> GenerateTaskState.Phase.TESTING
            GenerateTaskState.Stage.DELIVER.name -> GenerateTaskState.Phase.COMPLETED
            GenerateTaskState.Phase.SANITIZING.name -> GenerateTaskState.Phase.SANITIZING
            GenerateTaskState.Phase.PROMPTING.name -> GenerateTaskState.Phase.PROMPTING
            GenerateTaskState.Phase.PREPARING.name -> GenerateTaskState.Phase.PREPARING
            GenerateTaskState.Phase.REQUESTING_MODEL.name -> GenerateTaskState.Phase.REQUESTING_MODEL
            GenerateTaskState.Phase.WRITING_INITIAL.name -> GenerateTaskState.Phase.WRITING_INITIAL
            GenerateTaskState.Phase.WRITING_DIFF.name -> GenerateTaskState.Phase.WRITING_DIFF
            GenerateTaskState.Phase.APPLYING_DIFF.name -> GenerateTaskState.Phase.APPLYING_DIFF
            GenerateTaskState.Phase.APPLYING_TOOL.name -> GenerateTaskState.Phase.APPLYING_TOOL
            GenerateTaskState.Phase.BUILDING.name -> GenerateTaskState.Phase.BUILDING
            GenerateTaskState.Phase.INSPECTING.name -> GenerateTaskState.Phase.INSPECTING
            GenerateTaskState.Phase.SELF_TESTING.name -> GenerateTaskState.Phase.SELF_TESTING
            GenerateTaskState.Phase.TESTING.name -> GenerateTaskState.Phase.TESTING
            GenerateTaskState.Phase.REPAIRING.name -> GenerateTaskState.Phase.REPAIRING
            else -> GenerateTaskState.Phase.PREPARING
        }
    }

    fun refreshActiveModel() {
        viewModelScope.launch {
            val config = ChatCallConfigResolver.resolve(getApplication<Application>(), providerRepository)
            _activeModelName.value = config?.model
        }
    }

    fun createEmptySession() {
        messagesJob?.cancel()
        viewModelScope.launch {
            val sessionId = repository.createSession("新会话")
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
            currentTokenUsageSessionId = sessionId
            _currentSessionTokenUsage.value = SessionTokenUsage()
            loadMessages(sessionId)
            updateSessionLoadingIndicators()
        }
    }

    fun switchToSession(sessionId: Long) {
        messagesJob?.cancel()
        _currentSessionId.value = sessionId
        currentTokenUsageSessionId = sessionId
        _currentSessionTokenUsage.value = SessionTokenUsage()
        sessionTokenUsageController.load(sessionId, viewModelScope)
        loadMessages(sessionId)
        restoreGenerateTaskState(sessionId)
        updateSessionLoadingIndicators()
    }

    private fun loadMessages(sessionId: Long) {
        messagesJob = viewModelScope.launch {
            repository.getMessagesBySessionId(sessionId).collect { entities ->
                if (_currentSessionId.value == sessionId) {
                    val dbMessages = entities.map { it.toChatMessage() }
                    publishMessages(sessionId, dbMessages)
                }
            }
        }
    }

    private fun publishMessages(sessionId: Long, dbMessages: List<ChatMessage>) {
        val streamingMessage = streamingStates[sessionId]?.placeholder
        _messages.value = if (streamingMessage != null) {
            dbMessages + streamingMessage
        } else {
            dbMessages
        }
    }

    internal fun trackedChatStream(
        sessionId: Long,
        config: ChatCallConfig,
        request: ChatRequest
    ): Flow<StreamingApiService.StreamEvent> {
        val trackedRequest = request.copy(
            tokenUsageListener = { usage -> sessionTokenUsageController.record(sessionId, usage) }
        )
        return StreamingApiService.streamChatCompletion(config, trackedRequest)
    }

    fun sendMessage(content: String, imagePath: String? = null, imageUris: List<Uri>? = null) {
        val sessionId = _currentSessionId.value ?: run {
            createNewSessionAndSendMessage(content, imagePath, imageUris)
            return
        }

        cancelStreaming(sessionId, persistFallback = true)
        val state = StreamingSessionState(sessionId)
        streamingStates[sessionId] = state
        updateSessionLoadingIndicators()

        state.job = viewModelScope.launch {
            _errorMessage.value = null
            state.streamingContent.clear()
            try {
                withContext(Dispatchers.IO) {
                    AgentWorkspace.appendExplicitMemoryIfNeeded(getApplication(), content)
                }

                val config = ChatCallConfigResolver.resolve(getApplication<Application>(), providerRepository)
                if (config == null) {
                    _noModelConfigured.value = true
                    return@launch
                }

                _activeModelName.value = config.model

                val finalImagePath = imagePath ?: imageUris?.firstOrNull()?.toString()
                val imageBase64List = loadImageBase64List(imagePath, imageUris)

                repository.sendUserMessage(sessionId, content, finalImagePath)
                state.userMessagePersisted = true

                if (repository.getMessageCount(sessionId) == 1) {
                    repository.updateSessionTitle(sessionId, content.ifEmpty { "[图片]" })
                }

                generateResponseForPersistedUserMessage(state, config, content, imageBase64List)
            } finally {
                if (!state.isServiceOwned) {
                    finishStreamingState(state)
                }
            }
        }
    }

    private suspend fun loadImageBase64List(
        imagePath: String? = null,
        imageUris: List<Uri>? = null
    ): List<String> = withContext(Dispatchers.IO) {
        val imageBase64List = mutableListOf<String>()
        val urisToProcess = imageUris?.take(ApiConfig.MAX_IMAGE_COUNT)
            ?: listOfNotNull(imagePath?.let { Uri.parse(it) })

        urisToProcess.forEach { uri ->
            ImageUtils.uriToBase64(getApplication(), uri)?.let { imageBase64List.add(it) }
        }
        imageBase64List
    }

    private suspend fun generateResponseForPersistedUserMessage(
        state: StreamingSessionState,
        config: ChatCallConfig,
        content: String,
        imageBase64List: List<String>
    ) {
        val sessionRouting = withContext(Dispatchers.IO) {
            val session = repository.getSessionById(state.sessionId)
            val latestGeneratedPath = repository.getMessagesBySessionIdOnce(state.sessionId)
                .asReversed()
                .firstNotNullOfOrNull { it.appHtmlPath?.takeIf(String::isNotBlank) }
            val appHtmlPath = session?.appHtmlPath?.takeIf(String::isNotBlank) ?: latestGeneratedPath
            SessionRouting(
                isGenerateTask = session?.isGenerateTask == true || appHtmlPath != null,
                appHtmlPath = appHtmlPath
            )
        }

        if (sessionRouting.isGenerateTask) {
            repository.markSessionAsGenerate(state.sessionId, sessionRouting.appHtmlPath)
            val existingFile = sessionRouting.appHtmlPath
                ?.let(::File)
                ?.takeIf { it.exists() }
            launchAppGenerationService(state, config, content, existingFile)
            return
        }

        val isAppIntent = withContext(Dispatchers.IO) {
            AppIntentDetector.isAppGenerationIntent(
                context = getApplication(),
                config = config,
                userMessage = content,
                hasGeneratedAppInSession = false,
                tokenUsageListener = { usage ->
                    sessionTokenUsageController.record(state.sessionId, usage)
                },
                diagnosticContext = ApiDiagnosticContext(
                    runId = "session-${state.sessionId}",
                    operation = "intent.classify"
                ),
            )
        }

        if (!isAppIntent) {
            normalChatFlow(state, config, imageBase64List)
            return
        }

        repository.markSessionAsGenerate(state.sessionId)
        updateGenerateTaskState(
            GenerateTaskState(
                sessionId = state.sessionId,
                phase = GenerateTaskState.Phase.PREPARING,
                status = "正在准备初版应用"
            )
        )
        launchAppGenerationService(state, config, content)
    }


    private suspend fun normalChatFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        imageBase64List: List<String>
    ) {
        val sessionId = state.sessionId
        val historyMessages = repository.getMessagesBySessionIdOnce(sessionId)
        val systemPrompt = withContext(Dispatchers.IO) {
            AgentWorkspace.buildSystemPrompt(getApplication())
        }
        val apiMessages = ChatApiMessageBuilder.build(historyMessages, systemPrompt, imageBase64List)

        val request = ChatRequest(
            model = config.model,
            messages = apiMessages,
            stream = true
        )

        state.thinkingContent.clear()
        state.reconnectPrefixPending = ""
        addStreamingPlaceholder(state, status = "正在思考", useLoadingLayout = false)

        collectChatStreamWithReconnect(state, config, request)
    }

    private suspend fun collectChatStreamWithReconnect(
        state: StreamingSessionState,
        config: ChatCallConfig,
        request: ChatRequest
    ) {
        var reconnectDeadlineAt: Long? = null
        var activeRequest = request

        while (true) {
            var shouldRetry = false
            var finished = false
            var handledError = false

            trackedChatStream(state.sessionId, config, activeRequest)
                .catch { e ->
                    if (e is CancellationException) throw e
                    handledError = true
                    handleApiError(
                        state = state,
                        config = config,
                        rawError = e.message ?: "未知错误",
                        scene = "普通聊天",
                        partialContentToKeep = state.streamingContent.toString().trimEnd().ifEmpty { null }
                    )
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Start -> {
                            if (reconnectDeadlineAt != null) {
                                clearReconnectStatus(state)
                            }
                        }

                        is StreamingApiService.StreamEvent.Thinking -> {
                            if (state.streamingContent.isEmpty()) {
                                state.thinkingContent.append(event.text)
                                updateStreamingThinking(state, state.thinkingContent.toString())
                            }
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            val chunk = consumeReconnectChunk(state, event.text)
                            if (chunk.isEmpty()) return@collect
                            state.streamingContent.append(chunk)
                            updateStreamingMessage(state, state.streamingContent.toString())
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            clearReconnectStatus(state)
                            val finalContent = state.streamingContent.toString().trimEnd()
                            removeStreamingPlaceholder(state)
                            if (finalContent.isNotEmpty()) {
                                addFinalAssistantMessage(state, finalContent)
                            }
                            state.streamingContent.clear()
                            state.thinkingContent.clear()
                            state.reconnectPrefixPending = ""
                            finished = true
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            val deadlineAt = reconnectDeadlineAt
                                ?: (System.currentTimeMillis() + reconnectWindowMs).also {
                                    reconnectDeadlineAt = it
                                }
                            val remainingMs = deadlineAt - System.currentTimeMillis()
                            if (remainingMs <= 0L) {
                                persistPartialContentWithError(state)
                                finished = true
                            } else {
                                state.reconnectPrefixPending = state.streamingContent.toString()
                                shouldRetry = true
                                updateReconnectStatus(
                                    state,
                                    ceil(remainingMs / 1000.0).toInt().coerceAtLeast(1)
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            handledError = true
                            handleApiError(
                                state = state,
                                config = config,
                                rawError = event.message,
                                scene = "普通聊天",
                                partialContentToKeep = state.streamingContent.toString().trimEnd().ifEmpty { null }
                            )
                        }
                    }
                }

            when {
                finished || handledError -> return
                shouldRetry -> {
                    val deadlineAt = reconnectDeadlineAt ?: return
                    val remainingMs = deadlineAt - System.currentTimeMillis()
                    if (remainingMs <= 0L) {
                        persistPartialContentWithError(state)
                        return
                    }
                    activeRequest = buildReconnectRequest(
                        baseRequest = request,
                        partialContent = state.streamingContent.toString().trimEnd()
                    )
                    delay(min(reconnectCountdownIntervalMs, remainingMs))
                }

                else -> return
            }
        }
    }

    private fun buildReconnectRequest(
        baseRequest: ChatRequest,
        partialContent: String
    ): ChatRequest {
        if (partialContent.isBlank()) {
            return baseRequest
        }

        val reconnectPrompt = AgentWorkspace.buildReconnectResumePrompt(
            context = getApplication(),
            partialContent = partialContent
        )

        val reconnectMessages = baseRequest.messages.toMutableList().apply {
            add(
                ApiMessage(
                    role = ChatMessage.ROLE_ASSISTANT,
                    content = partialContent
                )
            )
            add(
                ApiMessage(
                    role = ChatMessage.ROLE_USER,
                    content = reconnectPrompt
                )
            )
        }

        return baseRequest.copy(messages = reconnectMessages)
    }

    private fun consumeReconnectChunk(state: StreamingSessionState, rawChunk: String): String {
        var chunk = rawChunk
        var pendingPrefix = state.reconnectPrefixPending
        if (pendingPrefix.isEmpty()) return chunk

        val overlap = pendingPrefix.commonPrefixWith(chunk)
        if (overlap.isNotEmpty()) {
            pendingPrefix = pendingPrefix.removePrefix(overlap)
            chunk = chunk.removePrefix(overlap)
        }

        if (chunk.isNotEmpty() && pendingPrefix.isNotEmpty() && chunk.startsWith(pendingPrefix)) {
            chunk = chunk.removePrefix(pendingPrefix)
            pendingPrefix = ""
        }

        state.reconnectPrefixPending = pendingPrefix
        return chunk
    }

    private suspend fun persistPartialContentWithError(state: StreamingSessionState) {
        val content = state.streamingContent.toString().trimEnd()
        clearReconnectStatus(state)
        removeStreamingPlaceholder(state)
        state.streamingContent.clear()
        state.thinkingContent.clear()
        state.reconnectPrefixPending = ""
        if (!state.userMessagePersisted || state.hasFinalAssistantMessage) return
        val finalText = if (content.isNotEmpty()) {
            content + requestFailedSuffix
        } else {
            requestInterruptedFallback
        }
        addFinalAssistantMessage(state, finalText)
    }


    internal val harnessTraceRecorder by lazy(LazyThreadSafetyMode.NONE) { HarnessTraceRecorder() }

    internal val generateTaskStateController by lazy(LazyThreadSafetyMode.NONE) {
        GenerateTaskStateController(
            taskStates = _generateTaskStates,
            streamingStateForSession = { sessionId -> streamingStates[sessionId] },
            updateIntervalMs = appGenerationProgressIntervalMs,
            notificationUpdateIntervalMs = HARNESS_NOTIFICATION_UPDATE_INTERVAL_MS
        )
    }

    internal fun updateGenerateTaskCode(
        state: StreamingSessionState,
        code: CharSequence,
        phase: GenerateTaskState.Phase,
        status: String,
        filePath: String? = null,
        isModification: Boolean = false,
        force: Boolean = false
    ) = generateTaskStateController.updateGenerateTaskCode(
        state, code, phase, status, filePath, isModification, force
    )

    internal fun updateGenerateTaskState(taskState: GenerateTaskState) =
        generateTaskStateController.updateGenerateTaskState(taskState)

    internal fun replaceGenerateTaskState(taskState: GenerateTaskState) =
        generateTaskStateController.replaceGenerateTaskState(taskState)

    internal fun updateHarnessStage(
        state: StreamingSessionState,
        phase: GenerateTaskState.Phase,
        stage: GenerateTaskState.Stage,
        outcome: GenerateTaskState.Outcome,
        message: String,
        iteration: Int = 1,
        diagnostics: List<String> = emptyList(),
        filePath: String? = null,
        isModification: Boolean = false
    ) = generateTaskStateController.updateHarnessStage(
        state, phase, stage, outcome, message, iteration, diagnostics, filePath, isModification
    )

    internal fun updateProjectHarnessEvent(
        state: StreamingSessionState,
        event: GenerateTaskState.HarnessEvent,
        isModification: Boolean,
        entryPath: String? = null,
        projectFiles: List<String> = emptyList()
    ) = generateTaskStateController.updateProjectHarnessEvent(
        state, event, isModification, entryPath, projectFiles
    )

    internal fun updateProjectFileState(
        state: StreamingSessionState,
        relativePath: String,
        content: String,
        entryPath: String?,
        projectFiles: List<String>,
        isModification: Boolean
    ) = generateTaskStateController.updateProjectFileState(
        state, relativePath, content, entryPath, projectFiles, isModification
    )

    internal fun markGenerateTaskError(
        state: StreamingSessionState,
        message: String,
        code: String = "",
        filePath: String? = null,
        isModification: Boolean = false
    ) = generateTaskStateController.markGenerateTaskError(
        state, message, code, filePath, isModification
    )

    private fun restoreGenerateTaskState(sessionId: Long) {
        if (streamingStates[sessionId]?.isGeneratingApp == true) return
        serviceProgressStates.values
            .firstOrNull { it.sessionId == sessionId && it.isRunning }
            ?.let { progress ->
                mergeDetachedHarnessProgress(progress)
                return
            }
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                val session = repository.getSessionById(sessionId) ?: return@withContext null
                val legacyPath = repository.getMessagesBySessionIdOnce(sessionId)
                    .asReversed()
                    .firstNotNullOfOrNull { it.appHtmlPath?.takeIf(String::isNotBlank) }
                val path = session.appHtmlPath?.takeIf(String::isNotBlank) ?: legacyPath
                if (!session.isGenerateTask && path == null) return@withContext null
                val resolved = path
                    ?.let(::File)
                    ?.takeIf(File::exists)
                    ?.let { input -> runCatching { WebAppProjectResolver.resolve(input) }.getOrNull() }
                val managedProject = resolved?.takeUnless {
                    it.kind == WebAppProjectSnapshotKind.LEGACY
                }
                val restoredProject = resolved?.let { project ->
                    if (project.kind == WebAppProjectSnapshotKind.LEGACY) {
                        project
                    } else {
                        WebAppProjectWorkspace().activeRelease(project.projectRoot)
                    }
                }
                if (managedProject != null && restoredProject == null) {
                    return@withContext GenerateTaskState(
                        sessionId = sessionId,
                        phase = GenerateTaskState.Phase.ERROR,
                        status = "项目尚无可用的已发布版本"
                    )
                }
                val restoredPath = restoredProject?.entryFile?.absolutePath ?: path
                if (!session.isGenerateTask || restoredPath != session.appHtmlPath) {
                    repository.markSessionAsGenerate(sessionId, restoredPath)
                }
                val file = if (restoredProject != null) {
                    restoredProject.entryFile.takeIf(File::isFile)
                } else {
                    path?.let(::File)?.takeIf(File::isFile)
                }
                if (file == null) {
                    GenerateTaskState(
                        sessionId = sessionId,
                        phase = GenerateTaskState.Phase.ERROR,
                        filePath = restoredPath,
                        status = "生成任务尚无可用的 HTML 文件"
                    )
                } else {
                    val projectFiles = restoredProject?.let { project ->
                        WebAppProjectDraftManager.projectFilePaths(WebProjectFileTool(project.contentRoot))
                    }.orEmpty()
                    GenerateTaskState(
                        sessionId = sessionId,
                        phase = GenerateTaskState.Phase.COMPLETED,
                        code = file.readText(),
                        filePath = file.absolutePath,
                        activeFilePath = restoredProject?.manifest?.entryPoint ?: file.name,
                        projectFiles = projectFiles,
                        status = "应用源码已载入"
                    )
                }
            }
            if (restored != null && streamingStates[sessionId]?.isGeneratingApp != true) {
                updateGenerateTaskState(restored)
            }
        }
    }

    internal fun addStreamingPlaceholder(
        state: StreamingSessionState,
        status: String,
        useLoadingLayout: Boolean
    ) = streamingUiController.addStreamingPlaceholder(state, status, useLoadingLayout)

    internal fun updateStreamingThinking(
        state: StreamingSessionState,
        thinking: String,
        force: Boolean = false
    ) = streamingUiController.updateStreamingThinking(state, thinking, force)

    private fun updateStreamingMessage(
        state: StreamingSessionState,
        content: String,
        force: Boolean = false
    ) = streamingUiController.updateStreamingMessage(state, content, force)

    internal fun removeStreamingPlaceholder(state: StreamingSessionState) =
        streamingUiController.removeStreamingPlaceholder(state)

    private fun updateStreamingStatusText(state: StreamingSessionState, text: String) =
        streamingUiController.updateStreamingStatusText(state, text)

    private fun updateReconnectStatus(state: StreamingSessionState, secondsRemaining: Int) =
        streamingUiController.updateReconnectStatus(state, secondsRemaining)

    private fun clearReconnectStatus(state: StreamingSessionState) =
        streamingUiController.clearReconnectStatus(state)

    internal suspend fun handleApiError(
        state: StreamingSessionState,
        config: ChatCallConfig?,
        rawError: String,
        scene: String,
        partialContentToKeep: String? = null
    ) {
        state.thinkingContent.clear()
        state.streamingContent.clear()
        state.reconnectPrefixPending = ""

        if (state.placeholder != null) {
            updateStreamingStatusText(state, "出错了，Starlit正在拼命分析…")
        } else {
            addStreamingPlaceholder(state, "出错了，Starlit正在拼命分析…", useLoadingLayout = false)
        }

        val analyzed = config?.let {
            ErrorAnalyzer.analyze(
                config = it,
                rawError = rawError,
                scene = scene,
                tokenUsageListener = { usage ->
                    sessionTokenUsageController.record(state.sessionId, usage)
                },
                diagnosticContext = ApiDiagnosticContext(
                    runId = state.serviceRunId ?: "session-${state.sessionId}",
                    operation = "error.analyze"
                )
            )
        }

        removeStreamingPlaceholder(state)

        partialContentToKeep?.takeIf { it.isNotEmpty() }?.let {
            addFinalAssistantMessage(state, it)
        }

        addFinalAssistantMessage(state, analyzed ?: requestInterruptedFallback)
    }

    fun stopStreaming() {
        _currentSessionId.value?.let { cancelStreaming(it, persistFallback = true) }
    }

    private fun cancelStreaming(sessionId: Long, persistFallback: Boolean = false) {
        val state = streamingStates.remove(sessionId)
        if (state == null) {
            serviceProgressStates.values
                .firstOrNull { it.sessionId == sessionId && it.isRunning }
                ?.let { progress ->
                    HarnessForegroundServiceController.cancel(
                        getApplication<Application>(),
                        progress.runId,
                        "生成任务已停止"
                    )
                }
            updateSessionLoadingIndicators()
            return
        }
        state.job?.cancel()
        state.job = null
        state.serviceRunId?.let { runId ->
            HarnessForegroundServiceController.cancel(
                getApplication<Application>(),
                runId,
                "生成任务已停止"
            )
        } ?: state.serviceJob?.cancel(CancellationException("生成任务已停止"))
        state.serviceJob = null
        state.serviceRunId = null
        state.isServiceOwned = false
        stopAppGenerationProgressHeartbeat(state)
        if (state.isGeneratingApp) {
            val task = _generateTaskStates.value.orEmpty()[sessionId]
            updateGenerateTaskState(
                task?.copy(
                    phase = GenerateTaskState.Phase.ERROR,
                    status = "生成任务已停止",
                    updatedAt = System.currentTimeMillis()
                ) ?: GenerateTaskState(
                    sessionId = sessionId,
                    phase = GenerateTaskState.Phase.ERROR,
                    status = "生成任务已停止"
                )
            )
        }
        state.isGeneratingApp = false
        if (persistFallback) {
            fallbackScope.launch {
                persistFallbackIfNeeded(state)
            }
        }
        state.streamingContent.clear()
        state.thinkingContent.clear()
        state.reconnectPrefixPending = ""
        removeStreamingPlaceholder(state)
        updateSessionLoadingIndicators()
    }

    private fun cancelAllStreaming(
        persistFallback: Boolean = false,
        includeServiceOwned: Boolean = true
    ) {
        streamingStates.keys.toList().forEach { sessionId ->
            if (!includeServiceOwned && streamingStates[sessionId]?.isServiceOwned == true) {
                return@forEach
            }
            cancelStreaming(sessionId, persistFallback = persistFallback)
        }
    }

    internal suspend fun finishStreamingState(state: StreamingSessionState) {
        stopAppGenerationProgressHeartbeat(state)
        state.isGeneratingApp = false
        if (streamingStates[state.sessionId] === state) {
            persistFallbackIfNeeded(state)
            streamingStates.remove(state.sessionId)
            state.streamingContent.clear()
            state.thinkingContent.clear()
            state.reconnectPrefixPending = ""
            removeStreamingPlaceholder(state)
            updateSessionLoadingIndicators()
        }
    }

    internal fun updateSessionLoadingIndicators() {
        val serviceRunningIds = serviceProgressStates.values
            .asSequence()
            .filter(HarnessProgress::isRunning)
            .mapNotNull(HarnessProgress::sessionId)
            .toSet()
        val runningIds = streamingStates.keys + serviceRunningIds
        _runningSessionIds.value = runningIds
        _isLoading.value = _currentSessionId.value?.let { it in runningIds } == true
        _isGeneratingApp.value = streamingStates.values.any { it.isGeneratingApp } ||
            serviceRunningIds.isNotEmpty()
    }

    internal suspend fun addFinalAssistantMessage(
        state: StreamingSessionState,
        content: String,
        appHtmlPath: String? = null
    ): Long {
        state.hasFinalAssistantMessage = true
        return repository.addAssistantMessage(state.sessionId, content, appHtmlPath)
    }

    private suspend fun persistFallbackIfNeeded(state: StreamingSessionState) {
        if (!state.userMessagePersisted || state.hasFinalAssistantMessage) return
        val partialContent = state.streamingContent.toString().trimEnd()
        val fallback = if (partialContent.isNotEmpty()) {
            partialContent + requestFailedSuffix
        } else {
            requestInterruptedFallback
        }
        addFinalAssistantMessage(state, fallback)
    }

    private fun createNewSessionAndSendMessage(
        content: String,
        imagePath: String?,
        imageUris: List<Uri>?
    ) {
        messagesJob?.cancel()
        viewModelScope.launch {
            val sessionId = repository.createSession(content.ifEmpty { "[图片]" })
            _currentSessionId.value = sessionId
            _messages.value = emptyList()
            currentTokenUsageSessionId = sessionId
            _currentSessionTokenUsage.value = SessionTokenUsage()
            loadMessages(sessionId)
            delay(100)
            sendMessage(content, imagePath, imageUris)
        }
    }

    fun deleteSession(sessionId: Long) {
        cancelStreaming(sessionId)
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            withContext(Dispatchers.IO) {
                HarnessConversationStore.delete(getApplication<Application>(), sessionId)
            }
            _generateTaskStates.value = _generateTaskStates.value.orEmpty() - sessionId
            if (_currentSessionId.value == sessionId) {
                _currentSessionId.value = null
                _messages.value = emptyList()
                currentTokenUsageSessionId = null
                _currentSessionTokenUsage.value = SessionTokenUsage()
            }
        }
    }

    fun setSessionArchived(sessionId: Long, isArchived: Boolean) {
        viewModelScope.launch {
            repository.setSessionArchived(sessionId, isArchived)
        }
    }

    fun deleteMessage(messageId: Long) {
        if (messageId <= 0) return
        viewModelScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    fun updateMessageContent(messageId: Long, content: String) {
        if (messageId <= 0 || content.isBlank()) return
        viewModelScope.launch {
            repository.updateMessageContent(messageId, content)
        }
    }

    fun editUserMessageAndRegenerate(messageId: Long, content: String) {
        if (messageId <= 0 || content.isBlank()) return
        viewModelScope.launch {
            val originalMessage = repository.getMessageById(messageId) ?: return@launch
            if (originalMessage.role != ChatMessage.ROLE_USER) return@launch

            cancelStreaming(originalMessage.sessionId, persistFallback = true)
            val editedMessage = repository.updateMessageContentAndDeleteAfter(messageId, content)
                ?: return@launch
            if (repository.getMessageCount(editedMessage.sessionId) == 1) {
                repository.updateSessionTitle(editedMessage.sessionId, content.ifEmpty { "[图片]" })
                if (repository.getSessionById(editedMessage.sessionId)?.isGenerateTask == true) {
                    repository.clearGeneratedApp(editedMessage.sessionId)
                }
            }

            val state = StreamingSessionState(editedMessage.sessionId)
            streamingStates[editedMessage.sessionId] = state
            updateSessionLoadingIndicators()

            state.job = viewModelScope.launch {
                _errorMessage.value = null
                state.streamingContent.clear()
                try {
                    withContext(Dispatchers.IO) {
                        AgentWorkspace.appendExplicitMemoryIfNeeded(getApplication(), content)
                    }

                    val config = ChatCallConfigResolver.resolve(getApplication<Application>(), providerRepository)
                    if (config == null) {
                        _noModelConfigured.value = true
                        return@launch
                    }

                    _activeModelName.value = config.model
                    state.userMessagePersisted = true

                    val imageBase64List = loadImageBase64List(imagePath = editedMessage.imagePath)
                    generateResponseForPersistedUserMessage(state, config, content, imageBase64List)
                } finally {
                    if (!state.isServiceOwned) {
                        finishStreamingState(state)
                    }
                }
            }
        }
    }

    fun deleteAllSessions() {
        cancelAllStreaming()
        viewModelScope.launch {
            repository.deleteAllSessions()
            withContext(Dispatchers.IO) {
                HarnessConversationStore.deleteAll(getApplication<Application>())
            }
            _currentSessionId.value = null
            _messages.value = emptyList()
            currentTokenUsageSessionId = null
            _currentSessionTokenUsage.value = SessionTokenUsage()
            _generateTaskStates.value = emptyMap()
        }
    }

    fun clearCurrentSession() {
        _currentSessionId.value?.let { cancelStreaming(it, persistFallback = true) }
        _currentSessionId.value = null
        _messages.value = emptyList()
        currentTokenUsageSessionId = null
        _currentSessionTokenUsage.value = SessionTokenUsage()
        updateSessionLoadingIndicators()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearAppGenerated() {
        _appGenerated.value = null
    }

    fun clearNoModelConfigured() {
        _noModelConfigured.value = false
    }

    override fun onCleared() {
        cancelAllStreaming(persistFallback = true, includeServiceOwned = false)
        messagesJob?.cancel()
        super.onCleared()
    }
}

internal const val WEB_APP_DIFF_LOG_TAG = "WebAppDiff"
internal const val APP_DIFF_LOG_PREVIEW_CHARS = 3_000
internal const val GENERATED_APP_HARNESS_MAX_ITERATIONS = 8
internal const val GENERATED_APP_REPAIR_PATCH_ATTEMPTS = 2
internal const val HARNESS_NOTIFICATION_UPDATE_INTERVAL_MS = 750L
internal const val WEB_PROJECT_PLAN_MAX_TOKENS = 24_000
internal const val WEB_PROJECT_DIRECTORY_NAME_MAX_CHARS = 64
internal const val WEB_PROJECT_DIRECTORY_ID_CHARS = 8
private data class SessionRouting(
    val isGenerateTask: Boolean,
    val appHtmlPath: String?
)

private fun ChatMessageEntity.toChatMessage() = ChatMessage(
    messageId = messageId,
    role = role,
    content = content,
    imagePath = imagePath,
    timestamp = timestamp,
    isStreaming = false,
    appHtmlPath = appHtmlPath,
    statusText = null,
    showStatusLoader = false
)
