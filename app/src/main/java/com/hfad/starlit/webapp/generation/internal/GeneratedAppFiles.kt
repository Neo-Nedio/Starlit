package com.hfad.starlit.webapp.generation.internal

import android.content.Context
import com.hfad.starlit.workspace.AgentWorkspace
import com.hfad.starlit.webapp.project.WebAppProjectDataFiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object GeneratedAppFiles {
    fun saveHtmlFile(context: Context, htmlContent: String, userMessage: String): File {
        AgentWorkspace.ensureWorkspace(context)
        val appDir = File(context.filesDir, AgentWorkspace.WEB_DIR)
        if (!appDir.exists()) appDir.mkdirs()
        val file = nextAvailableHtmlFile(appDir, userMessage)
        file.parentFile?.mkdirs()
        file.writeText(WebAppHtmlIdentity.ensureWebAppIdentity(htmlContent))
        ensureWebAppDataFile(file)
        return file
    }

    fun dataFileForHtml(htmlFile: File): File = WebAppProjectDataFiles.dataFileForHtml(htmlFile)

    fun ensureWebAppDataFile(htmlFile: File): File = WebAppProjectDataFiles.ensureWebAppDataFile(htmlFile)

    private fun nextAvailableHtmlFile(appDir: File, userMessage: String): File {
        val stem = inferAppFileStem(userMessage)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val projectName = "${stem}_$timestamp"
        var projectDir = File(appDir, projectName)
        var index = 2
        while (projectDir.exists()) {
            projectDir = File(appDir, "${projectName}_$index")
            index++
        }
        return File(projectDir, "$projectName.html")
    }

    internal fun inferAppFileStem(userMessage: String): String {
        val message = userMessage.trim()
        val lower = message.lowercase(Locale.getDefault())

        APP_TYPE_KEYWORDS.firstOrNull { (keyword, _) ->
            lower.contains(keyword.lowercase(Locale.getDefault()))
        }?.let { (_, name) ->
            return sanitizeFileStem(withStarlitPrefix(name))
        }

        val cleaned = COMMON_REQUEST_WORDS.fold(message) { current, word ->
            current.replace(word, "", ignoreCase = true)
        }
            .replace(Regex("[，。！？、,.!?；;：:\\[\\]（）(){}]+"), "")
            .trim()

        return sanitizeFileStem(withStarlitPrefix(cleaned.ifBlank { "应用" }))
    }

    private fun withStarlitPrefix(appName: String): String {
        val normalized = appName.trim()
        return if (normalized.startsWith(STARLIT_APP_NAME_PREFIX)) {
            normalized
        } else {
            "$STARLIT_APP_NAME_PREFIX$normalized"
        }
    }

    private fun sanitizeFileStem(rawName: String): String {
        val sanitized = rawName
            .replace(Regex("\\s+"), "")
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .trim('_', '-', '.', ' ')
            .take(24)

        return sanitized.ifBlank { "web_app" }
    }

    private const val STARLIT_APP_NAME_PREFIX = "Starlit"

    private val APP_TYPE_KEYWORDS = listOf(
        "pomodoro" to "番茄钟",
        "番茄钟" to "番茄钟",
        "番茄" to "番茄钟",
        "notepad" to "记事本",
        "记事本" to "记事本",
        "记事" to "记事本",
        "备忘录" to "备忘录",
        "便签" to "便签",
        "todo" to "待办清单",
        "待办" to "待办清单",
        "任务" to "任务清单",
        "weather" to "天气",
        "天气" to "天气",
        "calculator" to "计算器",
        "计算器" to "计算器",
        "calendar" to "日历",
        "日历" to "日历",
        "note" to "笔记",
        "笔记" to "笔记",
        "timer" to "计时器",
        "计时" to "计时器",
        "秒表" to "秒表",
        "clock" to "时钟",
        "时钟" to "时钟",
        "habit" to "习惯追踪",
        "习惯" to "习惯追踪",
        "budget" to "预算",
        "记账" to "记账",
        "预算" to "预算",
        "kanban" to "看板",
        "看板" to "看板",
        "2048" to "2048游戏",
        "snake" to "贪吃蛇",
        "贪吃蛇" to "贪吃蛇",
        "井字棋" to "井字棋",
        "扫雷" to "扫雷",
        "game" to "游戏",
        "游戏" to "游戏",
        "抽奖" to "抽奖",
        "转盘" to "转盘",
        "二维码" to "二维码",
        "简历" to "简历",
        "菜谱" to "菜谱",
        "健身" to "健身",
        "画板" to "画板"
    )

    private val COMMON_REQUEST_WORDS = listOf(
        "帮我生成一个",
        "帮我创建一个",
        "帮我制作一个",
        "帮我做一个",
        "生成一个",
        "创建一个",
        "制作一个",
        "做一个",
        "写一个",
        "我要一个",
        "我想要一个",
        "请帮我",
        "帮我",
        "生成",
        "创建",
        "制作",
        "网页应用",
        "web app",
        "website",
        "小程序",
        "应用",
        "网页",
        "工具",
        "app",
        "一个"
    )


}
