package com.hfad.starlit.viewmodel.chat

import com.hfad.starlit.data.model.chat.ChatMessage
import com.hfad.starlit.service.HarnessProgressReporter
import kotlinx.coroutines.Job

/** Mutable state shared by the chat request flow and its UI/foreground-service adapters. */
internal data class StreamingSessionState(
    val sessionId: Long,
    var job: Job? = null,
    var serviceJob: Job? = null,
    var serviceRunId: String? = null,
    var isServiceOwned: Boolean = false,
    var harnessProgressReporter: HarnessProgressReporter? = null,
    var appGenerationProgressJob: Job? = null,
    var placeholder: ChatMessage? = null,
    val streamingContent: StringBuilder = StringBuilder(),
    val thinkingContent: StringBuilder = StringBuilder(),
    var isGeneratingApp: Boolean = false,
    var userMessagePersisted: Boolean = false,
    var hasFinalAssistantMessage: Boolean = false,
    var lastStreamingContentUpdateAt: Long = 0L,
    var lastStreamingThinkingUpdateAt: Long = 0L,
    var lastGenerateTaskUpdateAt: Long = 0L,
    var lastHarnessProgressUpdateAt: Long = 0L,
    var lastHarnessProgressFingerprint: String = "",
    var reconnectPrefixPending: String = ""
)
