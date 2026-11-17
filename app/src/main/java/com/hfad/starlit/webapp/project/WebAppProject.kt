package com.hfad.starlit.webapp.project

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

enum class WebAppProjectFileRole {
    ENTRY,
    STYLE,
    SCRIPT,
    DATA,
    ASSET,
    OTHER
}

data class WebAppProjectFile(
    val path: String,
    val role: WebAppProjectFileRole = WebAppProjectFileRole.OTHER,
    val required: Boolean = true,
    val sha256: String? = null,
    val description: String = "",
    val dependsOn: List<String> = emptyList(),
    val ownsCriteria: List<String> = emptyList()
)

data class WebAppProjectManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val projectId: String,
    val displayName: String,
    val entryPoint: String = DEFAULT_ENTRY_POINT,
    val stateFile: String? = null,
    val files: List<WebAppProjectFile> = emptyList(),
    val appSpec: WebAppSpec? = null
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_ENTRY_POINT = "index.html"
    }
}

enum class WebAppProjectSnapshotKind {
    DRAFT,
    RELEASE,
    LEGACY
}

data class WebAppProjectSnapshot(
    val projectRoot: File,
    val contentRoot: File,
    val entryFile: File,
    val manifest: WebAppProjectManifest,
    val kind: WebAppProjectSnapshotKind,
    val version: Long? = null
) {
    val artifactPath: String
        get() = entryFile.absolutePath
}

data class WebAppProjectVersions(
    val latestDraftVersion: Long,
    val latestReleaseVersion: Long,
    val activeReleaseVersion: Long?
)

enum class WebAppProjectSelection {
    PREFER_RELEASE,
    LATEST_DRAFT,
    ACTIVE_RELEASE
}

enum class WebProjectDiagnosticSeverity {
    WARNING,
    ERROR
}

data class WebProjectValidationDiagnostic(
    val severity: WebProjectDiagnosticSeverity,
    val code: String,
    val message: String,
    val path: String? = null
)

data class WebProjectValidationReport(
    val diagnostics: List<WebProjectValidationDiagnostic>,
    val fileCount: Int = 0,
    val totalBytes: Long = 0L
) {
    val passed: Boolean
        get() = diagnostics.none { it.severity == WebProjectDiagnosticSeverity.ERROR }

    fun requirePassed() {
        if (!passed) throw WebAppProjectValidationException(this)
    }
}

class WebAppProjectValidationException(
    val report: WebProjectValidationReport
) : IllegalStateException(
    report.diagnostics
        .filter { it.severity == WebProjectDiagnosticSeverity.ERROR }
        .joinToString("; ") { diagnostic ->
            buildString {
                append(diagnostic.code).append(": ").append(diagnostic.message)
                diagnostic.path?.let { append(" (").append(it).append(')') }
            }
        }
        .ifBlank { "Web project validation failed" }
)

class WebAppProjectException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

object WebAppProjectManifestCodec {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun read(file: File): WebAppProjectManifest {
        if (!file.isFile) throw WebAppProjectException("Project manifest does not exist: ${file.path}")
        val decoded = runCatching {
            gson.fromJson(file.readText(), WebAppProjectManifest::class.java)
        }.getOrElse { error ->
            throw WebAppProjectException("Unable to read project manifest: ${file.path}", error)
        } ?: throw WebAppProjectException("Project manifest is empty: ${file.path}")
        val manifest = runCatching {
            decoded.copy(
                files = decoded.files.orEmpty().map { projectFile ->
                    projectFile.copy(
                        description = projectFile.description.orEmpty(),
                        dependsOn = projectFile.dependsOn.orEmpty(),
                        ownsCriteria = projectFile.ownsCriteria.orEmpty()
                    )
                }
            )
        }.getOrElse { error ->
            throw WebAppProjectException("Project manifest is invalid: ${file.path}", error)
        }
        val diagnostics = runCatching { WebAppProjectValidator.validateManifest(manifest) }
            .getOrElse { error ->
                throw WebAppProjectException("Project manifest is invalid: ${file.path}", error)
            }
        if (diagnostics.any { it.severity == WebProjectDiagnosticSeverity.ERROR }) {
            throw WebAppProjectValidationException(WebProjectValidationReport(diagnostics))
        }
        return manifest
    }

    fun write(file: File, manifest: WebAppProjectManifest) {
        val diagnostics = WebAppProjectValidator.validateManifest(manifest)
        if (diagnostics.any { it.severity == WebProjectDiagnosticSeverity.ERROR }) {
            throw WebAppProjectValidationException(WebProjectValidationReport(diagnostics))
        }
        WebProjectPaths.writeTextAtomically(file, gson.toJson(manifest) + "\n")
    }
}
