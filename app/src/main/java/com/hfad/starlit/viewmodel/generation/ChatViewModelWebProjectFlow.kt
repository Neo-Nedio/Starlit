package com.hfad.starlit.viewmodel.generation

import android.app.Application
import android.view.View
import android.webkit.WebView
import com.hfad.starlit.data.model.chat.GenerateTaskState
import com.hfad.starlit.data.api.chat.model.ChatCallConfig
import com.hfad.starlit.data.logging.chat.HarnessConversationStore
import com.hfad.starlit.data.logging.harness.HarnessTraceLogger
import com.hfad.starlit.data.logging.harness.HarnessTraceStatus
import com.hfad.starlit.data.logging.harness.elapsedMillisSince
import com.hfad.starlit.data.logging.harness.record
import com.hfad.starlit.data.preferences.chat.ContextLimitStore
import com.hfad.starlit.service.HarnessForegroundServiceController
import com.hfad.starlit.service.HarnessServiceRequest
import com.hfad.starlit.workspace.AgentWorkspace
import com.hfad.starlit.webapp.generation.AppGenerator
import com.hfad.starlit.webapp.generation.GenerationInputFilter
import com.hfad.starlit.webapp.generation.WebProjectHarnessFileToolFactory
import com.hfad.starlit.webapp.harness.AppHarnessOrchestrator
import com.hfad.starlit.webapp.harness.GeneratedAppHarnessScripts
import com.hfad.starlit.webapp.harness.GeneratedAppWebViewInspector
import com.hfad.starlit.webapp.harness.HarnessBuilder
import com.hfad.starlit.webapp.harness.HarnessCheckResult
import com.hfad.starlit.webapp.harness.HarnessFileTask
import com.hfad.starlit.webapp.harness.HarnessLimits
import com.hfad.starlit.webapp.harness.HarnessRunRequest
import com.hfad.starlit.webapp.harness.HarnessRunResult
import com.hfad.starlit.webapp.harness.StreamingHarnessModelRepair
import com.hfad.starlit.webapp.harness.WebQualityGateContract
import com.hfad.starlit.webapp.harness.WebViewHarnessInspectorAdapter
import com.hfad.starlit.webapp.harness.withDesignRequirements
import com.hfad.starlit.webapp.project.StreamingWebProjectPlanner
import com.hfad.starlit.webapp.project.PreparedWebProject
import com.hfad.starlit.webapp.project.WebAppProjectConventions.PLAN_FILE_NAME as PROJECT_PLAN_FILE_NAME
import com.hfad.starlit.webapp.project.WebAppProjectDraftManager
import com.hfad.starlit.webapp.project.MutableWebProjectBinding
import com.hfad.starlit.webapp.project.WebAppProjectSnapshot
import com.hfad.starlit.webapp.project.WebAppProjectWorkspace
import com.hfad.starlit.webapp.project.WebAppProjectValidator
import com.hfad.starlit.webapp.project.WebProjectFileTool
import com.hfad.starlit.viewmodel.chat.StreamingSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.hfad.starlit.viewmodel.ChatViewModel
import com.hfad.starlit.viewmodel.GENERATED_APP_HARNESS_MAX_ITERATIONS
import com.hfad.starlit.viewmodel.WEB_PROJECT_PLAN_MAX_TOKENS
import com.hfad.starlit.viewmodel.WEB_PROJECT_DIRECTORY_NAME_MAX_CHARS
import com.hfad.starlit.viewmodel.WEB_PROJECT_DIRECTORY_ID_CHARS
import com.hfad.starlit.viewmodel.harness.*
import com.hfad.starlit.viewmodel.chat.*

internal suspend fun ChatViewModel.launchAppGenerationService(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        existingFile: File? = null
    ) {
        if (state.serviceJob?.isActive == true) return

        val runId = "harness-${state.sessionId}-${System.currentTimeMillis()}"
        val traceLogger = createHarnessTraceLogger(state.sessionId, runId)
        val serviceStartedAt = System.nanoTime()
        val request = HarnessServiceRequest(
            runId = runId,
            sessionId = state.sessionId,
            title = "Starlit Harness",
            initialMessage = if (existingFile == null) {
                "正在后台生成应用"
            } else {
                "正在后台修改应用"
            },
            initialStage = "准备中"
        )
        state.serviceRunId = runId
        state.isServiceOwned = true
        state.isGeneratingApp = true
        updateSessionLoadingIndicators()
        traceLogger.record(
            runId = runId,
            component = "SERVICE",
            operation = "launch",
            status = HarnessTraceStatus.PROGRESS,
            message = "已请求启动 Harness 前台服务",
            iteration = 0,
            details = mapOf(
                "session_id" to state.sessionId.toString(),
                "is_modification" to (existingFile != null).toString(),
                "existing_artifact" to existingFile?.absolutePath.orEmpty()
            )
        )

        try {
            state.serviceJob = HarnessForegroundServiceController.launch(
                context = getApplication<Application>(),
                request = request
            ) { reporter ->
                state.harnessProgressReporter = reporter
                var workerFailure: Throwable? = null
                val workerStartedAt = System.nanoTime()
                traceLogger.record(
                    runId = runId,
                    component = "SERVICE",
                    operation = "worker",
                    status = HarnessTraceStatus.STARTED,
                    message = "Harness 后台任务开始执行",
                    iteration = 0,
                    durationMs = elapsedMillisSince(serviceStartedAt)
                )
                try {
                    generateWebProjectFlow(
                        state = state,
                        config = config,
                        userMessage = userMessage,
                        existingFile = existingFile,
                        runId = runId,
                        traceLogger = traceLogger
                    )
                } catch (error: Throwable) {
                    workerFailure = error
                    traceLogger.record(
                        runId = runId,
                        component = "SERVICE",
                        operation = "worker",
                        status = if (error is CancellationException) {
                            HarnessTraceStatus.CANCELLED
                        } else {
                            HarnessTraceStatus.FAILED
                        },
                        message = if (error is CancellationException) {
                            "Harness 后台任务已取消"
                        } else {
                            "Harness 后台任务失败"
                        },
                        iteration = 0,
                        durationMs = elapsedMillisSince(workerStartedAt),
                        details = mapOf("error_type" to error::class.java.simpleName)
                    )
                    throw error
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        state.harnessProgressReporter = null
                        state.serviceJob = null
                        state.isServiceOwned = false
                        state.serviceRunId = null
                        val unfinishedState = _generateTaskStates.value.orEmpty()[state.sessionId]
                        if (unfinishedState?.isRunning != false) {
                            val terminalMessage = when (workerFailure) {
                                is CancellationException -> workerFailure.message ?: "生成任务已停止"
                                null -> "生成任务未完成"
                                else -> workerFailure.message ?: "Harness 执行失败"
                            }
                            updateGenerateTaskState(
                                unfinishedState?.copy(
                                    phase = GenerateTaskState.Phase.ERROR,
                                    status = terminalMessage,
                                    updatedAt = System.currentTimeMillis()
                                ) ?: GenerateTaskState(
                                    sessionId = state.sessionId,
                                    phase = GenerateTaskState.Phase.ERROR,
                                    status = terminalMessage
                                )
                            )
                        }
                        if (workerFailure is CancellationException &&
                            state.userMessagePersisted &&
                            !state.hasFinalAssistantMessage
                        ) {
                            addFinalAssistantMessage(state, "生成任务已停止。")
                        }
                        try {
                            finishStreamingState(state)
                        } finally {
                            val taskState = _generateTaskStates.value.orEmpty()[state.sessionId]
                            when {
                                workerFailure is CancellationException -> reporter.cancel("生成任务已停止")
                                taskState?.phase == GenerateTaskState.Phase.COMPLETED -> reporter.succeed(
                                    message = taskState.status,
                                    diagnostics = taskState.diagnostics
                                )
                                taskState?.phase == GenerateTaskState.Phase.ERROR -> reporter.fail(
                                    message = taskState.status,
                                    diagnostics = taskState.diagnostics
                                )
                                workerFailure != null -> reporter.fail(
                                    message = workerFailure.message ?: "Harness 执行失败"
                                )
                                else -> reporter.fail("生成任务未完成")
                            }
                        }
                        if (workerFailure == null) {
                            val terminalState = _generateTaskStates.value.orEmpty()[state.sessionId]
                            val succeeded = terminalState?.phase == GenerateTaskState.Phase.COMPLETED
                            traceLogger.record(
                                runId = runId,
                                component = "SERVICE",
                                operation = "worker",
                                status = if (succeeded) {
                                    HarnessTraceStatus.SUCCEEDED
                                } else {
                                    HarnessTraceStatus.FAILED
                                },
                                message = if (succeeded) {
                                    "Harness 后台任务成功结束"
                                } else {
                                    "Harness 后台任务未成功完成"
                                },
                                iteration = terminalState?.harnessIteration ?: 0,
                                durationMs = elapsedMillisSince(workerStartedAt),
                                details = mapOf(
                                    "terminal_phase" to (terminalState?.phase?.name ?: "MISSING")
                                )
                            )
                        }
                    }
                }
            }
        } catch (error: Exception) {
            traceLogger.record(
                runId = runId,
                component = "SERVICE",
                operation = "launch",
                status = HarnessTraceStatus.FAILED,
                message = "Harness 前台服务启动失败，切回页面任务",
                iteration = 0,
                durationMs = elapsedMillisSince(serviceStartedAt),
                details = mapOf("error_type" to error::class.java.simpleName)
            )
            state.serviceRunId = null
            state.isServiceOwned = false
            state.isGeneratingApp = false
            updateSessionLoadingIndicators()
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PREPARING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.RETRYING,
                message = "后台服务启动失败，已切回当前页面继续执行",
                diagnostics = listOfNotNull(error.message)
            )
            generateWebProjectFlow(
                state = state,
                config = config,
                userMessage = userMessage,
                existingFile = existingFile,
                runId = runId,
                traceLogger = traceLogger
            )
        }
    }

internal suspend fun ChatViewModel.generateWebProjectFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        existingFile: File? = null,
        runId: String,
        traceLogger: HarnessTraceLogger
    ) {
        val application = getApplication<Application>()
        val flowStartedAt = System.nanoTime()
        traceLogger.record(
            runId = runId,
            component = "FLOW",
            operation = "generate_web_project",
            status = HarnessTraceStatus.STARTED,
            message = "Web 项目生成流程开始",
            iteration = 0,
            details = mapOf(
                "session_id" to state.sessionId.toString(),
                "is_modification" to (existingFile != null).toString(),
                "input_chars" to userMessage.length.toString(),
                "input_sha256" to traceSha256(userMessage),
                "existing_artifact" to existingFile?.absolutePath.orEmpty()
            )
        )
        val filterStartedAt = System.nanoTime()
        val filteredInput = GenerationInputFilter.filter(userMessage)
        traceLogger.record(
            runId = runId,
            component = "INPUT_FILTER",
            operation = "sanitize",
            status = HarnessTraceStatus.SUCCEEDED,
            message = "生成输入过滤完成",
            iteration = 0,
            durationMs = elapsedMillisSince(filterStartedAt),
            details = mapOf(
                "input_chars" to userMessage.length.toString(),
                "filtered_chars" to filteredInput.content.length.toString(),
                "filtered_sha256" to traceSha256(filteredInput.content),
                "notice_count" to filteredInput.notices.size.toString()
            )
        )
        val isModification = existingFile != null
        var resumableProjectPath: String? = existingFile?.absolutePath
        var inspector: GeneratedAppWebViewInspector? = null
        var inspectorWebView: WebView? = null

        state.isGeneratingApp = true
        withContext(Dispatchers.Main.immediate) {
            replaceGenerateTaskState(
                GenerateTaskState(
                    sessionId = state.sessionId,
                    phase = GenerateTaskState.Phase.SANITIZING,
                    status = if (isModification) {
                        "正在准备多文件项目修改"
                    } else {
                        "正在准备多文件项目规划"
                    },
                    isModification = isModification
                )
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.SANITIZING,
                stage = GenerateTaskState.Stage.INPUT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = filteredInput.notices.firstOrNull() ?: "用户输入已过滤并建立安全边界",
                diagnostics = filteredInput.notices,
                isModification = isModification
            )
            state.thinkingContent.clear()
            addStreamingPlaceholder(
                state = state,
                status = if (isModification) "正在读取项目" else "正在规划项目",
                useLoadingLayout = true
            )
            updateStreamingThinking(
                state,
                if (isModification) {
                    "正在解析现有项目并创建可安全修改的草稿..."
                } else {
                    "正在发起独立项目规划请求，确定文件结构与职责..."
                },
                force = true
            )
        }

        try {
            val preparedProject = if (existingFile == null) {
                planAndCreateWebProject(
                    state = state,
                    config = config,
                    userRequirement = filteredInput.promptPayload,
                    runId = runId,
                    traceLogger = traceLogger
                )
            } else {
                val prepareStartedAt = System.nanoTime()
                traceLogger.record(
                    runId = runId,
                    component = "WORKSPACE",
                    operation = "prepare_existing",
                    status = HarnessTraceStatus.STARTED,
                    message = "开始解析现有 Web 项目",
                    iteration = 0,
                    details = mapOf("input_path" to existingFile.absolutePath)
                )
                try {
                    withContext(Dispatchers.IO) {
                        WebAppProjectDraftManager.prepareExisting(existingFile)
                    }
                } catch (error: Throwable) {
                    traceLogger.record(
                        runId = runId,
                        component = "WORKSPACE",
                        operation = "prepare_existing",
                        status = if (error is CancellationException) {
                            HarnessTraceStatus.CANCELLED
                        } else {
                            HarnessTraceStatus.FAILED
                        },
                        message = if (error is CancellationException) {
                            "现有 Web 项目准备已取消"
                        } else {
                            "现有 Web 项目准备失败"
                        },
                        iteration = 0,
                        durationMs = elapsedMillisSince(prepareStartedAt),
                        details = mapOf("error_type" to error::class.java.simpleName)
                    )
                    throw error
                }.also { prepared ->
                    traceLogger.record(
                        runId = runId,
                        component = "WORKSPACE",
                        operation = "prepare_existing",
                        status = HarnessTraceStatus.SUCCEEDED,
                        message = "现有 Web 项目草稿准备完成",
                        iteration = 0,
                        durationMs = elapsedMillisSince(prepareStartedAt),
                        details = mapOf(
                            "project_root" to prepared.snapshot.projectRoot.absolutePath,
                            "content_root" to prepared.snapshot.contentRoot.absolutePath,
                            "entry" to prepared.snapshot.manifest.entryPoint,
                            "project_id" to prepared.snapshot.manifest.projectId,
                            "snapshot_kind" to prepared.snapshot.kind.name,
                            "draft_version" to prepared.snapshot.version.toString()
                        )
                    )
                }
            }
            val binding = MutableWebProjectBinding(
                workspace = preparedProject.workspace,
                projectRoot = preparedProject.snapshot.projectRoot,
                contentRoot = preparedProject.snapshot.contentRoot,
                manifest = preparedProject.snapshot.manifest,
                draftVersion = requireNotNull(preparedProject.snapshot.version) {
                    "多文件项目草稿缺少版本号"
                }
            )
            resumableProjectPath = binding.entryFile.takeIf(File::isFile)?.absolutePath
                ?: binding.projectRoot.absolutePath
            withContext(Dispatchers.IO) {
                repository.markSessionAsGenerate(state.sessionId, resumableProjectPath)
            }
            val projectFileTool = withContext(Dispatchers.IO) {
                WebProjectFileTool(binding.contentRoot).also { tool ->
                    ensureProjectEntryIdentity(binding, tool)
                }
            }
            val initialProjectFiles = withContext(Dispatchers.IO) {
                WebAppProjectDraftManager.projectFilePaths(projectFileTool)
            }
            traceLogger.record(
                runId = runId,
                component = "WORKSPACE",
                operation = "draft_ready",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "项目草稿和文件工具已就绪",
                iteration = 0,
                details = mapOf(
                    "project_root" to binding.projectRoot.absolutePath,
                    "content_root" to binding.contentRoot.absolutePath,
                    "entry" to binding.manifest.entryPoint,
                    "project_id" to binding.manifest.projectId,
                    "draft_version" to binding.draftVersion.toString(),
                    "file_count" to initialProjectFiles.size.toString()
                )
            )
            val initialPreview = withContext(Dispatchers.IO) {
                when {
                    binding.entryFile.isFile -> projectFileTool.read(binding.manifest.entryPoint).content
                    File(binding.contentRoot, PROJECT_PLAN_FILE_NAME).isFile -> {
                        projectFileTool.read(PROJECT_PLAN_FILE_NAME).content
                    }
                    else -> ""
                }
            }

            withContext(Dispatchers.Main.immediate) {
                updateProjectFileState(
                    state = state,
                    relativePath = if (binding.entryFile.isFile) {
                        binding.manifest.entryPoint
                    } else {
                        PROJECT_PLAN_FILE_NAME
                    },
                    content = initialPreview,
                    entryPath = binding.entryFile.absolutePath,
                    projectFiles = initialProjectFiles,
                    isModification = isModification
                )
                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.PROMPTING,
                    stage = GenerateTaskState.Stage.PROMPT,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在组装项目计划、设备上下文和代码工具协议",
                    filePath = binding.entryFile.absolutePath,
                    isModification = isModification
                )
            }

            val promptStartedAt = System.nanoTime()
            traceLogger.record(
                runId = runId,
                component = "PROMPT",
                operation = "build_project_system_prompt",
                status = HarnessTraceStatus.STARTED,
                message = "开始组装项目系统提示词",
                iteration = 0
            )
            val projectSystemPrompt = try {
                withContext(Dispatchers.Default) {
                    AppGenerator.buildProjectSystemPrompt(
                        context = application,
                        userMessage = filteredInput.content,
                        isModification = isModification
                    )
                }
            } catch (error: Throwable) {
                traceLogger.record(
                    runId = runId,
                    component = "PROMPT",
                    operation = "build_project_system_prompt",
                    status = if (error is CancellationException) {
                        HarnessTraceStatus.CANCELLED
                    } else {
                        HarnessTraceStatus.FAILED
                    },
                    message = if (error is CancellationException) {
                        "项目系统提示词组装已取消"
                    } else {
                        "项目系统提示词组装失败"
                    },
                    iteration = 0,
                    durationMs = elapsedMillisSince(promptStartedAt),
                    details = mapOf("error_type" to error::class.java.simpleName)
                )
                throw error
            }
            traceLogger.record(
                runId = runId,
                component = "PROMPT",
                operation = "build_project_system_prompt",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "项目系统提示词组装完成",
                iteration = 0,
                durationMs = elapsedMillisSince(promptStartedAt),
                details = mapOf(
                    "prompt_chars" to projectSystemPrompt.length.toString(),
                    "prompt_sha256" to traceSha256(projectSystemPrompt)
                )
            )
            val modelRepair = StreamingHarnessModelRepair(
                config = config,
                maxTokens = AppGenerator.resolveProjectFileOutputLimit(
                    ContextLimitStore.getTokenLimit(application)
                ),
                onThinking = { delta ->
                    withContext(Dispatchers.Main.immediate) {
                        state.thinkingContent.append(delta)
                        updateStreamingThinking(state, state.thinkingContent.toString())
                    }
                },
                onProgress = { progress ->
                    withContext(Dispatchers.Main.immediate) {
                        if (state.thinkingContent.isBlank()) {
                            updateStreamingThinking(
                                state,
                                "正在执行第 ${progress.iteration} 轮多文件编排 · " +
                                    "已接收 ${progress.receivedChars} 字符",
                                force = progress.receivedChars == 0
                            )
                        }
                        state.harnessProgressReporter?.report(
                            message = "模型正在编排项目文件 · ${progress.receivedChars} 字符",
                            stage = GenerateTaskState.Stage.MODEL.name,
                            iteration = progress.iteration,
                            progress = 25
                        )
                    }
                },
                traceLogger = traceLogger,
                streamChatCompletion = { callConfig, request ->
                    trackedChatStream(state.sessionId, callConfig, request)
                }
            )
            val harnessFileTool = WebProjectHarnessFileToolFactory(
                binding = binding,
                projectFileTool = projectFileTool,
                isModification = isModification,
                onProjectFileChanged = {
                        relativePath,
                        content,
                        entryPath,
                        projectFiles,
                        modifying ->
                    withContext(Dispatchers.Main.immediate) {
                        updateProjectFileState(
                            state = state,
                            relativePath = relativePath,
                            content = content,
                            entryPath = entryPath,
                            projectFiles = projectFiles,
                            isModification = modifying
                        )
                    }
                }
            ).create()

            inspectorWebView = withContext(Dispatchers.Main.immediate) {
                WebView(application)
            }
            inspector = GeneratedAppWebViewInspector(
                webView = inspectorWebView,
                prepareWebView = this::prepareHarnessWebView,
                onEvent = { event ->
                    recordWebInspectionEvent(
                        traceLogger = traceLogger,
                        runId = runId,
                        iteration = currentHarnessIteration(state),
                        event = event
                    )
                }
            )
            val inspectionAdapter = WebViewHarnessInspectorAdapter(
                inspector = inspector,
                traceLogger = traceLogger
            )
            val projectBuilder = HarnessBuilder { checkRequest ->
                val validationStartedAt = System.nanoTime()
                traceLogger.record(
                    runId = runId,
                    component = "PROJECT_VALIDATOR",
                    operation = checkRequest.kind.name,
                    status = HarnessTraceStatus.STARTED,
                    message = "项目清单与本地依赖校验开始",
                    iteration = checkRequest.iteration,
                    details = mapOf(
                        "entry" to binding.manifest.entryPoint,
                        "declared_file_count" to binding.manifest.files.size.toString()
                    )
                )
                val validation = try {
                    withContext(Dispatchers.IO) {
                        WebAppProjectValidator().validate(binding.manifest, binding.contentRoot)
                    }
                } catch (error: Throwable) {
                    traceLogger.record(
                        runId = runId,
                        component = "PROJECT_VALIDATOR",
                        operation = checkRequest.kind.name,
                        status = if (error is CancellationException) {
                            HarnessTraceStatus.CANCELLED
                        } else {
                            HarnessTraceStatus.FAILED
                        },
                        message = if (error is CancellationException) {
                            "项目清单与本地依赖校验已取消"
                        } else {
                            "项目清单与本地依赖校验异常"
                        },
                        iteration = checkRequest.iteration,
                        durationMs = elapsedMillisSince(validationStartedAt),
                        details = mapOf("error_type" to error::class.java.simpleName)
                    )
                    throw error
                }
                traceLogger.record(
                    runId = runId,
                    component = "PROJECT_VALIDATOR",
                    operation = checkRequest.kind.name,
                    status = if (validation.passed) {
                        HarnessTraceStatus.SUCCEEDED
                    } else {
                        HarnessTraceStatus.FAILED
                    },
                    message = if (validation.passed) {
                        "项目清单与本地依赖校验通过"
                    } else {
                        "项目清单或本地依赖校验失败"
                    },
                    iteration = checkRequest.iteration,
                    durationMs = elapsedMillisSince(validationStartedAt),
                    details = mapOf(
                        "passed" to validation.passed.toString(),
                        "file_count" to validation.fileCount.toString(),
                        "total_bytes" to validation.totalBytes.toString(),
                        "diagnostic_count" to validation.diagnostics.size.toString(),
                        "diagnostic_codes" to validation.diagnostics
                            .map { it.code }
                            .distinct()
                            .joinToString(",")
                    )
                )
                if (!validation.passed) {
                    HarnessCheckResult(
                        passed = false,
                        summary = "项目清单或本地依赖未通过构建检查",
                        diagnostics = validation.diagnostics.map { diagnostic ->
                            buildString {
                                append(diagnostic.severity.name)
                                append(' ').append(diagnostic.code).append(": ")
                                append(diagnostic.message)
                                diagnostic.path?.let { append(" [").append(it).append(']') }
                            }
                        },
                        artifactPath = binding.entryFile.absolutePath
                    )
                } else {
                    inspectionAdapter.build(checkRequest)
                }
            }
            val restoredConversation = withContext(Dispatchers.IO) {
                HarnessConversationStore.load(application, state.sessionId)
            }
            val orchestrator = AppHarnessOrchestrator(
                modelRepair = modelRepair,
                fileTool = harnessFileTool,
                builder = projectBuilder,
                inspector = inspectionAdapter,
                testRunner = inspectionAdapter,
                limits = HarnessLimits(
                    maxCodeIterations = GENERATED_APP_HARNESS_MAX_ITERATIONS
                ),
                eventLogger = { eventRunId, event ->
                    logHarnessEvent(state.sessionId, eventRunId, event)
                },
                traceLogger = traceLogger
            )
            val result = withContext(Dispatchers.Default) {
                orchestrator.run(
                    HarnessRunRequest(
                        runId = runId,
                        workspacePath = binding.contentRoot.absolutePath,
                        systemPrompt = projectSystemPrompt,
                        userInput = filteredInput.content,
                        restoredConversation = restoredConversation,
                        onConversationMessage = { message ->
                            HarnessConversationStore.append(application, state.sessionId, message)
                        },
                        artifactPath = binding.entryFile.absolutePath,
                        selfTestScript = GeneratedAppHarnessScripts.selfTest,
                        testSuiteScript = GeneratedAppHarnessScripts.testSuite,
                        acceptanceContract = binding.manifest.appSpec?.acceptanceContract,
                        acceptanceRequired = binding.manifest.appSpec != null,
                        qualityGateContract = binding.manifest.appSpec?.design?.let { design ->
                            WebQualityGateContract().withDesignRequirements(
                                viewportWidths = design.viewportWidths,
                                minTouchTargetCssPixels = design.minTouchTargetPx
                            )
                        } ?: WebQualityGateContract(),
                        planPath = PROJECT_PLAN_FILE_NAME.takeUnless { isModification },
                        fileTasks = if (isModification) {
                            emptyList()
                        } else {
                            binding.manifest.files
                                .filterNot { it.path == PROJECT_PLAN_FILE_NAME }
                                .map { file ->
                                    HarnessFileTask(
                                        path = file.path,
                                        description = file.description,
                                        dependsOn = file.dependsOn.filterNot {
                                            it == PROJECT_PLAN_FILE_NAME
                                        },
                                        criterionIds = file.ownsCriteria
                                    )
                                }
                        },
                        metadata = mapOf("projectId" to binding.manifest.projectId)
                    )
                ) { event ->
                    val projectFiles = withContext(Dispatchers.IO) {
                        WebAppProjectDraftManager.projectFilePaths(projectFileTool)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        updateProjectHarnessEvent(
                            state = state,
                            event = event,
                            isModification = isModification,
                            entryPath = binding.entryFile.absolutePath,
                            projectFiles = projectFiles
                        )
                    }
                }
            }

            when (result) {
                is HarnessRunResult.Delivered -> {
                    val publishStartedAt = System.nanoTime()
                    traceLogger.record(
                        runId = runId,
                        component = "PUBLISH",
                        operation = "publish_draft",
                        status = HarnessTraceStatus.STARTED,
                        message = "项目草稿开始原子发布",
                        iteration = result.iterations,
                        details = mapOf(
                            "project_root" to binding.projectRoot.absolutePath,
                            "draft_version" to binding.draftVersion.toString(),
                            "project_id" to binding.manifest.projectId
                        )
                    )
                    val release = try {
                        withContext(Dispatchers.IO) {
                            WebAppProjectDraftManager.synchronizeManifest(binding, projectFileTool)
                            ensureProjectEntryIdentity(binding, projectFileTool)
                            binding.workspace.publishDraft(
                                projectRoot = binding.projectRoot,
                                draftVersion = binding.draftVersion,
                                manifest = binding.manifest
                            )
                        }
                    } catch (error: Throwable) {
                        traceLogger.record(
                            runId = runId,
                            component = "PUBLISH",
                            operation = "publish_draft",
                            status = if (error is CancellationException) {
                                HarnessTraceStatus.CANCELLED
                            } else {
                                HarnessTraceStatus.FAILED
                            },
                            message = if (error is CancellationException) {
                                "项目草稿发布已取消"
                            } else {
                                "项目草稿发布失败"
                            },
                            iteration = result.iterations,
                            durationMs = elapsedMillisSince(publishStartedAt),
                            details = mapOf("error_type" to error::class.java.simpleName)
                        )
                        throw error
                    }
                    traceLogger.record(
                        runId = runId,
                        component = "PUBLISH",
                        operation = "publish_draft",
                        status = HarnessTraceStatus.SUCCEEDED,
                        message = "项目草稿原子发布完成",
                        iteration = result.iterations,
                        durationMs = elapsedMillisSince(publishStartedAt),
                        details = mapOf(
                            "release_version" to release.version.toString(),
                            "release_root" to release.contentRoot.absolutePath,
                            "entry" to release.entryFile.absolutePath,
                            "self_test_bypassed" to result.selfTestBypassed.toString()
                        )
                    )
                    completeGeneratedWebProject(
                        state = state,
                        release = release,
                        isModification = isModification,
                        iteration = result.iterations,
                        selfTestBypassed = result.selfTestBypassed
                    )
                    traceLogger.record(
                        runId = runId,
                        component = "FLOW",
                        operation = "generate_web_project",
                        status = HarnessTraceStatus.SUCCEEDED,
                        message = "Web 项目生成流程完成",
                        iteration = result.iterations,
                        durationMs = elapsedMillisSince(flowStartedAt),
                        details = mapOf(
                            "artifact" to release.entryFile.absolutePath,
                            "self_test_bypassed" to result.selfTestBypassed.toString()
                        )
                    )
                }

                is HarnessRunResult.Failed -> {
                    traceLogger.record(
                        runId = runId,
                        component = "FLOW",
                        operation = "generate_web_project",
                        status = HarnessTraceStatus.FAILED,
                        message = "Web 项目生成流程未通过 Harness",
                        iteration = result.iterations,
                        durationMs = elapsedMillisSince(flowStartedAt),
                        details = mapOf(
                            "artifact" to result.artifactPath.orEmpty(),
                            "diagnostic_count" to result.diagnostics.size.toString()
                        )
                    )
                    failGeneratedWebProject(
                        state = state,
                        message = result.reason,
                        diagnostics = result.diagnostics,
                        entryPath = resumableProjectPath,
                        isModification = isModification
                    )
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                traceLogger.record(
                    runId = runId,
                    component = "FLOW",
                    operation = "generate_web_project",
                    status = HarnessTraceStatus.CANCELLED,
                    message = "Web 项目生成流程已取消",
                    iteration = currentHarnessIteration(state),
                    durationMs = elapsedMillisSince(flowStartedAt)
                )
                throw error
            }
            traceLogger.record(
                runId = runId,
                component = "FLOW",
                operation = "generate_web_project",
                status = HarnessTraceStatus.FAILED,
                message = "Web 项目生成流程异常",
                iteration = currentHarnessIteration(state),
                durationMs = elapsedMillisSince(flowStartedAt),
                details = mapOf("error_type" to error::class.java.simpleName)
            )
            failGeneratedWebProject(
                state = state,
                message = error.message ?: "多文件项目生成失败",
                diagnostics = listOf(error.message ?: error::class.java.simpleName),
                entryPath = resumableProjectPath,
                isModification = isModification
            )
            throw error
        } finally {
            val cleanupStartedAt = System.nanoTime()
            traceLogger.record(
                runId = runId,
                component = "CLEANUP",
                operation = "webview",
                status = HarnessTraceStatus.STARTED,
                message = "Harness WebView 清理开始",
                iteration = currentHarnessIteration(state),
                details = mapOf("webview_created" to (inspectorWebView != null).toString())
            )
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                inspector?.cancelCurrentInspection()
                inspectorWebView?.let { webView ->
                    webView.stopLoading()
                    webView.removeAllViews()
                    webView.destroy()
                }
                state.isGeneratingApp = false
                updateSessionLoadingIndicators()
            }
            traceLogger.record(
                runId = runId,
                component = "CLEANUP",
                operation = "webview",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "Harness WebView 清理完成",
                iteration = currentHarnessIteration(state),
                durationMs = elapsedMillisSince(cleanupStartedAt)
            )
        }
    }

internal suspend fun ChatViewModel.planAndCreateWebProject(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userRequirement: String,
        runId: String,
        traceLogger: HarnessTraceLogger
    ): PreparedWebProject {
        withContext(Dispatchers.Main.immediate) {
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.RUNNING,
                message = "正在请求模型规划项目文件与依赖顺序"
            )
        }
        val planner = StreamingWebProjectPlanner(
            config = config,
            maxTokens = minOf(
                WEB_PROJECT_PLAN_MAX_TOKENS,
                ContextLimitStore.getTokenLimit(getApplication())
            ).coerceAtLeast(1),
            onThinking = { delta ->
                withContext(Dispatchers.Main.immediate) {
                    state.thinkingContent.append(delta)
                    updateStreamingThinking(state, state.thinkingContent.toString())
                }
            },
            onProgress = { receivedChars ->
                withContext(Dispatchers.Main.immediate) {
                    if (state.thinkingContent.isBlank()) {
                        updateStreamingThinking(
                            state,
                            "正在接收项目规划 · $receivedChars 字符"
                        )
                    }
                    state.harnessProgressReporter?.report(
                        message = "正在规划多文件项目 · $receivedChars 字符",
                        stage = GenerateTaskState.Stage.PROMPT.name,
                        iteration = 0,
                        progress = 12
                    )
                }
            },
            onStatus = { message ->
                withContext(Dispatchers.Main.immediate) {
                    updateHarnessStage(
                        state = state,
                        phase = GenerateTaskState.Phase.PROMPTING,
                        stage = GenerateTaskState.Stage.PROMPT,
                        outcome = GenerateTaskState.Outcome.RUNNING,
                        message = message,
                        iteration = 0
                    )
                    state.harnessProgressReporter?.report(
                        message = message,
                        stage = GenerateTaskState.Stage.PROMPT.name,
                        iteration = 0,
                        progress = 12
                    )
                }
            },
            traceLogger = traceLogger,
            streamChatCompletion = { callConfig, request ->
                trackedChatStream(state.sessionId, callConfig, request)
            }
        )
        val planningResult = withContext(Dispatchers.Default) {
            planner.plan(userRequirement, runId)
        }
        val workspace = WebAppProjectWorkspace()
        val workspaceStartedAt = System.nanoTime()
        traceLogger.record(
            runId = runId,
            component = "WORKSPACE",
            operation = "create_draft",
            status = HarnessTraceStatus.STARTED,
            message = "开始创建规划后的项目草稿",
            iteration = 0,
            details = mapOf(
                "project_id" to planningResult.manifest.projectId,
                "entry" to planningResult.manifest.entryPoint,
                "declared_file_count" to planningResult.manifest.files.size.toString()
            )
        )
        val snapshot = try {
            withContext(Dispatchers.IO) {
                val projectRoot = nextWebProjectRoot(
                    displayName = planningResult.manifest.displayName,
                    projectId = planningResult.manifest.projectId
                )
                workspace.create(projectRoot, planningResult.manifest).also { draft ->
                    WebProjectFileTool(draft.contentRoot).write(
                        path = PROJECT_PLAN_FILE_NAME,
                        content = planningResult.projectPlanJson,
                        createOnly = true
                    )
                }
            }
        } catch (error: Throwable) {
            traceLogger.record(
                runId = runId,
                component = "WORKSPACE",
                operation = "create_draft",
                status = if (error is CancellationException) {
                    HarnessTraceStatus.CANCELLED
                } else {
                    HarnessTraceStatus.FAILED
                },
                message = if (error is CancellationException) {
                    "规划后的项目草稿创建已取消"
                } else {
                    "规划后的项目草稿创建失败"
                },
                iteration = 0,
                durationMs = elapsedMillisSince(workspaceStartedAt),
                details = mapOf("error_type" to error::class.java.simpleName)
            )
            throw error
        }
        traceLogger.record(
            runId = runId,
            component = "WORKSPACE",
            operation = "create_draft",
            status = HarnessTraceStatus.SUCCEEDED,
            message = "规划后的项目草稿创建完成",
            iteration = 0,
            durationMs = elapsedMillisSince(workspaceStartedAt),
            details = mapOf(
                "project_root" to snapshot.projectRoot.absolutePath,
                "content_root" to snapshot.contentRoot.absolutePath,
                "entry" to snapshot.entryFile.absolutePath,
                "draft_version" to snapshot.version.toString(),
                "project_plan_chars" to planningResult.projectPlanJson.length.toString(),
                "project_plan_sha256" to traceSha256(planningResult.projectPlanJson)
            )
        )
        withContext(Dispatchers.Main.immediate) {
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.APPLYING_TOOL,
                stage = GenerateTaskState.Stage.TOOL,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = "项目计划已创建 · ${planningResult.manifest.files.size - 1} 个代码文件",
                filePath = snapshot.entryFile.absolutePath
            )
            updateProjectFileState(
                state = state,
                relativePath = PROJECT_PLAN_FILE_NAME,
                content = planningResult.projectPlanJson,
                entryPath = snapshot.entryFile.absolutePath,
                projectFiles = listOf(PROJECT_PLAN_FILE_NAME),
                isModification = false
            )
        }
        return PreparedWebProject(workspace, snapshot)
    }

internal fun ChatViewModel.ensureProjectEntryIdentity(
        binding: MutableWebProjectBinding,
        projectFileTool: WebProjectFileTool
    ) {
        if (!binding.entryFile.isFile) return
        val current = projectFileTool.read(binding.manifest.entryPoint).content
        val identified = AppGenerator.ensureWebAppIdentity(current)
        if (identified != current) {
            projectFileTool.write(binding.manifest.entryPoint, identified)
        }
    }

internal suspend fun ChatViewModel.completeGeneratedWebProject(
        state: StreamingSessionState,
        release: WebAppProjectSnapshot,
        isModification: Boolean,
        iteration: Int,
        selfTestBypassed: Boolean = false
    ) {
        val projectFiles = withContext(Dispatchers.IO) {
            WebAppProjectDraftManager.projectFilePaths(WebProjectFileTool(release.contentRoot))
        }
        val entryCode = withContext(Dispatchers.IO) { release.entryFile.readText() }
        repository.markSessionAsGenerate(state.sessionId, release.entryFile.absolutePath)
        withContext(Dispatchers.Main.immediate) {
            val current = _generateTaskStates.value.orEmpty()[state.sessionId]
            updateGenerateTaskState(
                (current ?: GenerateTaskState(
                    sessionId = state.sessionId,
                    phase = GenerateTaskState.Phase.COMPLETED,
                    status = "多文件项目已通过验证"
                )).copy(
                    phase = GenerateTaskState.Phase.COMPLETED,
                    code = entryCode,
                    filePath = release.entryFile.absolutePath,
                    activeFilePath = release.manifest.entryPoint,
                    projectFiles = projectFiles,
                    status = if (selfTestBypassed) {
                        "多文件项目已构建、运行检查和测试并发布（自测非阻塞放行）"
                    } else {
                        "多文件项目已构建、测试并发布"
                    },
                    isModification = isModification,
                    harnessIteration = maxOf(current?.harnessIteration ?: 0, iteration),
                    diagnostics = emptyList(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            removeStreamingPlaceholder(state)
            state.thinkingContent.clear()
        }
        addFinalAssistantMessage(
            state = state,
            content = if (isModification) {
                if (selfTestBypassed) {
                    "项目修改已完成，并通过构建、WebView 检查和测试集；自测非阻塞放行，点击下方查看 👇"
                } else {
                    "项目修改已完成，并通过构建、WebView 检查、自测和测试集，点击下方查看 👇"
                }
            } else {
                if (selfTestBypassed) {
                    "多文件 Web 应用已生成，并通过构建、WebView 检查和测试集；自测非阻塞放行，点击下方预览 👇"
                } else {
                    "多文件 Web 应用已生成，并通过完整 Harness，点击下方预览 👇"
                }
            },
            appHtmlPath = release.entryFile.absolutePath
        )
        withContext(Dispatchers.Main.immediate) {
            _appGenerated.value = release.entryFile.absolutePath
        }
    }

internal suspend fun ChatViewModel.failGeneratedWebProject(
        state: StreamingSessionState,
        message: String,
        diagnostics: List<String>,
        entryPath: String?,
        isModification: Boolean
    ) {
        entryPath?.let { path ->
            repository.markSessionAsGenerate(state.sessionId, path)
        }
        val current = withContext(Dispatchers.Main.immediate) {
            val base = _generateTaskStates.value.orEmpty()[state.sessionId]
            val failed = (base ?: GenerateTaskState(
                sessionId = state.sessionId,
                phase = GenerateTaskState.Phase.ERROR,
                status = message
            )).copy(
                phase = GenerateTaskState.Phase.ERROR,
                filePath = entryPath ?: base?.filePath,
                status = message,
                isModification = isModification,
                diagnostics = diagnostics,
                updatedAt = System.currentTimeMillis()
            )
            updateGenerateTaskState(failed)
            removeStreamingPlaceholder(state)
            state.thinkingContent.clear()
            _errorMessage.value = message
            failed
        }
        if (!state.hasFinalAssistantMessage) {
            addFinalAssistantMessage(
                state = state,
                content = "多文件 Web 项目未能完成：${current.status}"
            )
        }
    }

internal fun ChatViewModel.prepareHarnessWebView(webView: WebView) {
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        webView.settings.blockNetworkLoads = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        val metrics = getApplication<Application>().resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        webView.layout(0, 0, width, height)
    }

internal fun ChatViewModel.nextWebProjectRoot(displayName: String, projectId: String): File {
        val generatedApps = File(getApplication<Application>().filesDir, AgentWorkspace.WEB_DIR)
            .apply { mkdirs() }
        val stem = displayName
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .trim('_', '-', '.', ' ')
            .take(WEB_PROJECT_DIRECTORY_NAME_MAX_CHARS)
            .ifBlank { "STARLITWeb应用" }
        val uniqueSuffix = projectId
            .filter(Char::isLetterOrDigit)
            .takeLast(WEB_PROJECT_DIRECTORY_ID_CHARS)
            .ifBlank {
                System.currentTimeMillis().toString().takeLast(WEB_PROJECT_DIRECTORY_ID_CHARS)
            }
        var candidate = File(generatedApps, "${stem}_$uniqueSuffix")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(generatedApps, "${stem}_$suffix")
            suffix++
        }
        return candidate
    }



