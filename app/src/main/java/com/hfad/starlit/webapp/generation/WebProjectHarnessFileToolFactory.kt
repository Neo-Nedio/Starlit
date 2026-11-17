package com.hfad.starlit.webapp.generation

import com.hfad.starlit.webapp.harness.HarnessFileTool
import com.hfad.starlit.webapp.harness.HarnessToolResult
import com.hfad.starlit.webapp.project.MutableWebProjectBinding
import com.hfad.starlit.webapp.project.WebAppProjectConventions.PLAN_FILE_NAME
import com.hfad.starlit.webapp.project.WebAppProjectDraftManager
import com.hfad.starlit.webapp.project.WebProjectFileTool
import com.hfad.starlit.webapp.project.WebProjectPlanCodec
import com.hfad.starlit.webapp.project.WebProjectToolExecutionResult

internal class WebProjectHarnessFileToolFactory(
    private val binding: MutableWebProjectBinding,
    private val projectFileTool: WebProjectFileTool,
    private val isModification: Boolean,
    private val onProjectFileChanged: suspend (
        relativePath: String,
        content: String,
        entryPath: String,
        projectFiles: List<String>,
        isModification: Boolean
    ) -> Unit
) {
    fun create(): HarnessFileTool {
        return HarnessFileTool { request ->
            val call = request.call
            val requestedPath = call.arguments[WebProjectFileTool.ARG_PATH]
            val result = when {
                call.name == WebProjectFileTool.TOOL_DELETE_FILE &&
                    requestedPath == PLAN_FILE_NAME -> {
                    WebProjectToolExecutionResult(
                        success = false,
                        output = "project.json 由编排器维护，不能删除",
                        diagnostics = listOf("PROJECT_PLAN_DELETE_FORBIDDEN"),
                        metadata = toolPolicyMetadata(
                            tool = call.name,
                            path = requestedPath,
                            policyCode = "PROJECT_PLAN_DELETE_FORBIDDEN"
                        )
                    )
                }

                call.name == WebProjectFileTool.TOOL_WRITE_FILE &&
                    requestedPath == PLAN_FILE_NAME -> {
                    updateVisibleProjectPlan(call.arguments)
                }

                call.name == WebProjectFileTool.TOOL_WRITE_FILE &&
                    requestedPath != null && requestedPath !in binding.declaredPaths -> {
                    WebProjectToolExecutionResult(
                        success = false,
                        output = "写入新文件前必须先把路径加入 project.json：$requestedPath",
                        diagnostics = listOf("PROJECT_FILE_NOT_DECLARED: $requestedPath"),
                        metadata = toolPolicyMetadata(
                            tool = call.name,
                            path = requestedPath,
                            policyCode = "PROJECT_FILE_NOT_DECLARED"
                        )
                    )
                }

                call.name == WebProjectFileTool.TOOL_DELETE_FILE &&
                    requestedPath != null && requestedPath in binding.declaredPaths -> {
                    WebProjectToolExecutionResult(
                        success = false,
                        output = "删除文件前必须先从 project.json 移除声明：$requestedPath",
                        diagnostics = listOf("PROJECT_FILE_STILL_DECLARED: $requestedPath"),
                        metadata = toolPolicyMetadata(
                            tool = call.name,
                            path = requestedPath,
                            policyCode = "PROJECT_FILE_STILL_DECLARED"
                        )
                    )
                }

                else -> {
                    val arguments = if (
                        call.name == WebProjectFileTool.TOOL_WRITE_FILE &&
                        requestedPath == binding.manifest.entryPoint
                    ) {
                        call.arguments + (
                            WebProjectFileTool.ARG_CONTENT to AppGenerator.ensureWebAppIdentity(
                                call.arguments[WebProjectFileTool.ARG_CONTENT].orEmpty()
                            )
                        )
                    } else {
                        call.arguments
                    }
                    projectFileTool.execute(call.name, arguments)
                }
            }

            if (result.success && result.changedFiles.isNotEmpty()) {
                val projectFiles = WebAppProjectDraftManager.projectFilePaths(projectFileTool)
                val activePath = result.changedFiles.last()
                val activeContent = runCatching { projectFileTool.read(activePath).content }
                    .getOrDefault("")
                onProjectFileChanged(
                    activePath,
                    activeContent,
                    binding.entryFile.absolutePath,
                    projectFiles,
                    isModification
                )
            }
            HarnessToolResult(
                callId = call.id,
                success = result.success,
                output = result.output,
                diagnostics = result.diagnostics,
                changedFiles = result.changedFiles,
                artifactPath = binding.entryFile.absolutePath,
                metadata = result.metadata
            )
        }
    }

    private fun updateVisibleProjectPlan(
        arguments: Map<String, String>
    ): WebProjectToolExecutionResult {
        val content = arguments[WebProjectFileTool.ARG_CONTENT]
            ?: return WebProjectToolExecutionResult(
                success = false,
                output = "write_file requires argument: content",
                diagnostics = listOf("PROJECT_PLAN_CONTENT_MISSING"),
                metadata = mapOf(
                    "tool" to WebProjectFileTool.TOOL_WRITE_FILE,
                    "path" to PLAN_FILE_NAME,
                    "error_type" to "MissingArgument",
                    "failure_stage" to "arguments",
                    "policy_code" to "PROJECT_PLAN_CONTENT_MISSING"
                )
            )
        val parsedManifest = runCatching {
            WebProjectPlanCodec.parse(content).manifest.copy(
                projectId = binding.manifest.projectId,
                stateFile = binding.manifest.stateFile
            )
        }.getOrElse { error ->
            return WebProjectToolExecutionResult(
                success = false,
                output = error.message ?: "project.json 格式无效",
                diagnostics = listOf("PROJECT_PLAN_INVALID: ${error.message.orEmpty()}"),
                metadata = toolPolicyMetadata(
                    tool = WebProjectFileTool.TOOL_WRITE_FILE,
                    path = PLAN_FILE_NAME,
                    policyCode = "PROJECT_PLAN_INVALID"
                ) + ("error_type" to error::class.java.simpleName)
            )
        }
        if (parsedManifest.entryPoint != binding.manifest.entryPoint) {
            return WebProjectToolExecutionResult(
                success = false,
                output = "当前任务不能改变项目入口 ${binding.manifest.entryPoint}",
                diagnostics = listOf("PROJECT_ENTRY_CHANGE_FORBIDDEN"),
                metadata = toolPolicyMetadata(
                    tool = WebProjectFileTool.TOOL_WRITE_FILE,
                    path = PLAN_FILE_NAME,
                    policyCode = "PROJECT_ENTRY_CHANGE_FORBIDDEN"
                )
            )
        }
        if (!WebAppProjectDraftManager.preservesHostOwnedAppSpec(
                binding.manifest,
                parsedManifest
            )
        ) {
            return WebProjectToolExecutionResult(
                success = false,
                output = "当前任务不能删除或改写宿主已有的 AppSpec",
                diagnostics = listOf("PROJECT_APP_SPEC_CHANGE_FORBIDDEN"),
                metadata = toolPolicyMetadata(
                    tool = WebProjectFileTool.TOOL_WRITE_FILE,
                    path = PLAN_FILE_NAME,
                    policyCode = "PROJECT_APP_SPEC_CHANGE_FORBIDDEN"
                )
            )
        }

        val writeResult = projectFileTool.execute(
            WebProjectFileTool.TOOL_WRITE_FILE,
            arguments + (
                WebProjectFileTool.ARG_CONTENT to
                    WebAppProjectDraftManager.renderVisibleProjectPlan(parsedManifest)
                )
        )
        if (!writeResult.success) return writeResult
        binding.manifest = parsedManifest
        return writeResult
    }

    private fun toolPolicyMetadata(
        tool: String,
        path: String?,
        policyCode: String
    ): Map<String, String> = buildMap {
        put("tool", tool)
        path?.let { put("path", it) }
        put("error_type", "PolicyRejected")
        put("failure_stage", "policy")
        put("policy_code", policyCode)
    }
}
