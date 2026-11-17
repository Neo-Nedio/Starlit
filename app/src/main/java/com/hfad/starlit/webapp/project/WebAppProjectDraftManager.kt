package com.hfad.starlit.webapp.project

import java.io.File
import java.util.Locale

internal data class PreparedWebProject(
    val workspace: WebAppProjectWorkspace,
    val snapshot: WebAppProjectSnapshot
)

internal class MutableWebProjectBinding(
    val workspace: WebAppProjectWorkspace,
    val projectRoot: File,
    val contentRoot: File,
    manifest: WebAppProjectManifest,
    val draftVersion: Long
) {
    var manifest: WebAppProjectManifest = manifest

    val entryFile: File
        get() = File(contentRoot, manifest.entryPoint).absoluteFile

    val declaredPaths: Set<String>
        get() = manifest.files.mapTo(linkedSetOf()) { it.path }
}

internal object WebAppProjectConventions {
    const val PLAN_FILE_NAME = "project.json"
}

internal object WebAppProjectDraftManager {
    private const val DEFAULT_STYLE_PATH = "styles/app.css"
    private const val DEFAULT_SCRIPT_PATH = "scripts/app.js"
    private const val MAX_PLANNED_FILES = 24

    fun prepareExisting(input: File): PreparedWebProject {
        val workspace = WebAppProjectWorkspace()
        val resolved = WebAppProjectResolver.resolve(input)
        val draft = when (resolved.kind) {
            WebAppProjectSnapshotKind.LEGACY -> {
                val adoptedDraft = workspace.adoptLegacy(resolved)
                workspace.publishDraft(
                    projectRoot = adoptedDraft.projectRoot,
                    draftVersion = requireNotNull(adoptedDraft.version),
                    manifest = adoptedDraft.manifest
                )
                workspace.createDraft(adoptedDraft.projectRoot)
            }
            WebAppProjectSnapshotKind.RELEASE -> workspace.createDraft(resolved.projectRoot)
            WebAppProjectSnapshotKind.DRAFT -> resolved
        }
        return PreparedWebProject(workspace, ensureVisibleProjectPlan(draft))
    }

    fun preservesHostOwnedAppSpec(
        baseline: WebAppProjectManifest,
        candidate: WebAppProjectManifest
    ): Boolean {
        return WebAppSpecPolicy.preservesBaseline(
            baseline = baseline.appSpec,
            candidate = candidate.appSpec
        )
    }

    fun renderVisibleProjectPlan(manifest: WebAppProjectManifest): String {
        return WebProjectPlanCodec.render(manifest)
    }

    fun projectFilePaths(projectFileTool: WebProjectFileTool): List<String> {
        return projectFileTool.list(".", recursive = true)
            .asSequence()
            .filterNot { it.directory }
            .map { it.path }
            .sorted()
            .toList()
    }

    fun synchronizeManifest(
        binding: MutableWebProjectBinding,
        projectFileTool: WebProjectFileTool
    ) {
        val planContent = projectFileTool.read(WebAppProjectConventions.PLAN_FILE_NAME).content
        val parsedManifest = WebProjectPlanCodec.parse(planContent).manifest.copy(
            projectId = binding.manifest.projectId,
            stateFile = binding.manifest.stateFile
        )
        require(parsedManifest.entryPoint == binding.manifest.entryPoint) {
            "项目入口在发布前发生变化"
        }
        require(preservesHostOwnedAppSpec(binding.manifest, parsedManifest)) {
            "宿主已有的 AppSpec 在发布前发生变化"
        }
        binding.manifest = parsedManifest
    }

    private fun ensureVisibleProjectPlan(draft: WebAppProjectSnapshot): WebAppProjectSnapshot {
        val fileTool = WebProjectFileTool(draft.contentRoot)
        val visiblePlan = File(draft.contentRoot, WebAppProjectConventions.PLAN_FILE_NAME)
        val parsedManifest = visiblePlan
            .takeIf(File::isFile)
            ?.let { planFile ->
                runCatching {
                    WebProjectPlanCodec.parse(planFile.readText()).manifest.copy(
                        projectId = draft.manifest.projectId,
                        stateFile = draft.manifest.stateFile
                    )
                }.getOrNull()
            }
            ?.takeIf { manifest ->
                manifest.entryPoint == draft.manifest.entryPoint &&
                    manifest.files.any { it.role == WebAppProjectFileRole.STYLE } &&
                    manifest.files.any { it.role == WebAppProjectFileRole.SCRIPT } &&
                    preservesHostOwnedAppSpec(draft.manifest, manifest)
            }

        val manifest = parsedManifest
            ?: draft.manifest.takeIf { it.appSpec != null }
            ?: buildMigrationManifest(draft, fileTool)
        val planJson = renderVisibleProjectPlan(manifest)
        if (!visiblePlan.isFile || visiblePlan.readText() != planJson) {
            fileTool.write(WebAppProjectConventions.PLAN_FILE_NAME, planJson)
        }
        return draft.copy(
            manifest = manifest,
            entryFile = File(draft.contentRoot, manifest.entryPoint).canonicalFile
        )
    }

    private fun buildMigrationManifest(
        draft: WebAppProjectSnapshot,
        fileTool: WebProjectFileTool
    ): WebAppProjectManifest {
        val existingFiles = fileTool.list(".", recursive = true)
            .asSequence()
            .filterNot { it.directory }
            .map { it.path }
            .filterNot { it == WebAppProjectConventions.PLAN_FILE_NAME }
            .distinct()
            .toMutableList()
        if (draft.manifest.entryPoint !in existingFiles) {
            existingFiles.add(0, draft.manifest.entryPoint)
        }
        if (existingFiles.none { it.endsWith(".css", ignoreCase = true) }) {
            existingFiles += DEFAULT_STYLE_PATH
        }
        if (existingFiles.none {
                it.endsWith(".js", ignoreCase = true) || it.endsWith(".mjs", ignoreCase = true)
            }
        ) {
            existingFiles += DEFAULT_SCRIPT_PATH
        }
        val prioritizedFiles = existingFiles
            .distinct()
            .sortedWith(
                compareBy<String> { it != draft.manifest.entryPoint }
                    .thenBy { projectRoleForPath(it).ordinal }
                    .thenBy(String::lowercase)
            )
            .take(MAX_PLANNED_FILES)
        val requiredPaths = buildList {
            add(draft.manifest.entryPoint)
            if (DEFAULT_STYLE_PATH in existingFiles) add(DEFAULT_STYLE_PATH)
            if (DEFAULT_SCRIPT_PATH in existingFiles) add(DEFAULT_SCRIPT_PATH)
            addAll(prioritizedFiles)
        }.distinct().take(MAX_PLANNED_FILES)
        return draft.manifest.copy(
            files = buildList {
                add(
                    WebAppProjectFile(
                        WebAppProjectConventions.PLAN_FILE_NAME,
                        WebAppProjectFileRole.OTHER
                    )
                )
                requiredPaths.forEach { path ->
                    add(
                        WebAppProjectFile(
                            path = path,
                            role = if (path == draft.manifest.entryPoint) {
                                WebAppProjectFileRole.ENTRY
                            } else {
                                projectRoleForPath(path)
                            }
                        )
                    )
                }
            }
        )
    }

    private fun projectRoleForPath(path: String): WebAppProjectFileRole {
        return when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html", "htm" -> WebAppProjectFileRole.OTHER
            "css" -> WebAppProjectFileRole.STYLE
            "js", "mjs" -> WebAppProjectFileRole.SCRIPT
            "json" -> WebAppProjectFileRole.DATA
            "svg", "png", "jpg", "jpeg", "gif", "webp", "avif", "ico" -> {
                WebAppProjectFileRole.ASSET
            }
            else -> WebAppProjectFileRole.OTHER
        }
    }
}
