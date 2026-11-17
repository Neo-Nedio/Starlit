package com.hfad.starlit.viewmodel.harness

import android.app.Application
import android.util.Log
import android.view.View
import android.webkit.WebView
import com.hfad.starlit.data.model.chat.ChatMessage
import com.hfad.starlit.data.model.chat.GenerateTaskState
import com.hfad.starlit.data.api.chat.model.ApiMessage
import com.hfad.starlit.data.api.chat.model.ChatCallConfig
import com.hfad.starlit.data.api.chat.model.ChatRequest
import com.hfad.starlit.data.api.chat.service.StreamingApiService
import com.hfad.starlit.data.logging.harness.HarnessTraceLogger
import com.hfad.starlit.data.preferences.chat.ContextLimitStore
import com.hfad.starlit.webapp.generation.AppGenerator
import com.hfad.starlit.webapp.generation.GenerationInputFilter
import com.hfad.starlit.webapp.generation.LocalDiffFileTool
import com.hfad.starlit.webapp.harness.GeneratedAppHarnessScripts
import com.hfad.starlit.webapp.harness.GeneratedAppWebViewInspector
import com.hfad.starlit.webapp.harness.WebInspectionReport
import com.hfad.starlit.webapp.harness.WebInspectionEvent
import com.hfad.starlit.webapp.harness.WebInspectionTarget
import com.hfad.starlit.viewmodel.chat.StreamingSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.hfad.starlit.viewmodel.ChatViewModel
import com.hfad.starlit.viewmodel.GENERATED_APP_HARNESS_MAX_ITERATIONS
import com.hfad.starlit.viewmodel.GENERATED_APP_REPAIR_PATCH_ATTEMPTS
import com.hfad.starlit.viewmodel.WEB_APP_DIFF_LOG_TAG
import com.hfad.starlit.viewmodel.APP_DIFF_LOG_PREVIEW_CHARS
import com.hfad.starlit.viewmodel.chat.*

internal suspend fun ChatViewModel.runGeneratedAppHarness(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        isModification: Boolean,
        startingIteration: Int,
        initialCode: String? = null
    ) {
        var iteration = startingIteration.coerceAtLeast(1)
        var code = initialCode ?: withContext(Dispatchers.IO) { htmlFile.readText() }

        while (iteration <= GENERATED_APP_HARNESS_MAX_ITERATIONS) {
            val inspectorWebView = withContext(Dispatchers.Main) {
                WebView(getApplication<Application>())
            }
            val inspector = GeneratedAppWebViewInspector(
                webView = inspectorWebView,
                prepareWebView = { webView ->
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
            )

            try {
                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.BUILDING,
                    stage = GenerateTaskState.Stage.BUILD,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在构建并检查 HTML/JavaScript",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val buildReport = inspector.inspectBuild(code)
                if (!buildReport.passed) {
                    val diagnostics = GeneratedAppHarnessScripts.diagnostics(buildReport)
                    updateHarnessFailure(
                        state = state,
                        phase = GenerateTaskState.Phase.BUILDING,
                        stage = GenerateTaskState.Stage.BUILD,
                        message = "构建失败，准备返回模型修复",
                        iteration = iteration,
                        diagnostics = diagnostics,
                        htmlFile = htmlFile,
                        isModification = isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state = state,
                        config = config,
                        userMessage = userMessage,
                        htmlFile = htmlFile,
                        failedStage = "build",
                        diagnostics = diagnostics,
                        iteration = iteration,
                        isModification = isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.BUILDING,
                    GenerateTaskState.Stage.BUILD,
                    "构建成功",
                    iteration,
                    buildReport,
                    htmlFile,
                    isModification
                )

                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.INSPECTING,
                    stage = GenerateTaskState.Stage.INSPECT,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在通过 WebView 检查器读取运行错误",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val runtimeReport = inspector.inspectRuntime(WebInspectionTarget.Html(code))
                if (!runtimeReport.passed) {
                    val diagnostics = GeneratedAppHarnessScripts.diagnostics(runtimeReport)
                    updateHarnessFailure(
                        state,
                        GenerateTaskState.Phase.INSPECTING,
                        GenerateTaskState.Stage.INSPECT,
                        "WebView 运行检查失败，准备返回模型修复",
                        iteration,
                        diagnostics,
                        htmlFile,
                        isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state,
                        config,
                        userMessage,
                        htmlFile,
                        "runtime-inspection",
                        diagnostics,
                        iteration,
                        isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.INSPECTING,
                    GenerateTaskState.Stage.INSPECT,
                    "WebView 检查器未发现运行错误",
                    iteration,
                    runtimeReport,
                    htmlFile,
                    isModification
                )

                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.SELF_TESTING,
                    stage = GenerateTaskState.Stage.SELF_TEST,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在运行页面自测",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val selfTestReport = inspector.runSelfTests(
                    target = WebInspectionTarget.Html(code),
                    testScript = GeneratedAppHarnessScripts.selfTest
                )
                if (!selfTestReport.passed) {
                    val diagnostics = GeneratedAppHarnessScripts.diagnostics(selfTestReport)
                    updateHarnessFailure(
                        state,
                        GenerateTaskState.Phase.SELF_TESTING,
                        GenerateTaskState.Stage.SELF_TEST,
                        "页面自测未通过，准备返回模型修复",
                        iteration,
                        diagnostics,
                        htmlFile,
                        isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state,
                        config,
                        userMessage,
                        htmlFile,
                        "self-test",
                        diagnostics,
                        iteration,
                        isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.SELF_TESTING,
                    GenerateTaskState.Stage.SELF_TEST,
                    "页面自测通过",
                    iteration,
                    selfTestReport,
                    htmlFile,
                    isModification
                )

                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.BUILDING,
                    stage = GenerateTaskState.Stage.BUILD,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "自测通过，正在执行交付前构建",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val finalBuildReport = inspector.inspectBuild(code)
                val finalBuildFailure = runCatching {
                    AppGenerator.validateGeneratedWebApp(code)
                }.exceptionOrNull()
                if (!finalBuildReport.passed || finalBuildFailure != null) {
                    val diagnostics = (
                        GeneratedAppHarnessScripts.diagnostics(finalBuildReport) +
                            listOfNotNull(
                                finalBuildFailure?.message ?: finalBuildFailure?.let {
                                    "交付前构建校验失败"
                                }
                            )
                        ).distinct()
                    updateHarnessFailure(
                        state,
                        GenerateTaskState.Phase.BUILDING,
                        GenerateTaskState.Stage.BUILD,
                        "交付前构建失败，准备返回模型修复",
                        iteration,
                        diagnostics,
                        htmlFile,
                        isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state,
                        config,
                        userMessage,
                        htmlFile,
                        "final-build",
                        diagnostics,
                        iteration,
                        isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.BUILDING,
                    GenerateTaskState.Stage.BUILD,
                    "交付前构建通过",
                    iteration,
                    finalBuildReport,
                    htmlFile,
                    isModification
                )

                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.TESTING,
                    stage = GenerateTaskState.Stage.TEST,
                    outcome = GenerateTaskState.Outcome.RUNNING,
                    message = "正在运行独立测试集",
                    iteration = iteration,
                    filePath = htmlFile.absolutePath,
                    isModification = isModification
                )
                val testSuiteReport = inspector.runTestSuite(
                    target = WebInspectionTarget.Html(code),
                    testScript = GeneratedAppHarnessScripts.testSuite
                )
                if (!testSuiteReport.passed) {
                    val diagnostics = GeneratedAppHarnessScripts.diagnostics(testSuiteReport)
                    updateHarnessFailure(
                        state,
                        GenerateTaskState.Phase.TESTING,
                        GenerateTaskState.Stage.TEST,
                        "测试集未通过，准备返回代码阶段修复",
                        iteration,
                        diagnostics,
                        htmlFile,
                        isModification
                    )
                    code = repairGeneratedAppFromDiagnostics(
                        state,
                        config,
                        userMessage,
                        htmlFile,
                        "test-suite",
                        diagnostics,
                        iteration,
                        isModification
                    )
                    iteration++
                    continue
                }
                updateHarnessReportPassed(
                    state,
                    GenerateTaskState.Phase.TESTING,
                    GenerateTaskState.Stage.TEST,
                    "独立测试集通过",
                    iteration,
                    testSuiteReport,
                    htmlFile,
                    isModification
                )
                completeGeneratedAppHarness(
                    state = state,
                    htmlFile = htmlFile,
                    code = code,
                    isModification = isModification,
                    iteration = iteration
                )
                return
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    inspector.cancelCurrentInspection()
                    inspectorWebView.stopLoading()
                    inspectorWebView.removeAllViews()
                    inspectorWebView.destroy()
                }
            }
        }

        throw IllegalStateException("Harness 自动修复已达到 $GENERATED_APP_HARNESS_MAX_ITERATIONS 轮上限")
    }

internal fun ChatViewModel.updateHarnessFailure(
        state: StreamingSessionState,
        phase: GenerateTaskState.Phase,
        stage: GenerateTaskState.Stage,
        message: String,
        iteration: Int,
        diagnostics: List<String>,
        htmlFile: File,
        isModification: Boolean
    ) {
        updateHarnessStage(
            state = state,
            phase = phase,
            stage = stage,
            outcome = GenerateTaskState.Outcome.FAILED,
            message = message,
            iteration = iteration,
            diagnostics = diagnostics.ifEmpty { listOf(message) },
            filePath = htmlFile.absolutePath,
            isModification = isModification
        )
    }

internal fun ChatViewModel.updateHarnessReportPassed(
        state: StreamingSessionState,
        phase: GenerateTaskState.Phase,
        stage: GenerateTaskState.Stage,
        message: String,
        iteration: Int,
        report: WebInspectionReport,
        htmlFile: File,
        isModification: Boolean
    ) {
        val warnings = GeneratedAppHarnessScripts.diagnostics(report)
        updateHarnessStage(
            state = state,
            phase = phase,
            stage = stage,
            outcome = GenerateTaskState.Outcome.PASSED,
            message = "$message · ${report.durationMillis}ms",
            iteration = iteration,
            diagnostics = warnings,
            filePath = htmlFile.absolutePath,
            isModification = isModification
        )
    }

internal suspend fun ChatViewModel.completeGeneratedAppHarness(
        state: StreamingSessionState,
        htmlFile: File,
        code: String,
        isModification: Boolean,
        iteration: Int
    ) {
        updateGenerateTaskCode(
            state = state,
            code = code,
            phase = GenerateTaskState.Phase.COMPLETED,
            status = "构建、运行检查、自测与测试集均已通过",
            filePath = htmlFile.absolutePath,
            isModification = isModification,
            force = true
        )
        repository.markSessionAsGenerate(state.sessionId, htmlFile.absolutePath)
        removeStreamingPlaceholder(state)
        state.thinkingContent.clear()
        addFinalAssistantMessage(
            state = state,
            content = if (isModification) {
                "已完成增量修改，并通过构建、WebView 检查、自测和测试集，点击下方查看 👇"
            } else {
                "应用已生成，并通过构建、WebView 检查、自测和测试集，点击下方预览 👇"
            },
            appHtmlPath = htmlFile.absolutePath
        )
        _appGenerated.value = htmlFile.absolutePath
        updateHarnessStage(
            state = state,
            phase = GenerateTaskState.Phase.COMPLETED,
            stage = GenerateTaskState.Stage.DELIVER,
            outcome = GenerateTaskState.Outcome.PASSED,
            message = "全部 Harness 阶段通过，可以交付",
            iteration = iteration,
            filePath = htmlFile.absolutePath,
            isModification = isModification
        )
    }

internal suspend fun ChatViewModel.repairGeneratedAppFromDiagnostics(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        failedStage: String,
        diagnostics: List<String>,
        iteration: Int,
        isModification: Boolean
    ): String {
        val nextIteration = iteration + 1
        updateHarnessStage(
            state = state,
            phase = GenerateTaskState.Phase.REPAIRING,
            stage = GenerateTaskState.Stage.MODEL,
            outcome = GenerateTaskState.Outcome.RETRYING,
            message = "正在把 $failedStage 诊断返回模型修复",
            iteration = nextIteration,
            diagnostics = diagnostics,
            filePath = htmlFile.absolutePath,
            isModification = isModification
        )
        val diffTool = withContext(Dispatchers.IO) {
            LocalDiffFileTool(getApplication<Application>().filesDir)
        }
        var lastFailure: String? = null

        repeat(GENERATED_APP_REPAIR_PATCH_ATTEMPTS) { attempt ->
            val snapshot = withContext(Dispatchers.IO) { diffTool.readSnapshot(htmlFile) }
            val repairSystemPrompt = GenerationInputFilter.filterSystemPrompt(
                AppGenerator.buildModificationSystemPrompt(
                    context = getApplication(),
                    relativePath = snapshot.relativePath,
                    expectedSha256 = snapshot.sha256,
                    userMessage = userMessage,
                    includeTools = AppGenerator.modificationNeedsTools(userMessage)
                )
            )
            val request = ChatRequest(
                model = config.model,
                messages = listOf(
                    ApiMessage(
                        role = "system",
                        content = repairSystemPrompt.content
                    ),
                    ApiMessage(
                        role = ChatMessage.ROLE_USER,
                        content = AppGenerator.buildHarnessRepairUserPrompt(
                            userMessage = userMessage,
                            snapshot = snapshot,
                            failedStage = failedStage,
                            diagnostics = diagnostics + listOfNotNull(lastFailure),
                            iteration = nextIteration
                        )
                    )
                ),
                stream = true,
                maxTokens = AppGenerator.resolveAppDiffOutputLimit(
                    ContextLimitStore.getTokenLimit(getApplication())
                ),
                temperature = 0.15,
                topP = 0.9
            )
            val diffBuffer = StringBuilder()
            var streamFailure: String? = null

            trackedChatStream(state.sessionId, config, request)
                .catch { error ->
                    if (error is CancellationException) throw error
                    streamFailure = error.message ?: "模型修复请求失败"
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Content -> {
                            diffBuffer.append(event.text)
                            updateGenerateTaskCode(
                                state = state,
                                code = diffBuffer,
                                phase = GenerateTaskState.Phase.WRITING_DIFF,
                                status = "正在写入 Harness 修复 DIFF · ${diffBuffer.length} 字符",
                                filePath = htmlFile.absolutePath,
                                isModification = true
                            )
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> streamFailure = event.message
                        is StreamingApiService.StreamEvent.Error -> streamFailure = event.message
                        else -> Unit
                    }
                }
            if (streamFailure != null) {
                lastFailure = streamFailure
                if (attempt + 1 < GENERATED_APP_REPAIR_PATCH_ATTEMPTS) {
                    updateHarnessStage(
                        state = state,
                        phase = GenerateTaskState.Phase.REPAIRING,
                        stage = GenerateTaskState.Stage.MODEL,
                        outcome = GenerateTaskState.Outcome.RETRYING,
                        message = "模型修复请求中断，正在重试",
                        iteration = nextIteration,
                        diagnostics = listOf(streamFailure.orEmpty()),
                        filePath = htmlFile.absolutePath,
                        isModification = true
                    )
                }
                return@repeat
            }

            val unifiedDiff = AppGenerator.extractUnifiedDiff(diffBuffer.toString())
            if (unifiedDiff == null) {
                lastFailure = "模型没有返回合法 unified diff"
                return@repeat
            }
            val applyResult = runCatching {
                withContext(Dispatchers.IO) {
                    diffTool.apply(htmlFile, snapshot.sha256, unifiedDiff) { patchedContent ->
                        AppGenerator.validatePatchedWebApp(snapshot.content, patchedContent)
                    }
                }
            }
            if (applyResult.isSuccess) {
                val result = applyResult.getOrThrow()
                val updatedCode = withContext(Dispatchers.IO) { htmlFile.readText() }
                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.APPLYING_TOOL,
                    stage = GenerateTaskState.Stage.TOOL,
                    outcome = GenerateTaskState.Outcome.PASSED,
                    message = "修复工具已应用 ${result.hunkCount} 处修改，重新进入构建",
                    iteration = nextIteration,
                    filePath = htmlFile.absolutePath,
                    isModification = true
                )
                updateGenerateTaskCode(
                    state = state,
                    code = updatedCode,
                    phase = GenerateTaskState.Phase.BUILDING,
                    status = "修复已写入，正在重新构建",
                    filePath = htmlFile.absolutePath,
                    isModification = true,
                    force = true
                )
                return updatedCode
            }
            lastFailure = applyResult.exceptionOrNull()?.message ?: "修复补丁应用失败"
            if (attempt + 1 < GENERATED_APP_REPAIR_PATCH_ATTEMPTS) {
                updateHarnessStage(
                    state = state,
                    phase = GenerateTaskState.Phase.REPAIRING,
                    stage = GenerateTaskState.Stage.TOOL,
                    outcome = GenerateTaskState.Outcome.RETRYING,
                    message = "修复补丁无效，正在要求模型重算 DIFF",
                    iteration = nextIteration,
                    diagnostics = listOf(lastFailure.orEmpty()),
                    filePath = htmlFile.absolutePath,
                    isModification = true
                )
            }
        }
        throw IllegalStateException(lastFailure ?: "Harness 修复补丁应用失败")
    }

internal fun ChatViewModel.logGeneratedAppDiffFailure(
        state: StreamingSessionState,
        htmlFile: File,
        diffRetryCount: Int,
        diffCode: CharSequence,
        error: Exception
    ) {
        val response = diffCode.toString()
        Log.e(
            WEB_APP_DIFF_LOG_TAG,
            "Generated app diff failed: sessionId=${state.sessionId}, " +
                "attempt=${diffRetryCount + 1}, file=${htmlFile.absolutePath}, " +
                "responseChars=${response.length}",
            error
        )
        Log.e(
            WEB_APP_DIFF_LOG_TAG,
            "Model diff response preview:\n${buildDiffLogPreview(response)}"
        )
    }

internal fun ChatViewModel.logHarnessEvent(
    sessionId: Long,
    runId: String,
    event: GenerateTaskState.HarnessEvent
) = harnessTraceRecorder.logHarnessEvent(sessionId, runId, event)

internal fun ChatViewModel.createHarnessTraceLogger(
    sessionId: Long,
    expectedRunId: String
): HarnessTraceLogger = harnessTraceRecorder.createHarnessTraceLogger(sessionId, expectedRunId)

internal fun ChatViewModel.recordWebInspectionEvent(
    traceLogger: HarnessTraceLogger,
    runId: String,
    iteration: Int,
    event: WebInspectionEvent
) = harnessTraceRecorder.recordWebInspectionEvent(traceLogger, runId, iteration, event)

internal fun ChatViewModel.traceSha256(content: String): String = harnessTraceRecorder.traceSha256(content)

internal fun ChatViewModel.currentHarnessIteration(state: StreamingSessionState): Int =
    _generateTaskStates.value.orEmpty()[state.sessionId]?.harnessIteration ?: 0

internal fun ChatViewModel.buildDiffLogPreview(response: String): String {
        if (response.length <= APP_DIFF_LOG_PREVIEW_CHARS) return response
        val previewPartChars = APP_DIFF_LOG_PREVIEW_CHARS / 2
        return buildString(APP_DIFF_LOG_PREVIEW_CHARS + 80) {
            append(response.take(previewPartChars))
            append("\n… ${response.length - APP_DIFF_LOG_PREVIEW_CHARS} characters omitted …\n")
            append(response.takeLast(previewPartChars))
        }
    }

internal fun ChatViewModel.startAppGenerationProgressHeartbeat(
        state: StreamingSessionState,
        htmlBuffer: StringBuilder
    ) {
        stopAppGenerationProgressHeartbeat(state)
        state.appGenerationProgressJob = fallbackScope.launch(Dispatchers.Main.immediate) {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                if (state.thinkingContent.isBlank()) {
                    updateStreamingThinking(
                        state,
                        buildAppGenerationProgressText(
                            elapsedSeconds = elapsedSeconds,
                            receivedChars = htmlBuffer.length
                        )
                    )
                }
                state.harnessProgressReporter?.report(
                    message = if (htmlBuffer.isEmpty()) {
                        "模型处理中 · 已等待 ${elapsedSeconds}s"
                    } else {
                        "正在接收 HTML · ${htmlBuffer.length} 字符 · ${elapsedSeconds}s"
                    },
                    stage = GenerateTaskState.Stage.MODEL.name,
                    iteration = _generateTaskStates.value.orEmpty()[state.sessionId]
                        ?.harnessIteration
                        ?: 1,
                    progress = (25 + htmlBuffer.length / 1_000).coerceAtMost(39)
                )
                delay(appGenerationProgressIntervalMs)
            }
        }
    }

internal fun ChatViewModel.stopAppGenerationProgressHeartbeat(state: StreamingSessionState) {
        state.appGenerationProgressJob?.cancel()
        state.appGenerationProgressJob = null
    }

internal fun ChatViewModel.buildAppGenerationProgressText(
        elapsedSeconds: Int?,
        receivedChars: Int
    ): String {
        val elapsedLine = elapsedSeconds?.let { "已等待 ${it}s" } ?: "正在接收模型返回"
        val stageLine = when {
            receivedChars <= 0 -> "已提交生成请求，正在等待模型开始返回内容..."
            receivedChars < 2_000 -> "模型已开始返回内容，正在接收 HTML 代码..."
            receivedChars < 12_000 -> "正在持续接收页面结构、样式和交互代码..."
            else -> "已收到较多代码内容，正在等待模型完成收尾..."
        }
        val receivedLine = if (receivedChars > 0) {
            "已接收约 $receivedChars 个字符"
        } else {
            "如果模型没有思考过程，这里会持续显示生成状态"
        }
        return listOf(elapsedLine, stageLine, receivedLine).joinToString("\n")
    }
