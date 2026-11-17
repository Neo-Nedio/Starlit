package com.hfad.starlit.webapp.generation

import android.content.Context
import com.hfad.starlit.webapp.generation.internal.AppGenerationIntentClassifier
import com.hfad.starlit.webapp.generation.internal.AppGenerationPrompts
import com.hfad.starlit.webapp.generation.internal.GeneratedAppFiles
import com.hfad.starlit.webapp.generation.internal.GeneratedHtmlQuality
import com.hfad.starlit.webapp.generation.internal.WebAppHtmlIdentity
import java.io.File

object AppGenerator {
    const val APP_GEN_MAX_TOKENS = 256000
    const val APP_GEN_MAX_OUTPUT_TOKENS = 128000
    const val APP_DIFF_MAX_OUTPUT_TOKENS = 32_000
    const val APP_PROJECT_FILE_MAX_OUTPUT_TOKENS = 32_000
    internal const val INITIAL_QUALITY_MAX_ATTEMPTS = 2
    internal const val INITIAL_MISSING_HTML_MAX_ATTEMPTS = 3
    const val WEB_APP_BRIDGE_NAME = "StarlitApp"
    const val WEB_APP_USER_AGENT_TOKEN = "StarlitApp/1"

    internal sealed interface InitialGenerationDecision {
        data class Retry(val diagnostics: List<String>) : InitialGenerationDecision
        data class RunHarness(val html: String, val diagnostics: List<String>) : InitialGenerationDecision
        data class Fail(val reason: String, val diagnostics: List<String>) : InitialGenerationDecision
    }

    suspend fun buildSystemPrompt(context: Context, userMessage: String): String =
        AppGenerationPrompts.buildSystemPrompt(context, userMessage)

    suspend fun buildProjectSystemPrompt(
        context: Context,
        userMessage: String,
        isModification: Boolean
    ): String = AppGenerationPrompts.buildProjectSystemPrompt(context, userMessage, isModification)

    suspend fun buildModificationSystemPrompt(
        context: Context,
        relativePath: String,
        expectedSha256: String,
        userMessage: String,
        includeTools: Boolean = false
    ): String = AppGenerationPrompts.buildModificationSystemPrompt(
        context, relativePath, expectedSha256, userMessage, includeTools
    )

    internal fun modificationNeedsTools(userMessage: String): Boolean =
        AppGenerationIntentClassifier.modificationNeedsTools(userMessage)

    fun buildModificationUserPrompt(
        userMessage: String,
        snapshot: LocalDiffFileTool.FileSnapshot,
        previousFailure: String? = null
    ): String = AppGenerationPrompts.buildModificationUserPrompt(userMessage, snapshot, previousFailure)

    fun buildQualityRetryUserPrompt(userMessage: String, qualityIssues: List<String>): String =
        AppGenerationPrompts.buildQualityRetryUserPrompt(userMessage, qualityIssues)

    fun buildHarnessRepairUserPrompt(
        userMessage: String,
        snapshot: LocalDiffFileTool.FileSnapshot,
        failedStage: String,
        diagnostics: List<String>,
        iteration: Int
    ): String = AppGenerationPrompts.buildHarnessRepairUserPrompt(
        userMessage, snapshot, failedStage, diagnostics, iteration
    )

    internal fun resolveAppGenerationOutputLimit(contextTokenLimit: Int): Int =
        AppGenerationPrompts.resolveAppGenerationOutputLimit(contextTokenLimit)

    internal fun resolveAppDiffOutputLimit(contextTokenLimit: Int): Int =
        AppGenerationPrompts.resolveAppDiffOutputLimit(contextTokenLimit)

    internal fun resolveProjectFileOutputLimit(contextTokenLimit: Int): Int =
        AppGenerationPrompts.resolveProjectFileOutputLimit(contextTokenLimit)

    fun extractHtml(content: String): String? = GeneratedHtmlQuality.extractHtml(content)

    internal fun decideInitialGeneration(
        modelOutput: String,
        attempt: Int
    ): InitialGenerationDecision {
        require(attempt > 0) { "attempt must be greater than zero" }
        val html = extractHtml(modelOutput)
        if (html == null) {
            val diagnostics = listOf("模型返回内容中未找到完整 HTML")
            return if (attempt < INITIAL_MISSING_HTML_MAX_ATTEMPTS) {
                InitialGenerationDecision.Retry(diagnostics)
            } else {
                InitialGenerationDecision.Fail(
                    reason = "模型连续 $INITIAL_MISSING_HTML_MAX_ATTEMPTS 次未返回可提取的 HTML",
                    diagnostics = diagnostics
                )
            }
        }
        val qualityIssues = generatedWebAppQualityIssues(html)
        return if (qualityIssues.isNotEmpty() && attempt < INITIAL_QUALITY_MAX_ATTEMPTS) {
            InitialGenerationDecision.Retry(qualityIssues)
        } else {
            InitialGenerationDecision.RunHarness(html, qualityIssues)
        }
    }

    fun extractUnifiedDiff(content: String): String? = GeneratedHtmlQuality.extractUnifiedDiff(content)

    fun generatedWebAppQualityIssues(htmlContent: String): List<String> =
        GeneratedHtmlQuality.generatedWebAppQualityIssues(htmlContent)

    fun validateGeneratedWebApp(htmlContent: String) = GeneratedHtmlQuality.validateGeneratedWebApp(htmlContent)

    fun validatePatchedWebApp(originalContent: String, patchedContent: String) =
        GeneratedHtmlQuality.validatePatchedWebApp(originalContent, patchedContent)

    fun ensureWebAppIdentity(htmlContent: String): String =
        WebAppHtmlIdentity.ensureWebAppIdentity(htmlContent)

    fun withStarlitWebAppUserAgent(userAgent: String?): String =
        WebAppHtmlIdentity.withStarlitWebAppUserAgent(userAgent)

    fun extractWebAppIdentity(htmlContent: String): String? =
        WebAppHtmlIdentity.extractWebAppIdentity(htmlContent)

    fun saveHtmlFile(context: Context, htmlContent: String, userMessage: String): File =
        GeneratedAppFiles.saveHtmlFile(context, htmlContent, userMessage)

    fun dataFileForHtml(htmlFile: File): File = GeneratedAppFiles.dataFileForHtml(htmlFile)

    fun ensureWebAppDataFile(htmlFile: File): File = GeneratedAppFiles.ensureWebAppDataFile(htmlFile)

    internal fun inferAppFileStem(userMessage: String): String =
        GeneratedAppFiles.inferAppFileStem(userMessage)
}
