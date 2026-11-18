package com.hfad.starlit.webapp.runtime

import com.hfad.starlit.data.logging.harness.HarnessTraceLogger
import com.hfad.starlit.data.logging.harness.HarnessTraceStatus
import com.hfad.starlit.data.logging.harness.elapsedMillisSince
import com.hfad.starlit.data.logging.harness.record
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

internal object WebProjectContentServerFactory {
    private const val TRACE_COMPONENT = "CONTENT_SERVER"
    private const val UNTRACKED_RUN_ID = "untracked"
    private val REVISION_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    private val NO_OP_TRACE_LOGGER = HarnessTraceLogger {}

    internal fun create(
        projectRoot: File,
        entryFile: File,
        projectId: String? = null,
        revision: String? = null,
        limits: WebProjectContentServer.Limits = WebProjectContentServer.Limits(),
        htmlTransformer: ((String) -> String)? = null,
        runId: String = UNTRACKED_RUN_ID,
        iteration: Int? = null,
        traceLogger: HarnessTraceLogger = NO_OP_TRACE_LOGGER
    ): WebProjectContentServer {
        val normalizedRunId = runId.ifBlank { UNTRACKED_RUN_ID }
        val createStartedAt = System.nanoTime()
        traceLogger.record(
            runId = normalizedRunId,
            component = TRACE_COMPONENT,
            operation = "create",
            status = HarnessTraceStatus.STARTED,
            message = "开始创建项目内容服务器",
            iteration = iteration,
            details = mapOf(
                "project_root" to projectRoot.path,
                "entry_path" to entryFile.path,
                "html_transformer" to (htmlTransformer != null).toString()
            )
        )
        return try {
            require(projectRoot.exists() && projectRoot.isDirectory) {
                "Project root must be an existing directory"
            }
            require(!Files.isSymbolicLink(projectRoot.toPath())) {
                "Project root must not be a symbolic link"
            }
            val canonicalRoot = projectRoot.canonicalFile
            val requestedEntry = if (entryFile.isAbsolute) entryFile else File(canonicalRoot, entryFile.path)
            val canonicalEntry = requestedEntry.canonicalFile
            require(canonicalEntry.toPath().startsWith(canonicalRoot.toPath())) {
                "Project entry must stay inside the project root"
            }
            require(canonicalEntry.isFile && !Files.isSymbolicLink(requestedEntry.toPath())) {
                "Project entry must be an existing regular file"
            }
            require(canonicalEntry.extension.equals("html", ignoreCase = true) ||
                canonicalEntry.extension.equals("htm", ignoreCase = true)) {
                "Project entry must be an HTML file"
            }

            val scanStartedAt = System.nanoTime()
            traceLogger.record(
                runId = normalizedRunId,
                component = TRACE_COMPONENT,
                operation = "scan_project",
                status = HarnessTraceStatus.STARTED,
                message = "开始扫描项目文件",
                iteration = iteration,
                details = mapOf(
                    "project_root" to canonicalRoot.path,
                    "max_files" to limits.maxFiles.toString(),
                    "max_file_bytes" to limits.maxFileBytes.toString(),
                    "max_project_bytes" to limits.maxProjectBytes.toString(),
                    "max_depth" to limits.maxDepth.toString(),
                    "max_path_chars" to limits.maxRelativePathChars.toString()
                )
            )
            val files = try {
                WebProjectFileScanner.scanProject(canonicalRoot, limits)
            } catch (error: Exception) {
                traceLogger.record(
                    runId = normalizedRunId,
                    component = TRACE_COMPONENT,
                    operation = "scan_project",
                    status = HarnessTraceStatus.FAILED,
                    message = "项目文件扫描失败",
                    iteration = iteration,
                    durationMs = elapsedMillisSince(scanStartedAt),
                    details = mapOf("error_type" to error::class.java.simpleName)
                )
                throw error
            }
            val totalBytes = files.sumOf(WebProjectContentServer.ProjectFile::sizeBytes)
            traceLogger.record(
                runId = normalizedRunId,
                component = TRACE_COMPONENT,
                operation = "scan_project",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "项目文件扫描完成",
                iteration = iteration,
                durationMs = elapsedMillisSince(scanStartedAt),
                details = mapOf(
                    "file_count" to files.size.toString(),
                    "total_bytes" to totalBytes.toString()
                )
            )
            require(files.any { it.file == canonicalEntry }) {
                "Project entry was not found in the validated project tree"
            }
            val resolvedRevision = revision?.trim()?.takeIf(String::isNotEmpty)?.also { value ->
                require(REVISION_PATTERN.matches(value)) { "Project revision contains unsafe characters" }
            } ?: WebProjectFileScanner.computeRevision(files)
            val identity = projectId?.trim()?.takeIf(String::isNotEmpty) ?: canonicalRoot.path
            val hostHash = sha256(identity.toByteArray(Charsets.UTF_8)).take(24)
            val server = WebProjectContentServer(
                projectRoot = canonicalRoot,
                entryFile = canonicalEntry,
                revision = resolvedRevision,
                originHost = "app-$hostHash.starlit.local",
                files = files,
                htmlTransformer = htmlTransformer,
                traceRunId = normalizedRunId,
                traceIteration = iteration,
                traceLogger = traceLogger
            )
            traceLogger.record(
                runId = normalizedRunId,
                component = TRACE_COMPONENT,
                operation = "create",
                status = HarnessTraceStatus.SUCCEEDED,
                message = "项目内容服务器创建完成",
                iteration = iteration,
                durationMs = elapsedMillisSince(createStartedAt),
                details = mapOf(
                    "entry_relative_path" to server.entryRelativePath,
                    "file_count" to files.size.toString(),
                    "total_bytes" to totalBytes.toString(),
                    "revision" to resolvedRevision,
                    "origin_host" to server.originHost,
                    "html_transformer" to (htmlTransformer != null).toString()
                )
            )
            server
        } catch (error: Exception) {
            traceLogger.record(
                runId = normalizedRunId,
                component = TRACE_COMPONENT,
                operation = "create",
                status = HarnessTraceStatus.FAILED,
                message = "项目内容服务器创建失败",
                iteration = iteration,
                durationMs = elapsedMillisSince(createStartedAt),
                details = mapOf("error_type" to error::class.java.simpleName)
            )
            throw error
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
