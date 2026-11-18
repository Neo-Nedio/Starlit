package com.hfad.starlit.viewmodel.generation

import androidx.lifecycle.MutableLiveData
import com.hfad.starlit.data.model.chat.GenerateTaskState
import com.hfad.starlit.viewmodel.chat.StreamingSessionState

/** Stores generated-app task progress and reports it to the owning foreground run. */
internal class GenerateTaskStateController(
    private val taskStates: MutableLiveData<Map<Long, GenerateTaskState>>,
    private val streamingStateForSession: (Long) -> StreamingSessionState?,
    private val updateIntervalMs: Long,
    private val notificationUpdateIntervalMs: Long
) {
    fun updateGenerateTaskCode(
        state: StreamingSessionState,
        code: CharSequence,
        phase: GenerateTaskState.Phase,
        status: String,
        filePath: String? = null,
        isModification: Boolean = false,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!force && state.lastGenerateTaskUpdateAt != 0L &&
            now - state.lastGenerateTaskUpdateAt < updateIntervalMs
        ) {
            return
        }
        state.lastGenerateTaskUpdateAt = now
        updateGenerateTaskState(
            GenerateTaskState(
                sessionId = state.sessionId,
                phase = phase,
                code = code.toString(),
                filePath = filePath,
                status = status,
                isModification = isModification,
                updatedAt = now
            )
        )
    }

    fun updateGenerateTaskState(taskState: GenerateTaskState) {
        val states = taskStates.value.orEmpty().toMutableMap()
        val previous = states[taskState.sessionId]
        val storedState = if (previous != null && taskState.harnessEvents.isEmpty()) {
            taskState.copy(
                harnessIteration = maxOf(previous.harnessIteration, taskState.harnessIteration),
                harnessEvents = previous.harnessEvents,
                diagnostics = taskState.diagnostics.ifEmpty { previous.diagnostics }
            )
        } else {
            taskState
        }
        states[taskState.sessionId] = storedState
        taskStates.value = states
        reportHarnessProgress(storedState)
    }

    fun replaceGenerateTaskState(taskState: GenerateTaskState) {
        val states = taskStates.value.orEmpty().toMutableMap()
        states[taskState.sessionId] = taskState
        taskStates.value = states
        reportHarnessProgress(taskState)
    }

    private fun reportHarnessProgress(taskState: GenerateTaskState) {
        val execution = streamingStateForSession(taskState.sessionId) ?: return
        val reporter = execution.harnessProgressReporter ?: return
        val latestEvent = taskState.harnessEvents.lastOrNull()
        val fingerprint = buildString {
            append(taskState.phase.name).append('|')
            append(latestEvent?.stage?.name).append('|')
            append(latestEvent?.operation).append('|')
            append(latestEvent?.outcome?.name).append('|')
            append(latestEvent?.timestamp)
        }
        val now = System.currentTimeMillis()
        val isTerminal = taskState.phase == GenerateTaskState.Phase.COMPLETED ||
            taskState.phase == GenerateTaskState.Phase.ERROR
        val shouldReport = isTerminal ||
            fingerprint != execution.lastHarnessProgressFingerprint ||
            now - execution.lastHarnessProgressUpdateAt >= notificationUpdateIntervalMs
        if (!shouldReport) return

        execution.lastHarnessProgressFingerprint = fingerprint
        execution.lastHarnessProgressUpdateAt = now
        when (taskState.phase) {
            GenerateTaskState.Phase.COMPLETED -> reporter.succeed(
                message = taskState.status,
                diagnostics = taskState.diagnostics
            )
            GenerateTaskState.Phase.ERROR -> reporter.fail(
                message = taskState.status,
                diagnostics = taskState.diagnostics
            )
            else -> reporter.report(
                message = taskState.status,
                stage = latestEvent?.stage?.name ?: taskState.phase.name,
                operation = latestEvent?.operation,
                iteration = maxOf(taskState.harnessIteration, latestEvent?.iteration ?: 0),
                progress = harnessProgressPercent(taskState, latestEvent),
                diagnostics = taskState.diagnostics
            )
        }
    }

    private fun harnessProgressPercent(
        taskState: GenerateTaskState,
        latestEvent: GenerateTaskState.HarnessEvent?
    ): Int {
        return when (latestEvent?.stage) {
            GenerateTaskState.Stage.INPUT -> 5
            GenerateTaskState.Stage.PROMPT -> 10
            GenerateTaskState.Stage.MODEL -> 25
            GenerateTaskState.Stage.TOOL -> 40
            GenerateTaskState.Stage.BUILD -> 55
            GenerateTaskState.Stage.INSPECT -> 68
            GenerateTaskState.Stage.SELF_TEST -> 78
            GenerateTaskState.Stage.TEST -> 92
            GenerateTaskState.Stage.DELIVER -> 100
            null -> when (taskState.phase) {
                GenerateTaskState.Phase.SANITIZING -> 5
                GenerateTaskState.Phase.PROMPTING,
                GenerateTaskState.Phase.PREPARING -> 10
                GenerateTaskState.Phase.REQUESTING_MODEL,
                GenerateTaskState.Phase.WRITING_INITIAL,
                GenerateTaskState.Phase.WRITING_DIFF,
                GenerateTaskState.Phase.REPAIRING -> 25
                GenerateTaskState.Phase.APPLYING_DIFF,
                GenerateTaskState.Phase.APPLYING_TOOL -> 40
                GenerateTaskState.Phase.BUILDING -> 55
                GenerateTaskState.Phase.INSPECTING -> 68
                GenerateTaskState.Phase.SELF_TESTING -> 78
                GenerateTaskState.Phase.TESTING -> 92
                GenerateTaskState.Phase.COMPLETED -> 100
                GenerateTaskState.Phase.ERROR -> 0
            }
        }
    }

    fun updateHarnessStage(
        state: StreamingSessionState,
        phase: GenerateTaskState.Phase,
        stage: GenerateTaskState.Stage,
        outcome: GenerateTaskState.Outcome,
        message: String,
        iteration: Int = 1,
        diagnostics: List<String> = emptyList(),
        filePath: String? = null,
        isModification: Boolean = false
    ) {
        val current = taskStates.value.orEmpty()[state.sessionId]
        val base = current ?: GenerateTaskState(
            sessionId = state.sessionId,
            phase = phase,
            status = message,
            isModification = isModification
        )
        val next = base.copy(
            phase = phase,
            filePath = filePath ?: base.filePath,
            status = message,
            isModification = isModification || base.isModification,
            harnessIteration = maxOf(base.harnessIteration, iteration),
            diagnostics = diagnostics,
            updatedAt = System.currentTimeMillis()
        ).appendHarnessEvent(
            GenerateTaskState.HarnessEvent(
                stage = stage,
                outcome = outcome,
                message = message,
                iteration = iteration,
                diagnostics = diagnostics
            )
        )
        updateGenerateTaskState(next)
    }

    fun updateProjectHarnessEvent(
        state: StreamingSessionState,
        event: GenerateTaskState.HarnessEvent,
        isModification: Boolean,
        entryPath: String? = null,
        projectFiles: List<String> = emptyList()
    ) {
        val phase = when (event.stage) {
            GenerateTaskState.Stage.INPUT -> GenerateTaskState.Phase.SANITIZING
            GenerateTaskState.Stage.PROMPT -> GenerateTaskState.Phase.PROMPTING
            GenerateTaskState.Stage.MODEL -> GenerateTaskState.Phase.REQUESTING_MODEL
            GenerateTaskState.Stage.TOOL -> GenerateTaskState.Phase.APPLYING_TOOL
            GenerateTaskState.Stage.BUILD -> GenerateTaskState.Phase.BUILDING
            GenerateTaskState.Stage.INSPECT -> GenerateTaskState.Phase.INSPECTING
            GenerateTaskState.Stage.SELF_TEST -> GenerateTaskState.Phase.SELF_TESTING
            GenerateTaskState.Stage.TEST -> GenerateTaskState.Phase.TESTING
            GenerateTaskState.Stage.DELIVER -> if (
                event.outcome == GenerateTaskState.Outcome.FAILED
            ) {
                GenerateTaskState.Phase.ERROR
            } else {
                GenerateTaskState.Phase.BUILDING
            }
        }
        val current = taskStates.value.orEmpty()[state.sessionId]
        val base = current ?: GenerateTaskState(
            sessionId = state.sessionId,
            phase = phase,
            status = event.message,
            isModification = isModification
        )
        updateGenerateTaskState(
            base.copy(
                phase = phase,
                filePath = entryPath ?: base.filePath,
                projectFiles = projectFiles.ifEmpty { base.projectFiles },
                status = event.message,
                isModification = isModification,
                harnessIteration = maxOf(base.harnessIteration, event.iteration),
                diagnostics = event.diagnostics,
                updatedAt = event.timestamp
            ).appendHarnessEvent(event)
        )
    }

    fun updateProjectFileState(
        state: StreamingSessionState,
        relativePath: String,
        content: String,
        entryPath: String?,
        projectFiles: List<String>,
        isModification: Boolean
    ) {
        val current = taskStates.value.orEmpty()[state.sessionId]
        val base = current ?: GenerateTaskState(
            sessionId = state.sessionId,
            phase = GenerateTaskState.Phase.APPLYING_TOOL,
            status = "正在生成多文件项目",
            isModification = isModification
        )
        updateGenerateTaskState(
            base.copy(
                phase = GenerateTaskState.Phase.APPLYING_TOOL,
                code = content,
                filePath = entryPath ?: base.filePath,
                activeFilePath = relativePath,
                projectFiles = projectFiles,
                status = "已写入 $relativePath · ${projectFiles.size} 个项目文件",
                isModification = isModification,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun markGenerateTaskError(
        state: StreamingSessionState,
        message: String,
        code: String = "",
        filePath: String? = null,
        isModification: Boolean = false
    ) {
        updateGenerateTaskState(
            GenerateTaskState(
                sessionId = state.sessionId,
                phase = GenerateTaskState.Phase.ERROR,
                code = code,
                filePath = filePath,
                status = message,
                isModification = isModification
            )
        )
    }


}
