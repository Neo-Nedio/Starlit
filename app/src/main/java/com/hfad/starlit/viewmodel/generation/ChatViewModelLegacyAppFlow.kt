package com.hfad.starlit.viewmodel.generation

import android.app.Application
import com.hfad.starlit.data.model.chat.ChatMessage
import com.hfad.starlit.data.model.chat.GenerateTaskState
import com.hfad.starlit.data.api.chat.model.ApiMessage
import com.hfad.starlit.data.api.chat.model.ChatCallConfig
import com.hfad.starlit.data.api.chat.model.ChatRequest
import com.hfad.starlit.data.api.chat.service.StreamingApiService
import com.hfad.starlit.data.preferences.chat.ContextLimitStore
import com.hfad.starlit.webapp.generation.AppGenerator
import com.hfad.starlit.webapp.generation.GenerationInputFilter
import com.hfad.starlit.webapp.generation.LocalDiffFileTool
import com.hfad.starlit.viewmodel.chat.StreamingSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import java.io.File
import com.hfad.starlit.viewmodel.ChatViewModel
import com.hfad.starlit.viewmodel.harness.*
import com.hfad.starlit.viewmodel.chat.*

internal suspend fun ChatViewModel.generateAppFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        qualityRetryCount: Int = 0,
        requestMessage: String = userMessage
    ) {
        val sessionId = state.sessionId
        val filteredInput = GenerationInputFilter.filter(userMessage)
        val filteredRequest = GenerationInputFilter.filter(requestMessage)
        val harnessIteration = qualityRetryCount + 1
        state.isGeneratingApp = true
        updateSessionLoadingIndicators()
        try {
            if (qualityRetryCount == 0) {
                replaceGenerateTaskState(
                    GenerateTaskState(
                        sessionId = state.sessionId,
                        phase = GenerateTaskState.Phase.SANITIZING,
                        status = "正在过滤生成需求"
                    )
                )
            }
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.SANITIZING,
                stage = GenerateTaskState.Stage.INPUT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = filteredInput.notices.firstOrNull() ?: "用户输入已过滤并建立安全边界",
                iteration = harnessIteration,
                diagnostics = filteredInput.notices
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.RUNNING,
                message = "正在合并 System Prompt、设备上下文和工具文档",
                iteration = harnessIteration
            )
            state.thinkingContent.clear()
            addStreamingPlaceholder(state, status = "正在生成应用", useLoadingLayout = true)
            updateStreamingThinking(
                state,
                buildAppGenerationProgressText(elapsedSeconds = 0, receivedChars = 0)
            )

            val generationSystemPrompt = AppGenerator.buildSystemPrompt(
                getApplication(),
                filteredInput.content
            )
            val filteredSystemPrompt = GenerationInputFilter.filterSystemPrompt(
                generationSystemPrompt
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = "System Prompt 与用户需求已完成组装",
                iteration = harnessIteration,
                diagnostics = filteredSystemPrompt.notices
            )
            val apiMessages = listOf(
                ApiMessage(
                    role = "system",
                    content = filteredSystemPrompt.content
                ),
                ApiMessage(role = ChatMessage.ROLE_USER, content = filteredRequest.promptPayload)
            )
            val request = ChatRequest(
                model = config.model,
                messages = apiMessages,
                stream = true,
                maxTokens = AppGenerator.resolveAppGenerationOutputLimit(
                    ContextLimitStore.getTokenLimit(getApplication())
                ),
                temperature = 0.45,
                topP = 0.9
            )

            val htmlBuffer = StringBuilder()
            startAppGenerationProgressHeartbeat(state, htmlBuffer)
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.REQUESTING_MODEL,
                stage = GenerateTaskState.Stage.MODEL,
                outcome = if (qualityRetryCount == 0) {
                    GenerateTaskState.Outcome.RUNNING
                } else {
                    GenerateTaskState.Outcome.RETRYING
                },
                message = if (qualityRetryCount == 0) {
                    "正在请求模型生成初版代码"
                } else {
                    "质量检查未通过，正在请求模型重新生成"
                },
                iteration = harnessIteration
            )

            trackedChatStream(state.sessionId, config, request)
                .catch { e ->
                    if (e is CancellationException) throw e
                    stopAppGenerationProgressHeartbeat(state)
                    markGenerateTaskError(state, e.message ?: "未知错误", htmlBuffer.toString())
                    handleApiError(state, config, e.message ?: "未知错误", "生成网页应用")
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Thinking -> {
                            state.thinkingContent.append(event.text)
                            updateStreamingThinking(state, state.thinkingContent.toString())
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            htmlBuffer.append(event.text)
                            updateGenerateTaskCode(
                                state = state,
                                code = htmlBuffer,
                                phase = GenerateTaskState.Phase.WRITING_INITIAL,
                                status = "正在写入 HTML · ${htmlBuffer.length} 字符"
                            )
                            if (state.thinkingContent.isBlank()) {
                                updateStreamingThinking(
                                    state,
                                    buildAppGenerationProgressText(
                                        elapsedSeconds = null,
                                        receivedChars = htmlBuffer.length
                                    )
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            stopAppGenerationProgressHeartbeat(state)
                            updateHarnessStage(
                                state = state,
                                phase = GenerateTaskState.Phase.BUILDING,
                                stage = GenerateTaskState.Stage.MODEL,
                                outcome = GenerateTaskState.Outcome.PASSED,
                                message = "模型已完成本轮代码输出",
                                iteration = harnessIteration
                            )
                            if (state.thinkingContent.isBlank()) {
                                updateStreamingThinking(
                                    state,
                                    "模型已返回代码内容\n正在整理 HTML 并写入本地文件..."
                                )
                            }
                            try {
                                when (val decision = withContext(Dispatchers.Default) {
                                    AppGenerator.decideInitialGeneration(
                                        modelOutput = htmlBuffer.toString(),
                                        attempt = harnessIteration
                                    )
                                }) {
                                    is AppGenerator.InitialGenerationDecision.Retry -> {
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.BUILDING,
                                            stage = GenerateTaskState.Stage.BUILD,
                                            outcome = GenerateTaskState.Outcome.FAILED,
                                            message = "初始代码未达到可检查条件，准备受控重试",
                                            iteration = harnessIteration,
                                            diagnostics = decision.diagnostics
                                        )
                                        updateGenerateTaskCode(
                                            state = state,
                                            code = htmlBuffer.toString(),
                                            phase = GenerateTaskState.Phase.PREPARING,
                                            status = "初始代码不完整，准备第 ${harnessIteration + 1} 次生成",
                                            force = true
                                        )
                                        updateStreamingThinking(
                                            state,
                                            "初始代码未达到 Harness 可检查条件，正在要求模型从头重做...",
                                            force = true
                                        )
                                        generateAppFlow(
                                            state = state,
                                            config = config,
                                            userMessage = userMessage,
                                            qualityRetryCount = qualityRetryCount + 1,
                                            requestMessage = AppGenerator.buildQualityRetryUserPrompt(
                                                userMessage,
                                                decision.diagnostics
                                            )
                                        )
                                        return@collect
                                    }

                                    is AppGenerator.InitialGenerationDecision.Fail -> {
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.BUILDING,
                                            stage = GenerateTaskState.Stage.BUILD,
                                            outcome = GenerateTaskState.Outcome.FAILED,
                                            message = decision.reason,
                                            iteration = harnessIteration,
                                            diagnostics = decision.diagnostics
                                        )
                                        markGenerateTaskError(
                                            state,
                                            decision.reason,
                                            htmlBuffer.toString()
                                        )
                                        handleApiError(
                                            state,
                                            config,
                                            decision.reason,
                                            "生成网页应用"
                                        )
                                    }

                                    is AppGenerator.InitialGenerationDecision.RunHarness -> {
                                        val htmlContent = decision.html
                                        val hasQualityIssues = decision.diagnostics.isNotEmpty()
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.BUILDING,
                                            stage = GenerateTaskState.Stage.BUILD,
                                            outcome = if (hasQualityIssues) {
                                                GenerateTaskState.Outcome.RETRYING
                                            } else {
                                                GenerateTaskState.Outcome.PASSED
                                            },
                                            message = if (hasQualityIssues) {
                                                "候选 HTML 已提取，交由 Harness 诊断并修复"
                                            } else {
                                                "HTML、CSS 与 JavaScript 预构建检查通过"
                                            },
                                            iteration = harnessIteration,
                                            diagnostics = decision.diagnostics
                                        )
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                            stage = GenerateTaskState.Stage.TOOL,
                                            outcome = GenerateTaskState.Outcome.RUNNING,
                                            message = "正在调用本地文件工具写入代码",
                                            iteration = harnessIteration
                                        )
                                        val file = withContext(Dispatchers.IO) {
                                            AppGenerator.saveHtmlFile(
                                                getApplication(),
                                                htmlContent,
                                                filteredInput.content
                                            )
                                        }
                                        val savedCode = withContext(Dispatchers.IO) { file.readText() }
                                        repository.markSessionAsGenerate(sessionId, file.absolutePath)
                                        updateGenerateTaskCode(
                                            state = state,
                                            code = savedCode,
                                            phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                            status = "代码工具已写入 ${file.name}",
                                            filePath = file.absolutePath,
                                            force = true
                                        )
                                        updateHarnessStage(
                                            state = state,
                                            phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                            stage = GenerateTaskState.Stage.TOOL,
                                            outcome = GenerateTaskState.Outcome.PASSED,
                                            message = "本地代码文件已安全写入",
                                            iteration = harnessIteration,
                                            filePath = file.absolutePath
                                        )
                                        runGeneratedAppHarness(
                                            state = state,
                                            config = config,
                                            userMessage = filteredInput.content,
                                            htmlFile = file,
                                            isModification = false,
                                            startingIteration = harnessIteration,
                                            initialCode = savedCode
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                markGenerateTaskError(
                                    state,
                                    e.message ?: "保存 HTML 时出错",
                                    htmlBuffer.toString()
                                )
                                handleApiError(
                                    state,
                                    config,
                                    e.message ?: "保存 HTML 时出错",
                                    "生成网页应用"
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            stopAppGenerationProgressHeartbeat(state)
                            markGenerateTaskError(state, event.message, htmlBuffer.toString())
                            handleApiError(state, config, event.message, "生成网页应用")
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            stopAppGenerationProgressHeartbeat(state)
                            markGenerateTaskError(state, event.message, htmlBuffer.toString())
                            handleApiError(state, config, event.message, "生成网页应用")
                        }

                        is StreamingApiService.StreamEvent.Start -> Unit
                    }
                }
        } finally {
            stopAppGenerationProgressHeartbeat(state)
            state.isGeneratingApp = false
            updateSessionLoadingIndicators()
        }
    }

internal suspend fun ChatViewModel.generateAppDiffFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        diffRetryCount: Int = 0,
        previousFailure: String? = null
    ) {
        val sessionId = state.sessionId
        val filteredInput = GenerationInputFilter.filter(userMessage)
        val harnessIteration = diffRetryCount + 1
        state.isGeneratingApp = true
        updateSessionLoadingIndicators()
        val diffBuffer = StringBuilder()

        try {
            val diffTool = withContext(Dispatchers.IO) {
                LocalDiffFileTool(getApplication<Application>().filesDir)
            }
            val snapshot = withContext(Dispatchers.IO) { diffTool.readSnapshot(htmlFile) }
            val initialTaskState =
                GenerateTaskState(
                    sessionId = sessionId,
                    phase = GenerateTaskState.Phase.SANITIZING,
                    code = snapshot.content,
                    filePath = htmlFile.absolutePath,
                    status = if (diffRetryCount == 0) "正在过滤修改需求" else "正在重新过滤修复需求",
                    isModification = true,
                    harnessIteration = harnessIteration
                )
            if (diffRetryCount == 0) {
                replaceGenerateTaskState(initialTaskState)
            } else {
                updateGenerateTaskState(initialTaskState)
            }
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.SANITIZING,
                stage = GenerateTaskState.Stage.INPUT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = "修改需求已过滤并建立安全边界",
                iteration = harnessIteration,
                diagnostics = filteredInput.notices,
                filePath = htmlFile.absolutePath,
                isModification = true
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.RUNNING,
                message = "正在组装当前文件上下文和修复提示词",
                iteration = harnessIteration,
                filePath = htmlFile.absolutePath,
                isModification = true
            )

            state.thinkingContent.clear()
            addStreamingPlaceholder(
                state,
                status = if (diffRetryCount == 0) "正在修改应用" else "正在修复补丁格式",
                useLoadingLayout = true
            )
            updateStreamingThinking(
                state,
                if (diffRetryCount == 0) {
                    "已读取当前 HTML，正在生成最小化 diff..."
                } else {
                    "上一份补丁格式不合法，正在基于原文件重新生成规范 unified diff..."
                },
                force = true
            )

            val modificationSystemPrompt = GenerationInputFilter.filterSystemPrompt(
                AppGenerator.buildModificationSystemPrompt(
                    context = getApplication(),
                    relativePath = snapshot.relativePath,
                    expectedSha256 = snapshot.sha256,
                    userMessage = filteredInput.content,
                    includeTools = AppGenerator.modificationNeedsTools(filteredInput.content)
                )
            )
            val request = ChatRequest(
                model = config.model,
                messages = listOf(
                    ApiMessage(
                        role = "system",
                        content = modificationSystemPrompt.content
                    ),
                    ApiMessage(
                        role = ChatMessage.ROLE_USER,
                        content = AppGenerator.buildModificationUserPrompt(
                            filteredInput.content,
                            snapshot,
                            previousFailure
                        )
                    )
                ),
                stream = true,
                maxTokens = AppGenerator.resolveAppDiffOutputLimit(
                    ContextLimitStore.getTokenLimit(getApplication())
                ),
                temperature = 0.2,
                topP = 0.9
            )

            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.PROMPTING,
                stage = GenerateTaskState.Stage.PROMPT,
                outcome = GenerateTaskState.Outcome.PASSED,
                message = "修复提示词已就绪",
                iteration = harnessIteration,
                diagnostics = modificationSystemPrompt.notices,
                filePath = htmlFile.absolutePath,
                isModification = true
            )
            updateHarnessStage(
                state = state,
                phase = GenerateTaskState.Phase.REQUESTING_MODEL,
                stage = GenerateTaskState.Stage.MODEL,
                outcome = if (diffRetryCount == 0) {
                    GenerateTaskState.Outcome.RUNNING
                } else {
                    GenerateTaskState.Outcome.RETRYING
                },
                message = if (diffRetryCount == 0) "正在请求模型生成代码补丁" else "正在请求模型重算修复补丁",
                iteration = harnessIteration,
                filePath = htmlFile.absolutePath,
                isModification = true
            )

            trackedChatStream(state.sessionId, config, request)
                .catch { error ->
                    if (error is CancellationException) throw error
                    val message = error.message ?: "生成 diff 时出错"
                    if (StreamingApiService.isRetryableNetworkFailure(message) &&
                        retryAppDiffFlow(
                            state = state,
                            config = config,
                            userMessage = userMessage,
                            htmlFile = htmlFile,
                            diffRetryCount = diffRetryCount,
                            failure = message,
                            diffCode = diffBuffer,
                            status = "网络连接超时，准备自动重试"
                        )
                    ) {
                        return@catch
                    }
                    markGenerateTaskError(
                        state,
                        message,
                        diffBuffer.toString(),
                        htmlFile.absolutePath,
                        isModification = true
                    )
                    handleApiError(state, config, message, "增量修改网页应用")
                }
                .collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Thinking -> {
                            state.thinkingContent.append(event.text)
                            updateStreamingThinking(state, state.thinkingContent.toString())
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            diffBuffer.append(event.text)
                            updateGenerateTaskCode(
                                state = state,
                                code = diffBuffer,
                                phase = GenerateTaskState.Phase.WRITING_DIFF,
                                status = "正在写入 DIFF · ${diffBuffer.length} 字符",
                                filePath = htmlFile.absolutePath,
                                isModification = true
                            )
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            updateHarnessStage(
                                state = state,
                                phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                stage = GenerateTaskState.Stage.MODEL,
                                outcome = GenerateTaskState.Outcome.PASSED,
                                message = "模型已完成本轮 DIFF 输出",
                                iteration = harnessIteration,
                                filePath = htmlFile.absolutePath,
                                isModification = true
                            )
                            updateGenerateTaskCode(
                                state = state,
                                code = diffBuffer.toString(),
                                phase = GenerateTaskState.Phase.APPLYING_DIFF,
                                status = "正在校验并应用 DIFF",
                                filePath = htmlFile.absolutePath,
                                isModification = true,
                                force = true
                            )
                            try {
                                val unifiedDiff = AppGenerator.extractUnifiedDiff(diffBuffer.toString())
                                    ?: throw LocalDiffFileTool.DiffException("模型没有返回合法 unified diff")
                                val applyResult = withContext(Dispatchers.IO) {
                                    diffTool.apply(htmlFile, snapshot.sha256, unifiedDiff) { patchedContent ->
                                        AppGenerator.validatePatchedWebApp(snapshot.content, patchedContent)
                                    }
                                }
                                val updatedCode = withContext(Dispatchers.IO) { htmlFile.readText() }
                                repository.markSessionAsGenerate(sessionId, htmlFile.absolutePath)
                                updateGenerateTaskCode(
                                    state = state,
                                    code = updatedCode,
                                    phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                    status = "代码工具已应用 ${applyResult.hunkCount} 处修改 · +${applyResult.additions} -${applyResult.deletions}",
                                    filePath = htmlFile.absolutePath,
                                    isModification = true,
                                    force = true
                                )
                                updateHarnessStage(
                                    state,
                                    phase = GenerateTaskState.Phase.APPLYING_TOOL,
                                    stage = GenerateTaskState.Stage.TOOL,
                                    outcome = GenerateTaskState.Outcome.PASSED,
                                    message = "本地 diff 工具已应用修改",
                                    iteration = harnessIteration,
                                    filePath = htmlFile.absolutePath,
                                    isModification = true
                                )
                                runGeneratedAppHarness(
                                    state = state,
                                    config = config,
                                    userMessage = filteredInput.content,
                                    htmlFile = htmlFile,
                                    isModification = true,
                                    startingIteration = harnessIteration,
                                    initialCode = updatedCode
                                )
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                logGeneratedAppDiffFailure(
                                    state = state,
                                    htmlFile = htmlFile,
                                    diffRetryCount = diffRetryCount,
                                    diffCode = diffBuffer,
                                    error = error
                                )
                                if (error is LocalDiffFileTool.DiffException &&
                                    retryAppDiffFlow(
                                        state = state,
                                        config = config,
                                        userMessage = userMessage,
                                        htmlFile = htmlFile,
                                        diffRetryCount = diffRetryCount,
                                        failure = error.message ?: "补丁格式不合法",
                                        diffCode = diffBuffer,
                                        status = "补丁格式或上下文无效，准备自动重试"
                                    )
                                ) {
                                    return@collect
                                }
                                markGenerateTaskError(
                                    state,
                                    error.message ?: "应用 diff 时出错",
                                    diffBuffer.toString(),
                                    htmlFile.absolutePath,
                                    isModification = true
                                )
                                handleApiError(
                                    state,
                                    config,
                                    error.message ?: "应用 diff 时出错",
                                    "增量修改网页应用"
                                )
                            }
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            if (retryAppDiffFlow(
                                    state = state,
                                    config = config,
                                    userMessage = userMessage,
                                    htmlFile = htmlFile,
                                    diffRetryCount = diffRetryCount,
                                    failure = event.message,
                                    diffCode = diffBuffer,
                                    status = "网络连接中断，准备自动重试"
                                )
                            ) {
                                return@collect
                            }
                            markGenerateTaskError(
                                state,
                                event.message,
                                diffBuffer.toString(),
                                htmlFile.absolutePath,
                                isModification = true
                            )
                            handleApiError(state, config, event.message, "增量修改网页应用")
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            if (StreamingApiService.isRetryableNetworkFailure(event.message) &&
                                retryAppDiffFlow(
                                    state = state,
                                    config = config,
                                    userMessage = userMessage,
                                    htmlFile = htmlFile,
                                    diffRetryCount = diffRetryCount,
                                    failure = event.message,
                                    diffCode = diffBuffer,
                                    status = "网络连接超时，准备自动重试"
                                )
                            ) {
                                return@collect
                            }
                            markGenerateTaskError(
                                state,
                                event.message,
                                diffBuffer.toString(),
                                htmlFile.absolutePath,
                                isModification = true
                            )
                            handleApiError(state, config, event.message, "增量修改网页应用")
                        }

                        is StreamingApiService.StreamEvent.Start -> Unit
                    }
                }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val currentTask = _generateTaskStates.value.orEmpty()[sessionId]
            if (currentTask?.phase != GenerateTaskState.Phase.ERROR) {
                markGenerateTaskError(
                    state,
                    error.message ?: "无法读取现有应用",
                    currentTask?.code?.ifBlank { null } ?: diffBuffer.toString(),
                    htmlFile.absolutePath,
                    isModification = true
                )
            }
            handleApiError(state, config, error.message ?: "无法读取现有应用", "增量修改网页应用")
        } finally {
            state.isGeneratingApp = false
            updateSessionLoadingIndicators()
        }
    }

internal suspend fun ChatViewModel.retryAppDiffFlow(
        state: StreamingSessionState,
        config: ChatCallConfig,
        userMessage: String,
        htmlFile: File,
        diffRetryCount: Int,
        failure: String,
        diffCode: CharSequence,
        status: String
    ): Boolean {
        if (diffRetryCount != 0) return false
        updateGenerateTaskCode(
            state = state,
            code = diffCode,
            phase = GenerateTaskState.Phase.PREPARING,
            status = status,
            filePath = htmlFile.absolutePath,
            isModification = true,
            force = true
        )
        generateAppDiffFlow(
            state = state,
            config = config,
            userMessage = userMessage,
            htmlFile = htmlFile,
            diffRetryCount = 1,
            previousFailure = failure
        )
        return true
    }
