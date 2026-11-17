package com.hfad.starlit.viewmodel.chat

import com.hfad.starlit.data.model.chat.ChatMessage

/** Owns the temporary assistant row used while a chat request is streaming. */
internal class ChatStreamingUiController(
    private val currentSessionId: () -> Long?,
    private val messages: () -> List<ChatMessage>?,
    private val publishMessages: (List<ChatMessage>) -> Unit,
    private val updateIntervalMs: Long,
    private val placeholderContent: String
) {
    fun addStreamingPlaceholder(
        state: StreamingSessionState,
        status: String,
        useLoadingLayout: Boolean
    ) {
        state.lastStreamingContentUpdateAt = 0L
        state.lastStreamingThinkingUpdateAt = 0L
        state.placeholder = ChatMessage(
            messageId = streamingMessageId(state.sessionId),
            role = ChatMessage.ROLE_ASSISTANT,
            content = if (useLoadingLayout) "" else placeholderContent,
            isStreaming = true,
            thinking = null,
            statusText = status,
            showStatusLoader = true
        )
        publishStreamingPlaceholder(state)
    }

    fun updateStreamingThinking(
        state: StreamingSessionState,
        thinking: String,
        force: Boolean = false
    ) {
        if (!force && !shouldUpdateStreamingThinking(state)) return
        val placeholder = state.placeholder ?: return
        if (placeholder.isStreaming) {
            state.placeholder = placeholder.copy(thinking = thinking)
            publishStreamingPlaceholder(state)
        }
    }

    fun updateStreamingMessage(
        state: StreamingSessionState,
        content: String,
        force: Boolean = false
    ) {
        if (!force && !shouldUpdateStreamingContent(state)) return
        val placeholder = state.placeholder ?: return
        state.placeholder = placeholder.copy(
            content = content,
            isStreaming = true,
            statusText = null,
            showStatusLoader = false
        )
        publishStreamingPlaceholder(state)
    }

    fun updateStreamingStatusText(state: StreamingSessionState, text: String) {
        val placeholder = state.placeholder ?: return
        state.placeholder = placeholder.copy(
            statusText = text,
            showStatusLoader = true,
            isStreaming = true
        )
        publishStreamingPlaceholder(state)
    }

    fun updateReconnectStatus(state: StreamingSessionState, secondsRemaining: Int) {
        updateStreamingStatusText(state, "网络状态出错，尝试重连 ${secondsRemaining}秒")
    }

    fun clearReconnectStatus(state: StreamingSessionState) {
        val placeholder = state.placeholder ?: return
        val hasVisibleContent = hasVisibleStreamingContent(placeholder.content)
        state.placeholder = placeholder.copy(
            statusText = if (hasVisibleContent) null else "正在思考",
            showStatusLoader = !hasVisibleContent,
            isStreaming = true
        )
        publishStreamingPlaceholder(state)
    }

    fun removeStreamingPlaceholder(state: StreamingSessionState) {
        val messageId = state.placeholder?.messageId ?: streamingMessageId(state.sessionId)
        state.lastStreamingContentUpdateAt = 0L
        state.lastStreamingThinkingUpdateAt = 0L
        state.placeholder = null
        if (currentSessionId() != state.sessionId) return
        val currentList = messages()?.toMutableList() ?: return
        currentList.removeAll { it.messageId == messageId }
        publishMessages(currentList)
    }

    private fun shouldUpdateStreamingContent(state: StreamingSessionState): Boolean {
        val now = System.currentTimeMillis()
        if (state.lastStreamingContentUpdateAt == 0L ||
            now - state.lastStreamingContentUpdateAt >= updateIntervalMs
        ) {
            state.lastStreamingContentUpdateAt = now
            return true
        }
        return false
    }

    private fun shouldUpdateStreamingThinking(state: StreamingSessionState): Boolean {
        val now = System.currentTimeMillis()
        if (state.lastStreamingThinkingUpdateAt == 0L ||
            now - state.lastStreamingThinkingUpdateAt >= updateIntervalMs
        ) {
            state.lastStreamingThinkingUpdateAt = now
            return true
        }
        return false
    }

    private fun publishStreamingPlaceholder(state: StreamingSessionState) {
        if (currentSessionId() != state.sessionId) return
        val placeholder = state.placeholder ?: return
        val currentList = messages()?.toMutableList() ?: mutableListOf()
        currentList.removeAll { it.messageId == placeholder.messageId }
        currentList.add(placeholder)
        publishMessages(currentList)
    }

    private fun hasVisibleStreamingContent(content: String): Boolean {
        return content.isNotBlank() && content != placeholderContent
    }

    private fun streamingMessageId(sessionId: Long): Long = -sessionId.coerceAtLeast(1L)
}
