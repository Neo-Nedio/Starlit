package com.hfad.starlit.webapp.generation.internal

import java.util.Locale

internal object AppGenerationIntentClassifier {
    private val DIRECT_MODIFICATION_TOOL_KEYWORDS = listOf(
        "starlitapp", "android", "安卓", "系统能力", "相机", "拍照", "录像", "定位", "震动",
        "振动", "手电筒", "剪贴板", "拨号", "短信", "系统设置", "toast"
    )

    private val AMBIGUOUS_MODIFICATION_TOOL_KEYWORDS = listOf("闹钟", "日历", "通知", "分享")

    private val MODIFICATION_TOOL_ACTIONS = listOf(
        "调用", "接入", "唤起", "写入系统", "打开系统", "创建事件", "添加事件", "设置闹钟",
        "发送通知", "系统分享"
    )

    internal fun modificationNeedsTools(userMessage: String): Boolean {
        val normalized = userMessage.lowercase(Locale.US)
        return DIRECT_MODIFICATION_TOOL_KEYWORDS.any(normalized::contains) ||
                (AMBIGUOUS_MODIFICATION_TOOL_KEYWORDS.any(normalized::contains) &&
                        MODIFICATION_TOOL_ACTIONS.any(normalized::contains))
    }
}
