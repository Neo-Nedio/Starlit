package com.hfad.starlit.webapp.generation.internal

import java.util.Locale

internal object GeneratedHtmlQuality {
    private val STYLE_BLOCK_REGEX = Regex("<style\\b[^>]*>([\\s\\S]*?)</style\\s*>", RegexOption.IGNORE_CASE)
    private val CSS_RULE_REGEX = Regex("[^{}]+\\{[^{}]+\\}")
    private val SCRIPT_BLOCK_REGEX = Regex("<script\\b[^>]*>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE)
    private val RAW_TEXT_BLOCK_REGEX = Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val HTML_COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
    private val HTML_TAG_TOKEN_REGEX = Regex("<\\s*(/?)\\s*([a-z][a-z0-9:-]*)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SUSPICIOUS_ATTRIBUTE_TEXT_REGEX = Regex(
        ">\\s*=[\"'][^<>\\n]*(?:\\bid|\\bclass|\\btype)\\s*=",
        RegexOption.IGNORE_CASE
    )
    private val INTERACTIVE_ELEMENT_REGEX = Regex("<(?:button|input|textarea|select)\\b", RegexOption.IGNORE_CASE)
    private val EVENT_BINDING_REGEX = Regex(
        "(?:addEventListener\\s*\\(|on(?:click|input|change|submit|keydown|pointerdown|touchstart)\\s*=)",
        RegexOption.IGNORE_CASE
    )
    private val BALANCED_HTML_TAGS = setOf(
        "article", "button", "canvas", "dialog", "div", "footer", "form", "header",
        "li", "main", "nav", "ol", "p", "section", "select", "textarea", "ul"
    )
    fun extractHtml(content: String): String? {
        var trimmed = content.trim()

        val codeBlockRegex = Regex("```(?:html|HTML)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val match = codeBlockRegex.find(trimmed)
        if (match != null) {
            trimmed = match.groupValues[1].trim()
        }

        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            return trimmed
        }
        val startIndex = trimmed.indexOf("<!DOCTYPE", ignoreCase = true)
        if (startIndex >= 0) return trimmed.substring(startIndex)
        val htmlStart = trimmed.indexOf("<html", ignoreCase = true)
        if (htmlStart >= 0) return trimmed.substring(htmlStart)
        return null
    }

    fun extractUnifiedDiff(content: String): String? {
        val trimmed = content.trim()
        val fenced = Regex(
            "```(?:diff|patch)\\s*\\n([\\s\\S]*?)\\n```",
            RegexOption.IGNORE_CASE
        ).find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!fenced.isNullOrEmpty()) return fenced

        return trimmed.takeIf {
            it.startsWith("diff --git ") || it.startsWith("--- ")
        }
    }

    fun generatedWebAppQualityIssues(htmlContent: String): List<String> {
        val issues = mutableListOf<String>()
        val html = extractHtml(htmlContent)
        if (html == null || !html.trimEnd().endsWith("</html>", ignoreCase = true)) {
            return listOf("HTML 文档不完整")
        }
        val qualityHtml = withoutRuntimeGuard(html)

        val structuralHtml = HTML_COMMENT_REGEX.replace(RAW_TEXT_BLOCK_REGEX.replace(qualityHtml, ""), "")
        val unbalancedTags = findUnbalancedHtmlTags(structuralHtml)
        if (unbalancedTags.isNotEmpty()) {
            issues += "HTML 标签未正确闭合：${unbalancedTags.joinToString("、")}"
        }
        if (SUSPICIOUS_ATTRIBUTE_TEXT_REGEX.containsMatchIn(structuralHtml)) {
            issues += "HTML 中存在缺失标签名的属性片段"
        }

        val styleContent = STYLE_BLOCK_REGEX.findAll(qualityHtml)
            .joinToString("\n") { it.groupValues[1] }
            .trim()
        val styleRuleCount = CSS_RULE_REGEX.findAll(styleContent).count()
        if (styleContent.length < 500 || styleRuleCount < 6) {
            issues += "CSS 过少，未形成完整的移动端视觉系统"
        }

        val scriptContent = SCRIPT_BLOCK_REGEX.findAll(qualityHtml)
            .joinToString("\n") { it.groupValues[1] }
            .trim()
        if (scriptContent.length < 250) {
            issues += "JavaScript 过少，主要交互可能没有完整实现"
        }
        if (!INTERACTIVE_ELEMENT_REGEX.containsMatchIn(qualityHtml)) {
            issues += "页面缺少可执行主要任务的交互控件"
        }
        if (!EVENT_BINDING_REGEX.containsMatchIn(qualityHtml)) {
            issues += "页面没有绑定用户交互事件"
        }
        if (Regex("\\b(?:TODO|FIXME|placeholder implementation)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(qualityHtml)
        ) {
            issues += "代码中仍包含占位实现"
        }
        return issues
    }

    private fun withoutRuntimeGuard(content: String): String {
        val start = content.indexOf(WebAppHtmlIdentity.RUNTIME_GUARD_START)
        if (start < 0) return content
        val end = content.indexOf(WebAppHtmlIdentity.RUNTIME_GUARD_END, start)
        if (end < 0) return content
        return content.removeRange(start, end + WebAppHtmlIdentity.RUNTIME_GUARD_END.length)
    }

    private fun findUnbalancedHtmlTags(structuralHtml: String): List<String> {
        val balances = mutableMapOf<String, Int>()
        HTML_TAG_TOKEN_REGEX.findAll(structuralHtml).forEach { match ->
            val tagName = match.groupValues[2].lowercase(Locale.US)
            if (tagName !in BALANCED_HTML_TAGS) return@forEach
            val delta = if (match.groupValues[1].isEmpty()) 1 else -1
            balances[tagName] = balances.getOrDefault(tagName, 0) + delta
        }
        return balances.filterValues { it != 0 }.keys.sorted()
    }

    fun validateGeneratedWebApp(htmlContent: String) {
        val issues = generatedWebAppQualityIssues(htmlContent)
        require(issues.isEmpty()) {
            "生成质量检查未通过：${issues.joinToString("；")}"
        }
    }

    fun validatePatchedWebApp(originalContent: String, patchedContent: String) {
        require(extractHtml(patchedContent) != null) {
            "增量修改后的文件不是合法 HTML"
        }
        require(patchedContent.trimEnd().endsWith("</html>", ignoreCase = true)) {
            "增量修改后的 HTML 缺少结束标签"
        }

        val originalIdentity = WebAppHtmlIdentity.extractWebAppIdentity(originalContent)
        val patchedIdentity = WebAppHtmlIdentity.extractWebAppIdentity(patchedContent)
        require(originalIdentity != null && patchedIdentity == originalIdentity) {
            "增量修改不能删除或替换网页应用标识"
        }
        require(patchedContent.countOccurrences(WebAppHtmlIdentity.RUNTIME_GUARD_START) == 1 &&
                patchedContent.countOccurrences(WebAppHtmlIdentity.RUNTIME_GUARD_END) == 1
        ) {
            "增量修改不能删除或复制Starlit运行时保护区块"
        }
        require(extractRuntimeGuard(originalContent) == extractRuntimeGuard(patchedContent)) {
            "增量修改不能更改Starlit运行时保护区块"
        }
    }

    private fun extractRuntimeGuard(content: String): String? {
        val start = content.indexOf(WebAppHtmlIdentity.RUNTIME_GUARD_START)
        if (start < 0) return null
        val end = content.indexOf(WebAppHtmlIdentity.RUNTIME_GUARD_END, start)
        if (end < 0) return null
        return content.substring(start, end + WebAppHtmlIdentity.RUNTIME_GUARD_END.length)
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var fromIndex = 0
        while (true) {
            val index = indexOf(value, fromIndex)
            if (index < 0) return count
            count++
            fromIndex = index + value.length
        }
    }


}
