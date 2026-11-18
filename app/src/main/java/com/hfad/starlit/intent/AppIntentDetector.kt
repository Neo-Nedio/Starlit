package com.hfad.starlit.intent

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hfad.starlit.data.api.common.ApiEndpointResolver
import com.hfad.starlit.data.api.chat.model.ChatCallConfig
import com.hfad.starlit.data.api.chat.model.ModelTokenUsage
import com.hfad.starlit.data.api.chat.model.ModelTokenUsageResolver
import com.hfad.starlit.data.logging.api.ApiDiagnosticContext
import com.hfad.starlit.data.logging.api.ApiRequestTrace
import com.hfad.starlit.data.logging.api.ApiLoggingInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object AppIntentDetector {

    private const val TAG = "AppIntentDetector"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val INTENT_MAX_TOKENS = 20
    private const val INTENT_SYSTEM_PROMPT = """你是一个严格的二分类路由器。判断用户当前是否要求直接生成或修改一个可运行、可交互的网页应用。
返回 {"intent":"generate_app"}：用户明确或隐式要求产出网页、App、小程序、工具、游戏、交互页面，或在已有网页应用上继续修改功能和界面。
返回 {"intent":"chat"}：用户只是在询问开发方法、解释/分析/推荐应用、排查已有代码、生成文章或代码片段，或明确说不要生成应用。
示例："给我整一个能记账的" -> {"intent":"generate_app"}
示例："怎么开发一个记账 App" -> {"intent":"chat"}
示例："不要生成网页，只说实现思路" -> {"intent":"chat"}
只返回上述一个 JSON 对象，不要解释，不要输出其他值。"""

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(ApiLoggingInterceptor())
        .build()

    suspend fun isAppGenerationIntent(
        context: Context,
        config: ChatCallConfig,
        userMessage: String,
        hasGeneratedAppInSession: Boolean = false,
        tokenUsageListener: ((ModelTokenUsage) -> Unit)? = null,
        diagnosticContext: ApiDiagnosticContext? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val totalStartMs = System.currentTimeMillis()
        Log.d(TAG, "start at=${formatTimestamp(totalStartMs)} message=${preview(userMessage)}")

        if (AppIntentHeuristics.analyzeLocalSignals(userMessage).hasNegativeContext) {
            Log.d(TAG, "done result=chat source=explicit_negative total=${elapsedSince(totalStartMs)}ms")
            return@withContext false
        }

        val keywordStartMs = System.currentTimeMillis()
        val keywordResult = AppIntentHeuristics.isAppGenerationByKeywords(userMessage, hasGeneratedAppInSession)
        Log.d(
            TAG,
            "keyword result=$keywordResult elapsed=${elapsedSince(keywordStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
        )
        if (keywordResult) {
            Log.d(TAG, "done result=generate_app source=keyword total=${elapsedSince(totalStartMs)}ms")
            return@withContext true
        }

        val embeddingStartMs = System.currentTimeMillis()
        when (val embeddingDecision = LocalEmbeddingIntentDetector.detect(context, userMessage)) {
            LocalEmbeddingIntentDetector.Decision.GenerateApp -> {
                Log.d(
                    TAG,
                    "embedding result=generate_app elapsed=${elapsedSince(embeddingStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                )
                Log.d(TAG, "done result=generate_app source=embedding total=${elapsedSince(totalStartMs)}ms")
                return@withContext true
            }
            LocalEmbeddingIntentDetector.Decision.Chat -> {
                Log.d(
                    TAG,
                    "embedding result=chat elapsed=${elapsedSince(embeddingStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                )
                Log.d(TAG, "done result=chat source=embedding total=${elapsedSince(totalStartMs)}ms")
                return@withContext false
            }
            LocalEmbeddingIntentDetector.Decision.Uncertain -> {
                Log.d(
                    TAG,
                    "embedding result=${embeddingDecision.name} elapsed=${elapsedSince(embeddingStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                )
                val localGate = AppIntentHeuristics.shouldUseLlmFallback(userMessage, hasGeneratedAppInSession)
                Log.d(TAG, "localGate llmFallback=${localGate.useLlm} reason=${localGate.reason}")
                if (!localGate.useLlm) {
                    Log.d(TAG, "done result=chat source=local_chat_gate total=${elapsedSince(totalStartMs)}ms")
                    return@withContext false
                }
            }
        }

        try {
            val llmStartMs = System.currentTimeMillis()
            val builder = buildIntentRequest(
                config = config,
                userMessage = userMessage,
                hasGeneratedAppInSession = hasGeneratedAppInSession,
                diagnosticContext = diagnosticContext,
            )
            client.newCall(builder.build()).execute().use { response ->
                val responseBody = response.body?.string()
                if (responseBody == null) {
                    Log.d(
                        TAG,
                        "llm body=null result=chat elapsed=${elapsedSince(llmStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                    )
                    Log.d(TAG, "done result=chat source=llm_empty_body total=${elapsedSince(totalStartMs)}ms")
                    return@withContext false
                }
                if (!response.isSuccessful) {
                    Log.d(
                        TAG,
                        "llm http=${response.code} result=chat elapsed=${elapsedSince(llmStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                    )
                    Log.d(TAG, "done result=chat source=llm_http_error total=${elapsedSince(totalStartMs)}ms")
                    return@withContext false
                }

                val content = parseIntentContent(responseBody, config.isAnthropic)
                tokenUsageListener?.let { listener ->
                    val usage = ModelTokenUsageResolver.resolve(
                        responseBody = responseBody,
                        requestTexts = listOf(
                            INTENT_SYSTEM_PROMPT,
                            routingMessage(userMessage, hasGeneratedAppInSession)
                        ),
                        responseText = content.orEmpty()
                    )
                    runCatching { listener(usage) }
                }
                if (content == null) {
                    Log.d(
                        TAG,
                        "llm parsed=null result=chat elapsed=${elapsedSince(llmStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                    )
                    Log.d(TAG, "done result=chat source=llm_parse_empty total=${elapsedSince(totalStartMs)}ms")
                    return@withContext false
                }

                val result = parseIntentResult(content) == true
                Log.d(
                    TAG,
                    "llm result=${if (result) "generate_app" else "chat"} elapsed=${elapsedSince(llmStartMs)}ms total=${elapsedSince(totalStartMs)}ms"
                )
                Log.d(
                    TAG,
                    "done result=${if (result) "generate_app" else "chat"} source=llm total=${elapsedSince(totalStartMs)}ms"
                )
                result
            }
        } catch (e: Exception) {
            val fallback = AppIntentHeuristics.isAppGenerationByKeywords(userMessage, hasGeneratedAppInSession)
            Log.d(
                TAG,
                "error=${e.javaClass.simpleName}:${e.message.orEmpty()} fallback=$fallback total=${elapsedSince(totalStartMs)}ms"
            )
            Log.d(TAG, "done result=${if (fallback) "generate_app" else "chat"} source=error_keyword_fallback total=${elapsedSince(totalStartMs)}ms")
            fallback
        }
    }

    private fun elapsedSince(startMs: Long): Long = System.currentTimeMillis() - startMs

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
    }

    private fun preview(message: String): String {
        val compact = message.replace(Regex("\\s+"), " ").take(48)
        return "\"$compact\" len=${message.length}"
    }

    internal fun shouldUseLlmFallbackForTest(
        message: String,
        hasGeneratedAppInSession: Boolean = false,
    ): AppIntentHeuristics.LocalGateResult = AppIntentHeuristics.shouldUseLlmFallback(message, hasGeneratedAppInSession)

    internal fun isAppGenerationByKeywordsForTest(
        message: String,
        hasGeneratedAppInSession: Boolean = false,
    ): Boolean = AppIntentHeuristics.isAppGenerationByKeywords(message, hasGeneratedAppInSession)

    internal fun parseIntentResultForTest(content: String): Boolean? = parseIntentResult(content)

    private fun buildIntentRequest(
        config: ChatCallConfig,
        userMessage: String,
        hasGeneratedAppInSession: Boolean,
        diagnosticContext: ApiDiagnosticContext? = null,
    ): Request.Builder {
        val routingMessage = routingMessage(userMessage, hasGeneratedAppInSession)
        val requestJson = if (config.isAnthropic) {
            gson.toJson(mapOf(
                "model" to config.model,
                "system" to INTENT_SYSTEM_PROMPT,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to routingMessage)
                ),
                "stream" to false,
                "max_tokens" to INTENT_MAX_TOKENS,
            ))
        } else {
            gson.toJson(mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to INTENT_SYSTEM_PROMPT),
                    mapOf("role" to "user", "content" to routingMessage)
                ),
                "stream" to false,
                "max_tokens" to INTENT_MAX_TOKENS,
            ))
        }

        val requestBody = requestJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .tag(ApiRequestTrace::class.java, ApiRequestTrace.create(diagnosticContext))
            .url(
                if (config.isAnthropic) {
                    ApiEndpointResolver.anthropicMessagesUrl(config.baseUrl)
                } else {
                    ApiEndpointResolver.openAiChatCompletionsUrl(config.baseUrl)
                }
            )
            .addHeader("Content-Type", "application/json")
            .post(requestBody)

        if (config.isAnthropic) {
            builder.addHeader("anthropic-version", ANTHROPIC_VERSION)
            if (config.apiKey.isNotEmpty()) {
                builder.addHeader("x-api-key", config.apiKey)
            }
        } else if (config.apiKey.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        return builder
    }

    private fun routingMessage(
        userMessage: String,
        hasGeneratedAppInSession: Boolean
    ): String {
        return if (hasGeneratedAppInSession) {
            "[会话状态：已经生成过网页应用，修改或继续完善类请求应判为 generate_app]\n$userMessage"
        } else {
            userMessage
        }
    }

    private fun parseIntentResult(content: String): Boolean? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching {
            JsonParser.parseString(content.substring(start, end + 1))
        }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val intent = root.get("intent")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?: return null
        return when (intent) {
            "generate_app" -> true
            "chat" -> false
            else -> null
        }
    }

    private fun parseIntentContent(responseBody: String, isAnthropic: Boolean): String? {
        if (!isAnthropic) {
            val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            return chatResponse.choices?.firstOrNull()?.message?.content
        }

        val root = JsonParser.parseString(responseBody)
        if (!root.isJsonObject) return null
        val content = root.asJsonObject.get("content") ?: return null
        return when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.joinToString("") { el ->
                if (el.isJsonObject) {
                    el.asJsonObject.get("text")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        .orEmpty()
                } else {
                    ""
                }
            }.ifBlank { null }
            else -> null
        }
    }

    private data class ChatCompletionResponse(
        val choices: List<Choice>? = null
    )

    private data class Choice(
        val message: MessageData? = null
    )

    private data class MessageData(
        val content: String? = null
    )
}
