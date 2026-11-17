package com.hfad.starlit.webapp.project

import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

class WebAppProjectWorkspace(
    private val validator: WebAppProjectValidator = WebAppProjectValidator()
) {
    fun create(projectRoot: File, manifest: WebAppProjectManifest): WebAppProjectSnapshot =
        synchronized(WORKSPACE_LOCK) {
            val root = projectRoot.absoluteFile
            if (metadataDirectory(root).exists()) {
                throw WebAppProjectException("Project workspace already exists: ${root.path}")
            }
            if (root.isDirectory && root.listFiles().orEmpty().isNotEmpty()) {
                throw WebAppProjectException(
                    "Project root is not empty; resolve and adopt it as a legacy project instead"
                )
            }
            if (!root.exists() && !root.mkdirs()) {
                throw WebAppProjectException("Unable to create project root: ${root.path}")
            }
            if (!root.isDirectory) {
                throw WebAppProjectException("Project root must be a directory: ${root.path}")
            }
            try {
                metadataDirectory(root).mkdirsOrThrow()
                draftsDirectory(root).mkdirsOrThrow()
                releasesDirectory(root).mkdirsOrThrow()
                WebAppProjectManifestCodec.write(manifestFile(root), manifest)
                writeState(root, WebAppProjectWorkspaceState())
                WebProjectPaths.writeTextAtomically(runtimeStateFile(root), "{}")
                createDraftLocked(root, copyFromActiveRelease = false)
            } catch (error: Exception) {
                metadataDirectory(root).deleteRecursively()
                draftsDirectory(root).deleteRecursively()
                releasesDirectory(root).deleteRecursively()
                throw error
            }
        }

    fun adoptLegacy(
        legacy: WebAppProjectSnapshot,
        manifest: WebAppProjectManifest = legacy.manifest
    ): WebAppProjectSnapshot = synchronized(WORKSPACE_LOCK) {
        if (legacy.kind != WebAppProjectSnapshotKind.LEGACY) {
            throw WebAppProjectException("Only legacy projects can be adopted")
        }
        val root = legacy.projectRoot.canonicalFile
        val contentRoot = legacy.contentRoot.canonicalFile
        if (root != contentRoot || !root.isDirectory) {
            throw WebAppProjectException("Legacy project content root must match its project root")
        }
        val reservedPaths = listOf(
            metadataDirectory(root),
            draftsDirectory(root),
            releasesDirectory(root)
        )
        reservedPaths.firstOrNull(File::exists)?.let { reserved ->
            throw WebAppProjectException("Legacy project contains reserved managed path: ${reserved.name}")
        }
        val manifestDiagnostics = WebAppProjectValidator.validateManifest(manifest)
        if (manifestDiagnostics.any { it.severity == WebProjectDiagnosticSeverity.ERROR }) {
            throw WebAppProjectValidationException(WebProjectValidationReport(manifestDiagnostics))
        }
        val sourceEntry = WebProjectPaths.resolve(root, manifest.entryPoint)
        if (!sourceEntry.isFile) {
            throw WebAppProjectException("Legacy entry point does not exist: ${manifest.entryPoint}")
        }
        val legacyRuntimeState = manifest.stateFile
            ?.let { statePath -> WebProjectPaths.resolve(root, statePath) }
            ?.takeIf(File::isFile)
            ?.readText()
            ?: "{}"

        val parent = root.parentFile
            ?: throw WebAppProjectException("Legacy project root has no parent directory")
        val stagingRoot = File(parent, ".${root.name}.adopt-${UUID.randomUUID()}")
        var managedPathsCreated = false
        try {
            WebProjectPaths.copyDirectory(root, stagingRoot)
            val stagedEntry = WebProjectPaths.resolve(stagingRoot, manifest.entryPoint)
            if (!stagedEntry.isFile) {
                throw WebAppProjectException("Copied legacy entry point is missing: ${manifest.entryPoint}")
            }

            managedPathsCreated = true
            metadataDirectory(root).mkdirsOrThrow()
            draftsDirectory(root).mkdirsOrThrow()
            releasesDirectory(root).mkdirsOrThrow()
            val firstDraftRoot = versionDirectory(draftsDirectory(root), 1L)
            WebProjectPaths.moveAtomically(stagingRoot, firstDraftRoot)
            WebAppProjectManifestCodec.write(manifestFile(root), manifest)
            writeState(
                root,
                WebAppProjectWorkspaceState(latestDraftVersion = 1L)
            )
            WebProjectPaths.writeTextAtomically(runtimeStateFile(root), legacyRuntimeState)
            snapshot(
                projectRoot = root,
                contentRoot = firstDraftRoot,
                manifest = manifest,
                kind = WebAppProjectSnapshotKind.DRAFT,
                version = 1L
            )
        } catch (error: Exception) {
            stagingRoot.deleteRecursively()
            if (managedPathsCreated) {
                metadataDirectory(root).deleteRecursively()
                draftsDirectory(root).deleteRecursively()
                releasesDirectory(root).deleteRecursively()
            }
            throw error
        }
    }

    fun createDraft(
        projectRoot: File,
        copyFromActiveRelease: Boolean = true
    ): WebAppProjectSnapshot = synchronized(WORKSPACE_LOCK) {
        createDraftLocked(projectRoot.absoluteFile, copyFromActiveRelease)
    }

    fun publishDraft(projectRoot: File, draftVersion: Long): WebAppProjectSnapshot =
        synchronized(WORKSPACE_LOCK) {
            val root = projectRoot.absoluteFile
            publishDraftLocked(
                projectRoot = root,
                draftVersion = draftVersion,
                manifest = readManifest(root),
                replaceManifest = false
            )
        }

    fun publishDraft(
        projectRoot: File,
        draftVersion: Long,
        manifest: WebAppProjectManifest
    ): WebAppProjectSnapshot = synchronized(WORKSPACE_LOCK) {
        val root = projectRoot.absoluteFile
        val currentManifest = readManifest(root)
        if (manifest.projectId != currentManifest.projectId) {
            throw WebAppProjectException("Project ID cannot change during publish")
        }
        publishDraftLocked(
            projectRoot = root,
            draftVersion = draftVersion,
            manifest = manifest,
            replaceManifest = manifest != currentManifest,
            previousManifest = currentManifest
        )
    }

    fun latestDraft(projectRoot: File): WebAppProjectSnapshot? = synchronized(WORKSPACE_LOCK) {
        val root = projectRoot.absoluteFile
        val state = readState(root)
        state.latestDraftVersion.takeIf { it > 0L }?.let { version ->
            snapshot(
                projectRoot = root,
                contentRoot = versionDirectory(draftsDirectory(root), version),
                manifest = readManifest(root),
                kind = WebAppProjectSnapshotKind.DRAFT,
                version = version
            )
        }
    }

    fun activeRelease(projectRoot: File): WebAppProjectSnapshot? = synchronized(WORKSPACE_LOCK) {
        val root = projectRoot.absoluteFile
        val state = readState(root)
        state.activeReleaseVersion?.let { version ->
            snapshot(
                projectRoot = root,
                contentRoot = versionDirectory(releasesDirectory(root), version),
                manifest = readManifest(root),
                kind = WebAppProjectSnapshotKind.RELEASE,
                version = version
            )
        }
    }

    fun versions(projectRoot: File): WebAppProjectVersions = synchronized(WORKSPACE_LOCK) {
        readState(projectRoot.absoluteFile).toVersions()
    }

    fun readManifest(projectRoot: File): WebAppProjectManifest =
        WebAppProjectManifestCodec.read(manifestFile(projectRoot.absoluteFile))

    fun saveManifest(projectRoot: File, manifest: WebAppProjectManifest) =
        synchronized(WORKSPACE_LOCK) {
            val root = projectRoot.absoluteFile
            readState(root)
            WebAppProjectManifestCodec.write(manifestFile(root), manifest)
        }

    private fun createDraftLocked(
        projectRoot: File,
        copyFromActiveRelease: Boolean
    ): WebAppProjectSnapshot {
        val manifest = readManifest(projectRoot)
        val state = readState(projectRoot)
        val draftVersion = state.latestDraftVersion + 1L
        val finalRoot = versionDirectory(draftsDirectory(projectRoot), draftVersion)
        if (finalRoot.exists()) {
            throw WebAppProjectException("Draft version already exists: $draftVersion")
        }
        val stagingRoot = File(
            draftsDirectory(projectRoot),
            ".staging-${versionName(draftVersion)}-${UUID.randomUUID()}"
        )
        try {
            val activeReleaseRoot = state.activeReleaseVersion
                ?.takeIf { copyFromActiveRelease }
                ?.let { versionDirectory(releasesDirectory(projectRoot), it) }
                ?.takeIf(File::isDirectory)
            if (activeReleaseRoot != null) {
                WebProjectPaths.copyDirectory(activeReleaseRoot, stagingRoot)
            } else {
                stagingRoot.mkdirsOrThrow()
            }
            WebProjectPaths.moveAtomically(stagingRoot, finalRoot)
            writeState(
                projectRoot,
                state.copy(latestDraftVersion = draftVersion)
            )
            return snapshot(
                projectRoot = projectRoot,
                contentRoot = finalRoot,
                manifest = manifest,
                kind = WebAppProjectSnapshotKind.DRAFT,
                version = draftVersion
            )
        } catch (error: Exception) {
            stagingRoot.deleteRecursively()
            throw error
        }
    }

    private fun publishDraftLocked(
        projectRoot: File,
        draftVersion: Long,
        manifest: WebAppProjectManifest,
        replaceManifest: Boolean,
        previousManifest: WebAppProjectManifest? = null
    ): WebAppProjectSnapshot {
        val state = readState(projectRoot)
        val draftRoot = versionDirectory(draftsDirectory(projectRoot), draftVersion)
        if (!draftRoot.isDirectory) {
            throw WebAppProjectException("Draft version does not exist: $draftVersion")
        }
        val draftSnapshot = snapshot(
            projectRoot = projectRoot,
            contentRoot = draftRoot,
            manifest = manifest,
            kind = WebAppProjectSnapshotKind.DRAFT,
            version = draftVersion
        )
        validator.validate(draftSnapshot).requirePassed()

        val releaseVersion = state.latestReleaseVersion + 1L
        val finalRoot = versionDirectory(releasesDirectory(projectRoot), releaseVersion)
        if (finalRoot.exists()) {
            throw WebAppProjectException("Release version already exists: $releaseVersion")
        }
        val stagingRoot = File(
            releasesDirectory(projectRoot),
            ".staging-${versionName(releaseVersion)}-${UUID.randomUUID()}"
        )
        var finalRootCreated = false
        var manifestWriteAttempted = false
        var stateWriteAttempted = false
        try {
            WebProjectPaths.copyDirectory(draftRoot, stagingRoot)
            val stagedSnapshot = snapshot(
                projectRoot = projectRoot,
                contentRoot = stagingRoot,
                manifest = manifest,
                kind = WebAppProjectSnapshotKind.RELEASE,
                version = releaseVersion
            )
            validator.validate(stagedSnapshot).requirePassed()
            WebProjectPaths.moveAtomically(stagingRoot, finalRoot)
            finalRootCreated = true
            val releaseSnapshot = snapshot(
                projectRoot = projectRoot,
                contentRoot = finalRoot,
                manifest = manifest,
                kind = WebAppProjectSnapshotKind.RELEASE,
                version = releaseVersion
            )

            if (replaceManifest) {
                manifestWriteAttempted = true
                WebAppProjectManifestCodec.write(manifestFile(projectRoot), manifest)
            }
            stateWriteAttempted = true
            writeState(
                projectRoot,
                state.copy(
                    latestReleaseVersion = releaseVersion,
                    activeReleaseVersion = releaseVersion
                )
            )
            return releaseSnapshot
        } catch (error: Exception) {
            stagingRoot.deleteRecursively()
            if (stateWriteAttempted) {
                runCatching { writeState(projectRoot, state) }
                    .onFailure(error::addSuppressed)
            }
            if (manifestWriteAttempted && previousManifest != null) {
                runCatching {
                    WebAppProjectManifestCodec.write(manifestFile(projectRoot), previousManifest)
                }.onFailure(error::addSuppressed)
            }
            if (finalRootCreated && finalRoot.exists() && !finalRoot.deleteRecursively()) {
                error.addSuppressed(
                    WebAppProjectException("Unable to roll back release directory: ${finalRoot.path}")
                )
            }
            throw error
        }
    }

    private fun snapshot(
        projectRoot: File,
        contentRoot: File,
        manifest: WebAppProjectManifest,
        kind: WebAppProjectSnapshotKind,
        version: Long
    ): WebAppProjectSnapshot {
        val entryFile = WebProjectPaths.resolve(contentRoot, manifest.entryPoint)
        return WebAppProjectSnapshot(
            projectRoot = projectRoot.canonicalFile,
            contentRoot = contentRoot.canonicalFile,
            entryFile = entryFile,
            manifest = manifest,
            kind = kind,
            version = version
        )
    }

    private fun readState(projectRoot: File): WebAppProjectWorkspaceState {
        val file = workspaceStateFile(projectRoot)
        if (!file.isFile) {
            throw WebAppProjectException("Project workspace state does not exist: ${file.path}")
        }
        val state = runCatching {
            WORKSPACE_GSON.fromJson(file.readText(), WebAppProjectWorkspaceState::class.java)
        }.getOrElse { error ->
            throw WebAppProjectException("Unable to read project workspace state: ${file.path}", error)
        } ?: throw WebAppProjectException("Project workspace state is empty: ${file.path}")
        if (state.schemaVersion != WORKSPACE_SCHEMA_VERSION ||
            state.latestDraftVersion < 0L ||
            state.latestReleaseVersion < 0L ||
            state.activeReleaseVersion?.let { it <= 0L || it > state.latestReleaseVersion } == true
        ) {
            throw WebAppProjectException("Project workspace state is invalid: ${file.path}")
        }
        return state
    }

    private fun writeState(projectRoot: File, state: WebAppProjectWorkspaceState) {
        WebProjectPaths.writeTextAtomically(
            workspaceStateFile(projectRoot),
            WORKSPACE_GSON.toJson(state) + "\n"
        )
    }

    private data class WebAppProjectWorkspaceState(
        val schemaVersion: Int = WORKSPACE_SCHEMA_VERSION,
        val latestDraftVersion: Long = 0L,
        val latestReleaseVersion: Long = 0L,
        val activeReleaseVersion: Long? = null
    ) {
        fun toVersions() = WebAppProjectVersions(
            latestDraftVersion = latestDraftVersion,
            latestReleaseVersion = latestReleaseVersion,
            activeReleaseVersion = activeReleaseVersion
        )
    }

    companion object {
        const val METADATA_DIRECTORY_NAME = ".starlit"
        const val MANIFEST_FILE_NAME = "project.json"
        const val WORKSPACE_STATE_FILE_NAME = "workspace.json"
        const val RUNTIME_STATE_FILE_NAME = "app-state.json"
        const val DRAFTS_DIRECTORY_NAME = "drafts"
        const val RELEASES_DIRECTORY_NAME = "releases"
        private const val WORKSPACE_SCHEMA_VERSION = 1
        private val WORKSPACE_GSON = GsonBuilder().setPrettyPrinting().create()
        private val WORKSPACE_LOCK = Any()

        fun manifestFile(projectRoot: File): File =
            File(metadataDirectory(projectRoot), MANIFEST_FILE_NAME)

        fun workspaceStateFile(projectRoot: File): File =
            File(metadataDirectory(projectRoot), WORKSPACE_STATE_FILE_NAME)

        fun runtimeStateFile(projectRoot: File): File =
            File(metadataDirectory(projectRoot), RUNTIME_STATE_FILE_NAME)

        fun metadataDirectory(projectRoot: File): File =
            File(projectRoot, METADATA_DIRECTORY_NAME)

        fun draftsDirectory(projectRoot: File): File =
            File(projectRoot, DRAFTS_DIRECTORY_NAME)

        fun releasesDirectory(projectRoot: File): File =
            File(projectRoot, RELEASES_DIRECTORY_NAME)

        fun versionDirectory(parent: File, version: Long): File = File(parent, versionName(version))

        fun versionName(version: Long): String {
            require(version > 0L) { "Version must be positive" }
            return "v" + version.toString().padStart(6, '0')
        }

        fun parseVersion(directoryName: String): Long? {
            if (!VERSION_DIRECTORY_REGEX.matches(directoryName)) return null
            return directoryName.drop(1).toLongOrNull()?.takeIf { it > 0L }
        }

        private val VERSION_DIRECTORY_REGEX = Regex("^v[0-9]{6,}$")
    }
}


internal fun File.mkdirsOrThrow() {
    if (!exists() && !mkdirs()) throw WebAppProjectException("Unable to create directory: $path" )
    if (!isDirectory) throw WebAppProjectException("Path must be a directory: $path" )
}
