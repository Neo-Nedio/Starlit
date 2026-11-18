package com.hfad.starlit.webapp.project

import com.hfad.starlit.data.model.chat.ChatMessage
import com.hfad.starlit.data.api.chat.model.ApiMessage
import com.hfad.starlit.data.api.chat.model.ChatCallConfig
import com.hfad.starlit.data.api.chat.model.ChatRequest
import com.hfad.starlit.data.api.chat.service.StreamingApiService
import com.hfad.starlit.data.logging.api.ApiDiagnosticContext
import com.hfad.starlit.data.logging.harness.HarnessTraceLogger
import com.hfad.starlit.data.logging.harness.HarnessTraceStatus
import com.hfad.starlit.data.logging.harness.elapsedMillisSince
import com.hfad.starlit.data.logging.harness.record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.security.MessageDigest

data class WebProjectPlanningResult(
    val manifest: WebAppProjectManifest,
    val projectPlanJson: String,
    val appSpec: WebAppSpec? = manifest.appSpec
) {
    fun requireAppSpec(): WebAppSpec = appSpec
        ?: throw WebAppProjectException("项目计划缺少结构化 appSpec")
}

private class PlannerOutputBudgetExceededException(
    val finishReason: String,
    val maxTokens: Int,
    val partialResponseChars: Int
) : IllegalStateException(
    "模型计划正文预算已耗尽：finish_reason=$finishReason，max_tokens=$maxTokens，" +
        "已接收正文 $partialResponseChars 个字符；正文可能为空或已截断。"
) {
    fun traceDetails(): Map<String, String> = mapOf(
        "finish_reason" to finishReason,
        "max_tokens" to maxTokens.toString(),
        "output_budget_exhausted" to "true",
        "partial_response_chars" to partialResponseChars.toString()
    )
}

private fun String?.isOutputBudgetExhausted(): Boolean =
    equals("length", ignoreCase = true) ||
        equals("max_tokens", ignoreCase = true) ||
        equals("max_output_tokens", ignoreCase = true)

class StreamingWebProjectPlanner(
    private val config: ChatCallConfig,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val onThinking: suspend (String) -> Unit = {},
    private val onProgress: suspend (Int) -> Unit = {},
    private val onStatus: suspend (String) -> Unit = {},
    private val traceLogger: HarnessTraceLogger = HarnessTraceLogger {},
    private val streamChatCompletion: (
        ChatCallConfig,
        ChatRequest
    ) -> Flow<StreamingApiService.StreamEvent> = StreamingApiService::streamChatCompletion
) {

    init {
        require(maxTokens > 0)
    }

    suspend fun plan(
        userRequirement: String,
        runId: String? = null
    ): WebProjectPlanningResult {
        val startedAt = System.nanoTime()
        val diagnosticRunId = runId?.trim()?.takeIf(String::isNotEmpty)
        val traceRunId = diagnosticRunId ?: UNCORRELATED_RUN_ID
        var iteration = 0
        var phase = PlannerPhase.STREAM
        var completed = false
        var firstPayloadRecorded = false
        var contentChunks = 0
        var thinkingChunks = 0
        var thinkingChars = 0
        var finishReason: String? = null
        var previousOutputBudgetExhaustion: PlannerOutputBudgetExceededException? = null
        var failureDetails = emptyMap<String, String>()
        var response = StringBuilder()

        traceLogger.record(
            runId = traceRunId,
            component = "PLANNER",
            operation = "plan",
            status = HarnessTraceStatus.STARTED,
            message = "项目规划请求开始",
            iteration = 0,
            details = mapOf(
                "model" to config.model,
                "api_format" to config.apiFormat,
                "max_tokens" to maxTokens.toString(),
                "requirement_chars" to userRequirement.length.toString(),
                "requirement_sha256" to sha256(userRequirement),
                "system_prompt_chars" to PLANNING_PROMPT.length.toString(),
                "system_prompt_sha256" to sha256(PLANNING_PROMPT)
            )
        )

        val initialMessages = listOf(
            ApiMessage("system", PLANNING_PROMPT),
            ApiMessage(ChatMessage.ROLE_USER, userRequirement)
        )
        var request = planningRequest(
            messages = initialMessages,
            diagnosticRunId = diagnosticRunId,
            iteration = iteration,
            finishReasonListener = { reason -> finishReason = reason }
        )

        try {
            while (true) {
                phase = PlannerPhase.STREAM
                completed = false
                firstPayloadRecorded = false
                contentChunks = 0
                thinkingChunks = 0
                thinkingChars = 0
                finishReason = null
                failureDetails = emptyMap()
                response = StringBuilder()

                streamChatCompletion(config, request).collect { event ->
                    when (event) {
                        is StreamingApiService.StreamEvent.Start -> {
                            traceLogger.record(
                                runId = traceRunId,
                                component = "PLANNER_STREAM",
                                operation = "upstream_started",
                                status = HarnessTraceStatus.PROGRESS,
                                message = "项目规划上游流已建立",
                                iteration = iteration,
                                durationMs = elapsedMillisSince(startedAt)
                            )
                        }

                        is StreamingApiService.StreamEvent.Thinking -> {
                            thinkingChunks++
                            thinkingChars += event.text.length
                            if (!firstPayloadRecorded) {
                                firstPayloadRecorded = true
                                traceLogger.record(
                                    runId = traceRunId,
                                    component = "PLANNER_STREAM",
                                    operation = "first_payload",
                                    status = HarnessTraceStatus.PROGRESS,
                                    message = "收到项目规划首个流片段",
                                    iteration = iteration,
                                    durationMs = elapsedMillisSince(startedAt),
                                    details = mapOf("payload_type" to "thinking")
                                )
                            }
                            onThinking(event.text)
                        }

                        is StreamingApiService.StreamEvent.Content -> {
                            contentChunks++
                            if (!firstPayloadRecorded) {
                                firstPayloadRecorded = true
                                traceLogger.record(
                                    runId = traceRunId,
                                    component = "PLANNER_STREAM",
                                    operation = "first_payload",
                                    status = HarnessTraceStatus.PROGRESS,
                                    message = "收到项目规划首个流片段",
                                    iteration = iteration,
                                    durationMs = elapsedMillisSince(startedAt),
                                    details = mapOf("payload_type" to "content")
                                )
                            }
                            response.append(event.text)
                            onProgress(response.length)
                        }

                        is StreamingApiService.StreamEvent.Done -> {
                            completed = true
                            traceLogger.record(
                                runId = traceRunId,
                                component = "PLANNER_STREAM",
                                operation = "stream_complete",
                                status = HarnessTraceStatus.SUCCEEDED,
                                message = if (finishReason.isOutputBudgetExhausted()) {
                                    "模型输出预算已耗尽，项目计划可能被截断"
                                } else {
                                    "项目规划流接收完成"
                                },
                                iteration = iteration,
                                durationMs = elapsedMillisSince(startedAt),
                                details = responseDetails(
                                    response = response,
                                    contentChunks = contentChunks,
                                    thinkingChunks = thinkingChunks,
                                    thinkingChars = thinkingChars
                                ) + mapOf(
                                    "finish_reason" to finishReason.orEmpty(),
                                    "output_budget_exhausted" to finishReason
                                        .isOutputBudgetExhausted().toString()
                                )
                            )
                        }

                        is StreamingApiService.StreamEvent.Disconnected -> {
                            failureDetails = buildMap {
                                put("stream_event", "disconnected")
                                event.diagnosticId?.let { put("diagnostic_id", it) }
                            }
                            throw WebAppProjectException(event.message)
                        }

                        is StreamingApiService.StreamEvent.Error -> {
                            failureDetails = buildMap {
                                put("stream_event", "error")
                                put("retryable", event.retryable.toString())
                                event.httpStatus?.let { put("http_status", it.toString()) }
                                event.diagnosticId?.let { put("diagnostic_id", it) }
                                event.upstreamRequestId?.let { put("upstream_request_id", it) }
                            }
                            throw WebAppProjectException(event.message)
                        }
                    }
                }
                if (!completed) throw WebAppProjectException("项目规划请求在返回完整计划前结束")

                val outputBudgetExhausted = finishReason.isOutputBudgetExhausted()
                phase = if (outputBudgetExhausted) {
                    PlannerPhase.OUTPUT_BUDGET
                } else {
                    PlannerPhase.PARSE
                }
                if (outputBudgetExhausted) {
                    traceLogger.record(
                        runId = traceRunId,
                        component = "PLANNER_STREAM",
                        operation = "output_budget_exhausted",
                        status = HarnessTraceStatus.FAILED,
                        message = "模型输出预算已耗尽，项目计划正文可能为空或被截断",
                        iteration = iteration,
                        durationMs = elapsedMillisSince(startedAt),
                        details = responseDetails(
                            response = response,
                            contentChunks = contentChunks,
                            thinkingChunks = thinkingChunks,
                            thinkingChars = thinkingChars
                        ) + mapOf(
                            "finish_reason" to finishReason.orEmpty(),
                            "max_tokens" to maxTokens.toString(),
                            "output_budget_exhausted" to "true"
                        )
                    )
                } else {
                    traceLogger.record(
                        runId = traceRunId,
                        component = "PLANNER_PARSE",
                        operation = "parse_plan",
                        status = HarnessTraceStatus.STARTED,
                        message = "开始解析项目规划",
                        iteration = iteration,
                        details = responseDetails(
                            response = response,
                            contentChunks = contentChunks,
                            thinkingChunks = thinkingChunks,
                            thinkingChars = thinkingChars
                        )
                    )
                }
                val result = try {
                    if (finishReason.isOutputBudgetExhausted()) {
                        throw PlannerOutputBudgetExceededException(
                            finishReason = requireNotNull(finishReason),
                            maxTokens = maxTokens,
                            partialResponseChars = response.length
                        )
                    }
                    WebProjectPlanCodec.parse(response.toString(), requireAppSpec = true)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val diagnostics = repairDiagnostics(error)
                    if (error is PlannerOutputBudgetExceededException &&
                        previousOutputBudgetExhaustion == null
                    ) {
                        previousOutputBudgetExhaustion = error
                    }
                    failureDetails = diagnosticTraceDetails(diagnostics) +
                        previousOutputBudgetExhaustion
                            ?.traceDetails()
                            ?.mapKeys { (key, _) -> "first_attempt_$key" }
                            .orEmpty() +
                        (error as? PlannerOutputBudgetExceededException)?.traceDetails().orEmpty()
                    val parseErrorDetails = responseDetails(
                        response = response,
                        contentChunks = contentChunks,
                        thinkingChunks = thinkingChunks,
                        thinkingChars = thinkingChars
                    ) + failureDetails + mapOf(
                        "phase" to phase.logValue,
                        "stream_completed" to completed.toString(),
                        "error_type" to error::class.java.simpleName,
                        "attempt_count" to (iteration + 1).toString()
                    )
                    traceLogger.record(
                        runId = traceRunId,
                        component = if (error is PlannerOutputBudgetExceededException) {
                            "PLANNER_STREAM"
                        } else {
                            "PLANNER_PARSE"
                        },
                        operation = if (error is PlannerOutputBudgetExceededException) {
                            "output_budget_exhausted"
                        } else {
                            "parse_plan"
                        },
                        status = HarnessTraceStatus.FAILED,
                        message = when {
                            error is PlannerOutputBudgetExceededException -> "项目计划输出预算已耗尽"
                            previousOutputBudgetExhaustion != null -> "输出预算耗尽后的精简重试仍未通过解析"
                            else -> "项目规划解析失败"
                        },
                        iteration = iteration,
                        durationMs = elapsedMillisSince(startedAt),
                        details = parseErrorDetails
                    )
                    if (iteration >= MAX_REPAIR_ATTEMPTS) {
                        if (iteration > 0) {
                            traceLogger.record(
                                runId = traceRunId,
                                component = "PLANNER_REPAIR",
                                operation = "repair_plan",
                                status = HarnessTraceStatus.FAILED,
                                message = when {
                                    error is PlannerOutputBudgetExceededException ->
                                        "精简计划重试再次耗尽输出预算"
                                    previousOutputBudgetExhaustion != null ->
                                        "输出预算耗尽后的精简重试仍未生成有效计划"
                                    else -> "项目规划修复未通过严格校验"
                                },
                                iteration = iteration,
                                durationMs = elapsedMillisSince(startedAt),
                                details = parseErrorDetails
                            )
                        }
                        val exhaustedBudget = previousOutputBudgetExhaustion
                        if (exhaustedBudget != null &&
                            error !is PlannerOutputBudgetExceededException
                        ) {
                            failureDetails = failureDetails + exhaustedBudget.traceDetails()
                            throw WebAppProjectException(
                                "项目计划输出预算已耗尽（finish_reason=${exhaustedBudget.finishReason}，" +
                                    "max_tokens=${exhaustedBudget.maxTokens}）；已尝试用精简计划重试，" +
                                    "但仍未得到有效计划：${error.message ?: "解析失败"}",
                                error
                            )
                        }
                        throw error
                    }

                    val nextIteration = iteration + 1
                    val isOutputBudgetRetry = error is PlannerOutputBudgetExceededException
                    if (error is PlannerOutputBudgetExceededException) {
                        onStatus(
                            "项目计划输出预算已耗尽（finish_reason=${error.finishReason}，" +
                                "上限 ${error.maxTokens} tokens），正在用精简计划重试"
                        )
                    }
                    request = planningRequest(
                        messages = if (isOutputBudgetRetry) {
                            compactRetryMessages(userRequirement, diagnostics)
                        } else if (response.isBlank()) {
                            listOf(
                                initialMessages.first(),
                                ApiMessage(
                                    ChatMessage.ROLE_USER,
                                    "$userRequirement\n\n${repairFeedback(diagnostics)}"
                                )
                            )
                        } else {
                            initialMessages + buildList {
                                if (response.isNotBlank()) {
                                    add(ApiMessage(ChatMessage.ROLE_ASSISTANT, response.toString()))
                                }
                                add(
                                    ApiMessage(
                                        ChatMessage.ROLE_USER,
                                        repairFeedback(diagnostics)
                                    )
                                )
                            }
                        },
                        diagnosticRunId = diagnosticRunId,
                        iteration = nextIteration,
                        finishReasonListener = { reason -> finishReason = reason }
                    )
                    traceLogger.record(
                        runId = traceRunId,
                        component = "PLANNER_REPAIR",
                        operation = "repair_plan",
                        status = HarnessTraceStatus.STARTED,
                        message = if (isOutputBudgetRetry) {
                            "模型输出预算已耗尽，开始精简计划重试"
                        } else {
                            "项目规划未通过严格校验，开始有界修复"
                        },
                        iteration = nextIteration,
                        durationMs = elapsedMillisSince(startedAt),
                        details = failureDetails + mapOf(
                            "previous_iteration" to iteration.toString(),
                            "max_repair_attempts" to MAX_REPAIR_ATTEMPTS.toString()
                        )
                    )
                    iteration = nextIteration
                    continue
                }
                val parseDetails = responseDetails(
                    response = response,
                    contentChunks = contentChunks,
                    thinkingChunks = thinkingChunks,
                    thinkingChars = thinkingChars
                ) + mapOf(
                    "project_id" to result.manifest.projectId,
                    "entry_point" to result.manifest.entryPoint,
                    "file_count" to result.manifest.files.size.toString(),
                    "app_spec_version" to result.requireAppSpec().specVersion.toString(),
                    "acceptance_criteria_count" to result.requireAppSpec()
                        .acceptanceContract.criteria.size.toString(),
                    "display_name_chars" to result.manifest.displayName.length.toString(),
                    "display_name_sha256" to sha256(result.manifest.displayName),
                    "attempt_count" to (iteration + 1).toString()
                )
                traceLogger.record(
                    runId = traceRunId,
                    component = "PLANNER_PARSE",
                    operation = "parse_plan",
                    status = HarnessTraceStatus.SUCCEEDED,
                    message = "项目规划解析完成",
                    iteration = iteration,
                    durationMs = elapsedMillisSince(startedAt),
                    details = parseDetails
                )
                if (iteration > 0) {
                    traceLogger.record(
                        runId = traceRunId,
                        component = "PLANNER_REPAIR",
                        operation = "repair_plan",
                        status = HarnessTraceStatus.SUCCEEDED,
                        message = "项目规划修复通过严格校验",
                        iteration = iteration,
                        durationMs = elapsedMillisSince(startedAt),
                        details = parseDetails
                    )
                }
                traceLogger.record(
                    runId = traceRunId,
                    component = "PLANNER",
                    operation = "plan",
                    status = HarnessTraceStatus.SUCCEEDED,
                    message = "项目规划完成",
                    iteration = iteration,
                    durationMs = elapsedMillisSince(startedAt),
                    details = parseDetails
                )
                return result
            }
        } catch (error: CancellationException) {
            traceLogger.record(
                runId = traceRunId,
                component = "PLANNER",
                operation = "plan",
                status = HarnessTraceStatus.CANCELLED,
                message = "项目规划已取消",
                iteration = iteration,
                durationMs = elapsedMillisSince(startedAt),
                details = responseDetails(
                    response = response,
                    contentChunks = contentChunks,
                    thinkingChunks = thinkingChunks,
                    thinkingChars = thinkingChars
                ) + mapOf(
                    "phase" to phase.logValue,
                    "attempt_count" to (iteration + 1).toString()
                )
            )
            throw error
        } catch (error: Exception) {
            val priorBudgetDetails = previousOutputBudgetExhaustion
                ?.traceDetails()
                ?.mapKeys { (key, _) -> "first_attempt_$key" }
                .orEmpty()
            val errorDetails = responseDetails(
                response = response,
                contentChunks = contentChunks,
                thinkingChunks = thinkingChunks,
                thinkingChars = thinkingChars
            ) + priorBudgetDetails + failureDetails + mapOf(
                "phase" to phase.logValue,
                "stream_completed" to completed.toString(),
                "error_type" to error::class.java.simpleName,
                "attempt_count" to (iteration + 1).toString()
            )
            traceLogger.record(
                runId = traceRunId,
                component = "PLANNER",
                operation = "plan",
                status = HarnessTraceStatus.FAILED,
                message = when {
                    phase == PlannerPhase.OUTPUT_BUDGET -> "项目规划输出预算耗尽"
                    phase == PlannerPhase.PARSE && iteration > 0 && previousOutputBudgetExhaustion != null ->
                        "输出预算耗尽后的精简重试失败"
                    phase == PlannerPhase.STREAM && iteration > 0 && previousOutputBudgetExhaustion != null ->
                        "输出预算耗尽后的精简重试请求失败"
                    phase == PlannerPhase.PARSE -> "项目规划解析失败"
                    else -> "项目规划请求失败"
                },
                iteration = iteration,
                durationMs = elapsedMillisSince(startedAt),
                details = errorDetails
            )
            val exhaustedBudget = previousOutputBudgetExhaustion
            if (exhaustedBudget != null &&
                iteration > 0 &&
                error !is PlannerOutputBudgetExceededException &&
                error.message?.contains("预算已耗尽") != true
            ) {
                throw WebAppProjectException(
                    "项目计划输出预算已耗尽（finish_reason=${exhaustedBudget.finishReason}，" +
                        "max_tokens=${exhaustedBudget.maxTokens}）；精简重试仍失败：" +
                        (error.message ?: "请求失败"),
                    error
                )
            }
            throw error
        }
    }

    private fun planningRequest(
        messages: List<ApiMessage>,
        diagnosticRunId: String?,
        iteration: Int,
        finishReasonListener: (String) -> Unit
    ): ChatRequest {
        return ChatRequest(
            model = config.model,
            messages = messages,
            stream = true,
            maxTokens = maxTokens,
            temperature = 0.2,
            topP = 0.9,
            diagnosticContext = ApiDiagnosticContext(
                runId = diagnosticRunId,
                operation = "harness.plan",
                iteration = iteration
            ),
            finishReasonListener = finishReasonListener
        )
    }

    private fun repairDiagnostics(error: Exception): List<PlannerRepairDiagnostic> {
        return when (error) {
            is PlannerOutputBudgetExceededException -> listOf(
                PlannerRepairDiagnostic(
                    code = "PROJECT_PLAN_OUTPUT_BUDGET_EXHAUSTED",
                    message = error.message ?: "模型计划输出预算已耗尽"
                )
            )

            is WebAppProjectValidationException -> error.report.diagnostics.map { diagnostic ->
                PlannerRepairDiagnostic(
                    code = diagnostic.code,
                    message = diagnostic.message,
                    path = diagnostic.path
                )
            }

            is WebAppProjectException -> listOf(
                PlannerRepairDiagnostic(
                    code = "PROJECT_PLAN_CODEC_REJECTED",
                    message = error.message ?: "项目计划未通过严格解析"
                )
            )

            else -> listOf(
                PlannerRepairDiagnostic(
                    code = "PROJECT_PLAN_PARSE_FAILED",
                    message = error.message ?: "项目计划解析失败"
                )
            )
        }
    }

    private fun repairFeedback(diagnostics: List<PlannerRepairDiagnostic>): String {
        return buildString {
            appendLine("上一次输出未通过 WebProjectPlanCodec 的严格解析与校验。")
            appendLine("请根据宿主诊断修复，并只返回一个完整、合法的 JSON 对象；不要输出 Markdown、前言、解释或省略内容。")
            appendLine("修复后的计划仍须满足系统消息中的全部原始约束和用户需求。")
            appendLine("严格遵循系统消息列出的字段类型和对象层级：数组必须输出 JSON 数组，对象必须输出 JSON 对象；不得用相似字段替代必需字段。")
            appendLine("宿主诊断：")
            diagnostics.forEach { diagnostic ->
                append("- [")
                append(sanitizeDiagnostic(diagnostic.code))
                append("] ")
                diagnostic.path?.let { path ->
                    append("path=")
                    append(sanitizeDiagnostic(path))
                    append("; ")
                }
                appendLine(sanitizeDiagnostic(diagnostic.message))
            }
        }.trimEnd()
    }

    private fun compactRetryMessages(
        userRequirement: String,
        diagnostics: List<PlannerRepairDiagnostic>
    ): List<ApiMessage> {
        val retryInstruction = buildString {
            appendLine("原始需求：")
            appendLine(userRequirement)
            appendLine()
            appendLine("上一次模型回复因输出 token 预算耗尽而结束，finish_reason 表示 length/max_tokens；正文可能为空或 JSON 被截断。")
            appendLine("这不是普通 JSON 格式错误。请从原始需求重新生成计划，不要续写或依赖上一次的残缺输出。")
            appendLine("只返回一个完整且合法的 JSON 对象；保留所有必需字段和原始核心需求。")
            appendLine("为确保正文完整，只规划满足核心需求所需的最少文件，优先 3-8 个；字段描述用短句，验收项只覆盖明确要求的核心行为。")
            appendLine("宿主诊断：")
            diagnostics.forEach { diagnostic ->
                append("- [")
                append(sanitizeDiagnostic(diagnostic.code))
                append("] ")
                appendLine(sanitizeDiagnostic(diagnostic.message))
            }
        }.trimEnd()
        return listOf(
            ApiMessage("system", PLANNING_PROMPT),
            ApiMessage(ChatMessage.ROLE_USER, retryInstruction)
        )
    }

    private fun diagnosticTraceDetails(
        diagnostics: List<PlannerRepairDiagnostic>
    ): Map<String, String> {
        val codes = diagnostics.joinToString(",") { diagnostic -> diagnostic.code }
        val diagnosticText = diagnostics.joinToString("\n") { diagnostic ->
            listOfNotNull(diagnostic.code, diagnostic.path, diagnostic.message).joinToString("|")
        }
        return mapOf(
            "diagnostic_count" to diagnostics.size.toString(),
            "diagnostic_codes" to codes,
            "diagnostic_sha256" to sha256(diagnosticText)
        )
    }

    private fun sanitizeDiagnostic(value: String): String {
        return value.replace(DIAGNOSTIC_WHITESPACE, " ").trim().take(MAX_DIAGNOSTIC_CHARS)
    }

    private fun responseDetails(
        response: CharSequence,
        contentChunks: Int,
        thinkingChunks: Int,
        thinkingChars: Int
    ): Map<String, String> {
        val responseText = response.toString()
        return mapOf(
            "response_chars" to responseText.length.toString(),
            "response_sha256" to sha256(responseText),
            "content_chunks" to contentChunks.toString(),
            "thinking_chunks" to thinkingChunks.toString(),
            "thinking_chars" to thinkingChars.toString()
        )
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 24_000
        const val UNCORRELATED_RUN_ID = "planner-unscoped"
        const val MAX_REPAIR_ATTEMPTS = 1
        const val MAX_DIAGNOSTIC_CHARS = 1_000
        val DIAGNOSTIC_WHITESPACE = Regex("\\s+")

        fun sha256(content: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        val PLANNING_PROMPT = """
            你是 Web 项目架构规划器。根据用户需求规划一个无需 Node.js、npm、服务器或远程依赖、可直接在移动 WebView 中运行的多文件静态项目。

            用户需求是唯一的产品范围来源。先从原文提取核心目标和明确要求，再据此生成项目规格；不得把其他应用类型、历史项目或下面的字段说明当成默认功能。`primaryGoal` 和 P0 验收项必须直接覆盖用户要求的核心行为。若用户要制作一个游戏，游戏就是主应用；不得把它改成清单、记录或其他应用，再把游戏降为附加功能。不要自行添加与核心目标无关的第二种产品。

            只返回一个 JSON 对象，不要 Markdown 或解释。下面给出完整结构模板，帮助你遵守字段层级、类型和引用关系。尖括号中的内容以及模板里的 `AC-001`、`selector-main`、`main-control` 等示例标识都必须按当前用户需求替换，并同步更新所有引用；模板中的路径、功能、状态和设计值只说明结构，不能照搬为产品要求。产品范围唯一来自当前用户需求。

            {
              "schemaVersion": 1,
              "name": "<项目名称>",
              "entry": "index.html",
              "files": [
                {"path":"index.html","role":"entry","description":"<入口文件职责>","dependsOn":["styles/app.css","scripts/app.js"],"ownsCriteria":["AC-001"]},
                {"path":"styles/app.css","role":"style","description":"<样式文件职责>","dependsOn":[],"ownsCriteria":[]},
                {"path":"scripts/app.js","role":"script","description":"<交互逻辑职责>","dependsOn":[],"ownsCriteria":["AC-001"]}
              ],
              "appSpec": {
                "specVersion": 1,
                "summary": "<应用范围和主要能力>",
                "primaryGoal": "<用户要完成的核心目标>",
                "selectors": [
                  {"id":"selector-main","selector":"[data-testid='main-control']","purpose":"<该产品核心界面元素的用途>"}
                ],
                "screens": [
                  {"id":"screen-main","title":"<屏幕名称>","purpose":"<屏幕用途>","selectorIds":["selector-main"]}
                ],
                "components": [
                  {"id":"component-main","name":"<组件名称>","purpose":"<组件用途>","selectorIds":["selector-main"],"states":["<实际状态>"]}
                ],
                "state": {
                  "persistence":"NONE",
                  "fields":[{"name":"appMode","type":"string","description":"<按实际功能描述应用状态>"}],
                  "emptyState":"<真实的初始或待机状态>",
                  "errorStates":[]
                },
                "interactions": [
                  {"id":"interaction-main","title":"<核心操作名称>","triggerSelectorId":"selector-main","outcome":"<操作结果>","stateChanges":["<状态变化>"]}
                ],
                "userFlows": [
                  {"id":"flow-main","title":"<核心用户流程>","interactionIds":["interaction-main"],"criterionIds":["AC-001"]}
                ],
                "design": {
                  "theme":"<符合当前产品的视觉主题>",
                  "tokens":[{"name":"color-primary","value":"<符合产品主题的颜色值>","purpose":"<颜色用途>"}],
                  "constraints":["<实际的界面约束>"],
                  "viewportWidths":[360,393],
                  "minTouchTargetPx":44
                },
                "acceptanceContract": {
                  "criteria":[
                    {
                      "id":"AC-001",
                      "title":"<可验证的行为>",
                      "priority":"P0",
                      "covers":["CORE_SUCCESS"],
                      "setup":[],
                      "actions":[{"type":"CLICK","target":"[data-testid='main-control']"}],
                      "expected":[{"type":"VISIBLE","target":"[data-testid='main-control']"}]
                    }
                  ]
                }
              }
            }

            结构类型要求：`files`、`selectors`、`screens`、`components`、`fields`、`interactions`、`userFlows`、`tokens`、`constraints`、`viewportWidths`、`criteria`、`covers`、`setup`、`actions`、`expected` 都是数组；`state`、`design`、`acceptanceContract` 及数组元素都是对象。`acceptanceContract` 必须包在对象中并通过 `criteria` 字段提供数组。`tokens` 每项必须包含字符串 `name`、`value`、`purpose`；state field 每项必须包含字符串 `name`、`type`、`description`，可选字符串 `initialValue`。无内容的数组使用 `[]`，不得用对象或单个字符串代替数组。

            约束：
            - 文件数 3-24 个，必须包含且只包含一个 entry，并至少包含独立 CSS 与独立 JavaScript。
            - 可以按需规划 data/*.json、assets/*.svg 和拆分后的 JS modules，但不规划二进制文件。
            - 路径只能使用项目内相对路径和 `/`，不能出现隐藏目录、空段、`.`、`..` 或绝对路径。
            - 不要规划 package.json、构建配置、CDN 或任何需要安装依赖的文件。
            - description 要明确文件职责；dependsOn 只能引用 files 中的路径且不能成环。
            - 每条 acceptance criterion 必须由至少一个 files[].ownsCriteria 负责，userFlows 也必须引用它。
            - 规格必须充分、具体并可直接实现；文件、状态、交互、用户流程和验收项的数量由当前需求决定。不要为了精简而遗漏用户明确要求，也不要添加无关功能。
            - selectors 必须使用稳定、唯一且格式严格为 `[data-testid='name']` 的 CSS selector；验收动作和普通 DOM 断言的 target 必须直接使用其中的 selector。URL_CONTAINS 不需要 target；STORAGE_EQUALS 的 target 是 storageKey，不是 CSS selector。
            - 验收动作 type 仅可使用 CLICK、INPUT、SELECT、SUBMIT、FOCUS、BLUR、KEY_PRESS、WAIT。
            - 验收断言 type 仅可使用 EXISTS、NOT_EXISTS、VISIBLE、HIDDEN、TEXT_EQUALS、TEXT_CONTAINS、VALUE_EQUALS、ATTRIBUTE_EQUALS、COUNT_EQUALS、URL_CONTAINS、STORAGE_EQUALS。
            - STARLIT_STORAGE 的 storageKey 表示根 JSON 对象中的唯一字段名；实现必须用 storageGet(storageKey) 和 storageSet(storageKey, JSON.stringify(state)) 读写该字段，不能把它当提示标签。
            - 使用 STARLIT_STORAGE 时必须有 STORAGE_EQUALS 断言；其 target 必须与 state.storageKey 完全相同，value 必须是该字段预期值的合法 JSON 文本。
            - 每条 criterion 必须声明非空 covers，值仅可使用 CORE_SUCCESS、EMPTY_STATE、ERROR_STATE、PERSISTENCE，且同一值不能重复。
            - 每条 criterion 的 setup 或 actions 中必须至少包含一个非 WAIT 动作，不能用纯静态断言或仅等待来伪造验收。
            - TEXT_CONTAINS 和 URL_CONTAINS 的 value 必须是非空白文本，禁止使用恒真空字符串。
            - acceptanceContract 必须覆盖 CORE_SUCCESS；只有应用确实有可验证的无内容/初始状态时才覆盖 EMPTY_STATE；state.errorStates 非空时必须覆盖 ERROR_STATE；使用 STARLIT_STORAGE 时必须覆盖 PERSISTENCE，且对应 criterion 必须包含 STORAGE_EQUALS。覆盖 CORE_SUCCESS 的 criterion 必须标为 P0。
            - P0 criterion、对应 userFlow 和负责实现它的文件必须覆盖用户原文中的核心目标；不能只验证附加功能或无关的常见清单功能。
            - 不得在验收合同中写 JavaScript、自然语言伪步骤或模型自行判断的条件。
        """.trimIndent()
    }

    private enum class PlannerPhase(val logValue: String) {
        STREAM("stream"),
        PARSE("parse"),
        OUTPUT_BUDGET("output_budget")
    }

    private data class PlannerRepairDiagnostic(
        val code: String,
        val message: String,
        val path: String? = null
    )
}
