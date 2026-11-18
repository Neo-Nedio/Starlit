package com.hfad.starlit.viewmodel.chat

import com.hfad.starlit.data.model.chat.SessionTokenUsage
import com.hfad.starlit.data.api.chat.model.ModelTokenUsage
import com.hfad.starlit.data.repository.chat.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SessionTokenUsageController(
    private val repository: ChatRepository,
    private val ioScope: CoroutineScope,
    private val isSessionCurrent: (Long) -> Boolean,
    private val currentUsage: () -> SessionTokenUsage?,
    private val publishUsage: (SessionTokenUsage) -> Unit
) {
    private val writeMutex = Mutex()

    fun load(sessionId: Long, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val session = repository.getSessionById(sessionId)
            val usage = SessionTokenUsage(
                totalTokens = session?.consumedTokens ?: 0L,
                includesEstimate = session?.tokenUsageIncludesEstimate ?: false
            )
            withContext(Dispatchers.Main.immediate) {
                if (!isSessionCurrent(sessionId)) return@withContext
                val displayed = currentUsage() ?: SessionTokenUsage()
                if (usage.totalTokens >= displayed.totalTokens) {
                    publishUsage(usage)
                }
            }
        }
    }

    fun record(sessionId: Long, usage: ModelTokenUsage) {
        if (usage.totalTokens <= 0L) return
        ioScope.launch {
            writeMutex.withLock {
                repository.addSessionTokenUsage(
                    sessionId = sessionId,
                    tokens = usage.totalTokens,
                    estimated = usage.estimated
                )
                val session = repository.getSessionById(sessionId) ?: return@withLock
                withContext(Dispatchers.Main.immediate) {
                    if (isSessionCurrent(sessionId)) {
                        publishUsage(
                            SessionTokenUsage(
                                totalTokens = session.consumedTokens,
                                includesEstimate = session.tokenUsageIncludesEstimate
                            )
                        )
                    }
                }
            }
        }
    }
}
