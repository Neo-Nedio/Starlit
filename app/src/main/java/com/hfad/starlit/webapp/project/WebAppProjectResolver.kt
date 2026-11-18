package com.hfad.starlit.webapp.project

import java.io.File
import java.util.Locale

object WebAppProjectResolver {
    fun resolve(
        input: File,
        selection: WebAppProjectSelection = WebAppProjectSelection.PREFER_RELEASE
    ): WebAppProjectSnapshot {
        val target = input.absoluteFile
        if (!target.exists()) {
            throw WebAppProjectException("Project path does not exist: ${target.path}")
        }
        val managedRoot = findManagedProjectRoot(target)
        if (managedRoot != null) {
            val selectedContent = detectManagedContentRoot(managedRoot, target)
            if (selectedContent != null) {
                return managedSnapshot(managedRoot, selectedContent)
            }
            return selectManagedSnapshot(managedRoot, selection)
        }
        return resolveLegacy(target)
    }

    private fun selectManagedSnapshot(
        projectRoot: File,
        selection: WebAppProjectSelection
    ): WebAppProjectSnapshot {
        val workspace = WebAppProjectWorkspace()
        return when (selection) {
            WebAppProjectSelection.PREFER_RELEASE ->
                workspace.activeRelease(projectRoot) ?: workspace.latestDraft(projectRoot)

            WebAppProjectSelection.LATEST_DRAFT -> workspace.latestDraft(projectRoot)
            WebAppProjectSelection.ACTIVE_RELEASE -> workspace.activeRelease(projectRoot)
        } ?: throw WebAppProjectException("Project has no selectable draft or release: ${projectRoot.path}")
    }

    private fun managedSnapshot(
        projectRoot: File,
        selected: ManagedContentRoot
    ): WebAppProjectSnapshot {
        val manifest = WebAppProjectManifestCodec.read(WebAppProjectWorkspace.manifestFile(projectRoot))
        return WebAppProjectSnapshot(
            projectRoot = projectRoot.canonicalFile,
            contentRoot = selected.root.canonicalFile,
            entryFile = WebProjectPaths.resolve(selected.root, manifest.entryPoint),
            manifest = manifest,
            kind = selected.kind,
            version = selected.version
        )
    }

    private fun findManagedProjectRoot(input: File): File? {
        var current = if (input.isDirectory) input else input.parentFile
        while (current != null) {
            if (WebAppProjectWorkspace.manifestFile(current).isFile &&
                WebAppProjectWorkspace.workspaceStateFile(current).isFile
            ) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    private fun detectManagedContentRoot(
        projectRoot: File,
        input: File
    ): ManagedContentRoot? {
        val rootPath = projectRoot.canonicalFile.toPath()
        val inputPath = input.canonicalFile.toPath()
        if (!inputPath.startsWith(rootPath)) return null
        val relative = rootPath.relativize(inputPath)
        if (relative.nameCount < 2) return null
        val kind = when (relative.getName(0).toString()) {
            WebAppProjectWorkspace.DRAFTS_DIRECTORY_NAME -> WebAppProjectSnapshotKind.DRAFT
            WebAppProjectWorkspace.RELEASES_DIRECTORY_NAME -> WebAppProjectSnapshotKind.RELEASE
            else -> return null
        }
        val versionName = relative.getName(1).toString()
        val version = WebAppProjectWorkspace.parseVersion(versionName) ?: return null
        val contentRoot = File(projectRoot, "${relative.getName(0)}/$versionName")
        if (!contentRoot.isDirectory) return null
        return ManagedContentRoot(contentRoot, kind, version)
    }

    private fun resolveLegacy(input: File): WebAppProjectSnapshot {
        val entry = when {
            input.isFile && isHtmlFile(input) -> input
            input.isDirectory -> chooseLegacyEntry(input)
            else -> null
        } ?: throw WebAppProjectException("Legacy project has no HTML entry point: ${input.path}")
        val root = entry.parentFile?.canonicalFile
            ?: throw WebAppProjectException("Legacy entry point has no parent directory: ${entry.path}")
        val projectId = "legacy-${WebProjectPaths.sha256(root.absolutePath.toByteArray()).take(16)}"
        val stateFile = File(root, "${entry.nameWithoutExtension}.json")
            .takeIf(File::isFile)
            ?.name
        val manifest = WebAppProjectManifest(
            projectId = projectId,
            displayName = root.name.ifBlank { entry.nameWithoutExtension },
            entryPoint = entry.name,
            stateFile = stateFile,
            files = listOf(
                WebAppProjectFile(entry.name, WebAppProjectFileRole.ENTRY),
                *stateFile?.let {
                    arrayOf(WebAppProjectFile(it, WebAppProjectFileRole.DATA))
                }.orEmpty()
            )
        )
        return WebAppProjectSnapshot(
            projectRoot = root,
            contentRoot = root,
            entryFile = entry.canonicalFile,
            manifest = manifest,
            kind = WebAppProjectSnapshotKind.LEGACY
        )
    }

    private fun chooseLegacyEntry(directory: File): File? {
        val htmlFiles = directory.listFiles()
            ?.asSequence()
            ?.filter(File::isFile)
            ?.filter(::isHtmlFile)
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.toList()
            .orEmpty()
        return htmlFiles.firstOrNull { it.nameWithoutExtension == directory.name }
            ?: htmlFiles.firstOrNull { it.name.equals("index.html", ignoreCase = true) }
            ?: htmlFiles.firstOrNull()
    }

    private fun isHtmlFile(file: File): Boolean =
        file.extension.equals("html", ignoreCase = true) ||
            file.extension.equals("htm", ignoreCase = true)

    private data class ManagedContentRoot(
        val root: File,
        val kind: WebAppProjectSnapshotKind,
        val version: Long
    )
}

data class WebAppProjectValidationPolicy(
    val maxFiles: Int = 512,
    val maxFileBytes: Long = 8L * 1024L * 1024L,
    val maxTotalBytes: Long = 32L * 1024L * 1024L,
    val forbidRemoteResources: Boolean = true,
    val allowedExtensions: Set<String> = DEFAULT_ALLOWED_EXTENSIONS
) {
    init {
        require(maxFiles > 0)
        require(maxFileBytes > 0L)
        require(maxTotalBytes > 0L)
        require(allowedExtensions.isNotEmpty())
    }

    companion object {
        val DEFAULT_ALLOWED_EXTENSIONS = setOf(
            "html", "htm", "css", "js", "mjs", "json", "svg", "txt", "md",
            "png", "jpg", "jpeg", "gif", "webp", "avif", "ico",
            "woff", "woff2", "ttf", "otf", "wasm", "mp3", "wav", "ogg", "mp4", "webm"
        )
    }
}

