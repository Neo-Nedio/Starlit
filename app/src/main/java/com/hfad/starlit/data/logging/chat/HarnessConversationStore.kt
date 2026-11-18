package com.hfad.starlit.data.logging.chat

import android.content.Context
import com.google.gson.Gson
import com.hfad.starlit.webapp.harness.HarnessMessage
import com.hfad.starlit.webapp.harness.HarnessMessageRole
import java.io.File

/** Persists model-facing Harness messages independently from the visible chat transcript. */
internal object HarnessConversationStore {
    private const val DIRECTORY_NAME = "harness_conversations"
    private val SESSION_FILE_PATTERN = Regex("session-\\d+\\.jsonl")
    private val gson = Gson()
    private val lock = Any()

    fun load(context: Context, sessionId: Long): List<HarnessMessage> = synchronized(lock) {
        val file = historyFile(context, sessionId)
        if (!file.isFile) return@synchronized emptyList()

        file.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching { gson.fromJson(line, HarnessMessage::class.java) }.getOrNull()
            }
                .filter { it.role != HarnessMessageRole.SYSTEM }
                .map { it.copy(preserveOriginalContent = true) }
                .toList()
        }
    }

    fun append(context: Context, sessionId: Long, message: HarnessMessage) {
        if (message.role == HarnessMessageRole.SYSTEM) return

        synchronized(lock) {
            val file = historyFile(context, sessionId)
            val parent = file.parentFile
            if (parent == null || (!parent.isDirectory && !parent.mkdirs())) {
                error("无法创建 Harness 对话历史目录：${file.parent}")
            }
            file.appendText(gson.toJson(message) + "\n", Charsets.UTF_8)
        }
    }

    fun delete(context: Context, sessionId: Long) = synchronized(lock) {
        historyFile(context, sessionId).delete()
    }

    fun deleteAll(context: Context) = synchronized(lock) {
        historyDirectory(context)
            .listFiles()
            ?.filter { it.isFile && SESSION_FILE_PATTERN.matches(it.name) }
            ?.forEach { it.delete() }
    }

    private fun historyDirectory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY_NAME)

    private fun historyFile(context: Context, sessionId: Long): File =
        File(historyDirectory(context), "session-$sessionId.jsonl")
}
