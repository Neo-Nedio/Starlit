package com.hfad.starlit.webapp.project

import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

class WebAppProjectValidator(
    private val policy: WebAppProjectValidationPolicy = WebAppProjectValidationPolicy()
) {
    fun validate(snapshot: WebAppProjectSnapshot): WebProjectValidationReport =
        validate(snapshot.manifest, snapshot.contentRoot)

    fun validate(
        manifest: WebAppProjectManifest,
        contentRoot: File
    ): WebProjectValidationReport {
        val diagnostics = validateManifest(manifest).toMutableList()
        if (!contentRoot.isDirectory) {
            diagnostics += error(
                code = "CONTENT_ROOT_MISSING",
                message = "Project content root does not exist",
                path = contentRoot.path
            )
            return WebProjectValidationReport(diagnostics)
        }

        val root = contentRoot.canonicalFile
        val files = mutableListOf<File>()
        var totalBytes = 0L
        Files.walk(root.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == root.toPath()) continue
                val relativePath = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                if (Files.isSymbolicLink(path)) {
                    diagnostics += error(
                        code = "SYMLINK_NOT_ALLOWED",
                        message = "Symbolic links are not allowed in web projects",
                        path = relativePath
                    )
                    continue
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                val file = path.toFile()
                files += file
                val size = file.length()
                totalBytes += size
                if (size > policy.maxFileBytes) {
                    diagnostics += error(
                        code = "FILE_TOO_LARGE",
                        message = "File exceeds ${policy.maxFileBytes} bytes",
                        path = relativePath
                    )
                }
                val extension = file.extension.lowercase(Locale.US)
                if (extension !in policy.allowedExtensions) {
                    diagnostics += error(
                        code = "FILE_TYPE_NOT_ALLOWED",
                        message = "File extension .$extension is not allowed",
                        path = relativePath
                    )
                }
            }
        }

        if (files.size > policy.maxFiles) {
            diagnostics += error(
                code = "FILE_COUNT_LIMIT",
                message = "Project has ${files.size} files; limit is ${policy.maxFiles}"
            )
        }
        if (totalBytes > policy.maxTotalBytes) {
            diagnostics += error(
                code = "PROJECT_SIZE_LIMIT",
                message = "Project uses $totalBytes bytes; limit is ${policy.maxTotalBytes}"
            )
        }

        val entry = resolveManifestPath(root, manifest.entryPoint, "entryPoint", diagnostics)
        if (entry == null || !entry.isFile) {
            diagnostics += error(
                code = "ENTRY_POINT_MISSING",
                message = "HTML entry point does not exist",
                path = manifest.entryPoint
            )
        } else {
            validateEntryHtml(root, entry, diagnostics)
            validateStructuredProjectEntry(manifest, root, entry, diagnostics)
        }

        manifest.stateFile?.let { statePath ->
            val stateFile = resolveManifestPath(root, statePath, "stateFile", diagnostics)
            if (stateFile == null || !stateFile.isFile) {
                diagnostics += error(
                    code = "STATE_FILE_MISSING",
                    message = "Declared state file does not exist",
                    path = statePath
                )
            }
        }

        manifest.files.forEach { declared ->
            val declaredFile = resolveManifestPath(root, declared.path, "files.path", diagnostics)
            if (declared.required && (declaredFile == null || !declaredFile.isFile)) {
                diagnostics += error(
                    code = "DECLARED_FILE_MISSING",
                    message = "Required manifest file does not exist",
                    path = declared.path
                )
            }
            val expectedHash = declared.sha256
            if (declaredFile?.isFile == true && expectedHash != null &&
                WebProjectPaths.sha256(declaredFile.readBytes()) != expectedHash.lowercase(Locale.US)
            ) {
                diagnostics += error(
                    code = "DECLARED_FILE_HASH_MISMATCH",
                    message = "File SHA-256 does not match the manifest",
                    path = declared.path
                )
            }
        }

        files.filter { it.extension.equals("json", ignoreCase = true) }.forEach { jsonFile ->
            val relativePath = WebProjectPaths.relativePath(root, jsonFile)
            runCatching { JsonParser.parseString(jsonFile.readText()) }
                .onFailure { parseError ->
                    diagnostics += error(
                        code = "JSON_INVALID",
                        message = parseError.message ?: "JSON is invalid",
                        path = relativePath
                    )
                }
        }

        files.filter { it.extension.equals("css", ignoreCase = true) }.forEach { cssFile ->
            validateCssReferences(root, cssFile, diagnostics)
        }
        val documentEntry = entry?.takeIf(File::isFile)
        files.filter {
            it.extension.equals("js", ignoreCase = true) ||
                it.extension.equals("mjs", ignoreCase = true)
        }.forEach { scriptFile ->
            validateJavaScriptReferences(
                contentRoot = root,
                scriptFile = scriptFile,
                documentEntry = documentEntry,
                diagnostics = diagnostics
            )
        }

        return WebProjectValidationReport(
            diagnostics = diagnostics.distinct(),
            fileCount = files.size,
            totalBytes = totalBytes
        )
    }

    private fun validateEntryHtml(
        contentRoot: File,
        entry: File,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val relativePath = WebProjectPaths.relativePath(contentRoot, entry)
        val html = runCatching { entry.readText() }.getOrElse { readError ->
            diagnostics += error(
                code = "ENTRY_POINT_UNREADABLE",
                message = readError.message ?: "Unable to read HTML entry point",
                path = relativePath
            )
            return
        }
        if (!HTML_ROOT_REGEX.containsMatchIn(html) || !HTML_END_REGEX.containsMatchIn(html)) {
            diagnostics += error(
                code = "ENTRY_HTML_INCOMPLETE",
                message = "Entry point must contain a complete HTML document",
                path = relativePath
            )
        }
        HTML_RESOURCE_REGEX.findAll(html).forEach { match ->
            validateReference(
                contentRoot = contentRoot,
                owner = entry,
                rawReference = match.groupValues[4],
                diagnostics = diagnostics
            )
        }
    }

    private fun validateStructuredProjectEntry(
        manifest: WebAppProjectManifest,
        contentRoot: File,
        entry: File,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val declaredStyles = manifest.files.filter { it.role == WebAppProjectFileRole.STYLE }
        val declaredScripts = manifest.files.filter { it.role == WebAppProjectFileRole.SCRIPT }
        if (declaredStyles.isEmpty() && declaredScripts.isEmpty()) return

        if (declaredStyles.isEmpty()) {
            diagnostics += error(
                code = "PROJECT_STYLESHEET_DECLARATION_MISSING",
                message = "Structured web projects must declare at least one stylesheet"
            )
        }
        if (declaredScripts.isEmpty()) {
            diagnostics += error(
                code = "PROJECT_SCRIPT_DECLARATION_MISSING",
                message = "Structured web projects must declare at least one JavaScript file"
            )
        }

        declaredStyles.filterNot { it.path.endsWith(".css", ignoreCase = true) }
            .forEach { declared ->
                diagnostics += error(
                    code = "STYLESHEET_FILE_TYPE_INVALID",
                    message = "STYLE files must use the .css extension",
                    path = declared.path
                )
            }
        declaredScripts.filterNot {
            it.path.endsWith(".js", ignoreCase = true) ||
                it.path.endsWith(".mjs", ignoreCase = true)
        }.forEach { declared ->
            diagnostics += error(
                code = "SCRIPT_FILE_TYPE_INVALID",
                message = "SCRIPT files must use the .js or .mjs extension",
                path = declared.path
            )
        }

        val html = runCatching { entry.readText() }.getOrNull() ?: return
        val declaredStylePaths = declaredStyles.mapNotNullTo(mutableSetOf()) { declared ->
            runCatching { WebProjectPaths.resolve(contentRoot, declared.path).canonicalPath }.getOrNull()
        }
        val declaredScriptPaths = declaredScripts.mapNotNullTo(mutableSetOf()) { declared ->
            runCatching { WebProjectPaths.resolve(contentRoot, declared.path).canonicalPath }.getOrNull()
        }
        val referencedStyles = HTML_LINK_TAG_REGEX.findAll(html)
            .mapNotNull { match ->
                val tag = match.value
                val rel = htmlAttribute(tag, "rel")
                    ?.split(Regex("\\s+"))
                    ?.any { it.equals("stylesheet", ignoreCase = true) }
                    ?: false
                if (!rel) return@mapNotNull null
                htmlAttribute(tag, "href")
                    ?.let { resolveLocalReference(contentRoot, entry, it) }
            }
            .filter { it.canonicalPath in declaredStylePaths }
            .distinctBy(File::getCanonicalPath)
            .toList()
        val referencedScripts = HTML_SCRIPT_TAG_REGEX.findAll(html)
            .mapNotNull { match ->
                htmlAttribute(match.value, "src")
                    ?.let { resolveLocalReference(contentRoot, entry, it) }
            }
            .filter { it.canonicalPath in declaredScriptPaths }
            .distinctBy(File::getCanonicalPath)
            .toList()

        if (declaredStyles.isNotEmpty() && referencedStyles.isEmpty()) {
            diagnostics += error(
                code = "ENTRY_STYLESHEET_REFERENCE_MISSING",
                message = "Entry HTML must load a declared local stylesheet with <link rel=\"stylesheet\">",
                path = manifest.entryPoint
            )
        } else if (referencedStyles.isNotEmpty() && referencedStyles.none(::hasNonBlankText)) {
            diagnostics += error(
                code = "REFERENCED_STYLESHEET_EMPTY",
                message = "Entry HTML must load at least one non-empty declared stylesheet",
                path = manifest.entryPoint
            )
        }

        if (declaredScripts.isNotEmpty() && referencedScripts.isEmpty()) {
            diagnostics += error(
                code = "ENTRY_SCRIPT_REFERENCE_MISSING",
                message = "Entry HTML must load a declared local JavaScript file with <script src=\"...\">",
                path = manifest.entryPoint
            )
        } else if (referencedScripts.isNotEmpty() && referencedScripts.none(::hasNonBlankText)) {
            diagnostics += error(
                code = "REFERENCED_SCRIPT_EMPTY",
                message = "Entry HTML must load at least one non-empty declared JavaScript file",
                path = manifest.entryPoint
            )
        }
    }

    private fun htmlAttribute(tag: String, name: String): String? {
        return HTML_ATTRIBUTE_REGEX.findAll(tag)
            .firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
            ?.groupValues
            ?.drop(2)
            ?.firstOrNull(String::isNotEmpty)
    }

    private fun resolveLocalReference(contentRoot: File, owner: File, rawReference: String): File? {
        val reference = rawReference.trim()
        if (reference.isEmpty() || IGNORED_REFERENCE_PREFIXES.any(reference::startsWith) ||
            reference.startsWith("//") || SCHEME_REGEX.containsMatchIn(reference)
        ) {
            return null
        }
        val cleanReference = decodeReferencePath(reference)?.takeIf(String::isNotBlank) ?: return null
        val candidate = if (cleanReference.startsWith('/')) {
            File(contentRoot, cleanReference.removePrefix("/"))
        } else {
            File(owner.parentFile, cleanReference)
        }
        val rootPath = contentRoot.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        return candidate.takeIf {
            candidatePath.startsWith(rootPath) &&
                Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS)
        }?.canonicalFile
    }

    private fun hasNonBlankText(file: File): Boolean {
        return runCatching { file.readText().isNotBlank() }.getOrDefault(false)
    }

    private fun validateCssReferences(
        contentRoot: File,
        cssFile: File,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val css = runCatching { cssFile.readText() }.getOrNull() ?: return
        CSS_URL_REGEX.findAll(css).forEach { match ->
            validateReference(contentRoot, cssFile, match.groupValues[2], diagnostics)
        }
        CSS_IMPORT_REGEX.findAll(css).forEach { match ->
            validateReference(contentRoot, cssFile, match.groupValues[1], diagnostics)
        }
    }

    private fun validateJavaScriptReferences(
        contentRoot: File,
        scriptFile: File,
        documentEntry: File?,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val script = runCatching { scriptFile.readText() }.getOrNull() ?: return
        JS_MODULE_REGEX.findAll(script).forEach { match ->
            val reference = match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty()
            if (reference.isBlank()) return@forEach
            if (!reference.startsWith(".") && !reference.startsWith("/") &&
                !SCHEME_REGEX.containsMatchIn(reference)
            ) {
                diagnostics += error(
                    code = "BARE_MODULE_UNSUPPORTED",
                    message = "Bare module imports require a bundler",
                    path = "${WebProjectPaths.relativePath(contentRoot, scriptFile)} -> $reference"
                )
            } else {
                validateReference(contentRoot, scriptFile, reference, diagnostics)
            }
        }
        JS_RUNTIME_RESOURCE_REGEX.findAll(script).forEach { match ->
            validateReference(
                contentRoot,
                documentEntry ?: scriptFile,
                match.groupValues[3],
                diagnostics
            )
        }
    }

    private fun validateReference(
        contentRoot: File,
        owner: File,
        rawReference: String,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ) {
        val reference = rawReference.trim()
        if (reference.isEmpty() || IGNORED_REFERENCE_PREFIXES.any(reference::startsWith)) return
        if (reference.startsWith("//") || SCHEME_REGEX.containsMatchIn(reference)) {
            if (policy.forbidRemoteResources) {
                diagnostics += error(
                    code = "REMOTE_RESOURCE_FORBIDDEN",
                    message = "Remote project resources are not allowed",
                    path = "${WebProjectPaths.relativePath(contentRoot, owner)} -> $reference"
                )
            }
            return
        }
        val cleanReference = decodeReferencePath(reference) ?: run {
            diagnostics += error(
                code = "RESOURCE_PATH_INVALID",
                message = "Resource path is invalid",
                path = "${WebProjectPaths.relativePath(contentRoot, owner)} -> $reference"
            )
            return
        }
        if (cleanReference.isBlank()) return
        val candidate = if (cleanReference.startsWith('/')) {
            File(contentRoot, cleanReference.removePrefix("/"))
        } else {
            File(owner.parentFile, cleanReference)
        }
        val rootPath = contentRoot.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        if (!candidatePath.startsWith(rootPath)) {
            diagnostics += error(
                code = "RESOURCE_OUTSIDE_PROJECT",
                message = "Resource path escapes the project root",
                path = "${WebProjectPaths.relativePath(contentRoot, owner)} -> $reference"
            )
            return
        }
        if (!Files.isRegularFile(candidatePath, LinkOption.NOFOLLOW_LINKS)) {
            diagnostics += error(
                code = "LOCAL_RESOURCE_MISSING",
                message = "Referenced local resource does not exist",
                path = "${WebProjectPaths.relativePath(contentRoot, owner)} -> $reference"
            )
        }
    }

    private fun resolveManifestPath(
        root: File,
        path: String,
        field: String,
        diagnostics: MutableList<WebProjectValidationDiagnostic>
    ): File? {
        return runCatching { WebProjectPaths.resolve(root, path) }
            .getOrElse { pathError ->
                diagnostics += error(
                    code = "MANIFEST_PATH_INVALID",
                    message = "$field: ${pathError.message}",
                    path = path
                )
                null
            }
    }

    private fun decodeReferencePath(reference: String): String? {
        return runCatching {
            val withoutFragment = reference.substringBefore('#').substringBefore('?')
            URI(withoutFragment).path ?: withoutFragment
        }.getOrNull()
    }

    companion object {
        private val PROJECT_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val HTML_ROOT_REGEX = Regex("<html(?:\\s|>)", RegexOption.IGNORE_CASE)
        private val HTML_END_REGEX = Regex("</html\\s*>", RegexOption.IGNORE_CASE)
        private val HTML_RESOURCE_REGEX = Regex(
            """<(script|link|img|source|audio|video|iframe)\b[^>]*?\b(src|href|poster)\s*=\s*(["'])([^"']+)\3""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val HTML_LINK_TAG_REGEX = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val HTML_SCRIPT_TAG_REGEX = Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val HTML_ATTRIBUTE_REGEX = Regex(
            """\b([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))"""
        )
        private val CSS_URL_REGEX = Regex(
            """url\(\s*(["']?)([^"')]+)\1\s*\)""",
            RegexOption.IGNORE_CASE
        )
        private val CSS_IMPORT_REGEX = Regex(
            """@import\s+(?:url\(\s*)?["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        private val JS_MODULE_REGEX = Regex(
            """(?:\b(?:import|export)\s+(?:[^;]*?\s+from\s+)?["']([^"']+)["']|\bimport\s*\(\s*["']([^"']+)["']\s*\))"""
        )
        private val JS_RUNTIME_RESOURCE_REGEX = Regex(
            """\b(fetch|Worker)\s*\(\s*(["'])([^"']+)\2"""
        )
        private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        private val IGNORED_REFERENCE_PREFIXES = listOf(
            "#", "data:", "blob:", "javascript:", "mailto:", "tel:", "about:", "${'$'}{", "{{"
        )

        fun validateManifest(manifest: WebAppProjectManifest): List<WebProjectValidationDiagnostic> {
            val diagnostics = mutableListOf<WebProjectValidationDiagnostic>()
            if (manifest.schemaVersion != WebAppProjectManifest.CURRENT_SCHEMA_VERSION) {
                diagnostics += error(
                    code = "MANIFEST_SCHEMA_UNSUPPORTED",
                    message = "Unsupported manifest schema version ${manifest.schemaVersion}"
                )
            }
            if (!PROJECT_ID_REGEX.matches(manifest.projectId)) {
                diagnostics += error(
                    code = "PROJECT_ID_INVALID",
                    message = "projectId must contain 1-128 ASCII letters, digits, dots, underscores, or hyphens"
                )
            }
            if (manifest.displayName.isBlank() || manifest.displayName.length > 120) {
                diagnostics += error(
                    code = "DISPLAY_NAME_INVALID",
                    message = "displayName must contain 1-120 characters"
                )
            }
            validateManifestRelativePath(manifest.entryPoint, "entryPoint", diagnostics)
            if (!manifest.entryPoint.substringAfterLast('.', "").let {
                    it.equals("html", true) || it.equals("htm", true)
                }
            ) {
                diagnostics += error(
                    code = "ENTRY_POINT_TYPE_INVALID",
                    message = "entryPoint must be an HTML file",
                    path = manifest.entryPoint
                )
            }
            manifest.stateFile?.let { path ->
                validateManifestRelativePath(path, "stateFile", diagnostics)
                if (!path.substringAfterLast('.', "").equals("json", true)) {
                    diagnostics += error(
                        code = "STATE_FILE_TYPE_INVALID",
                        message = "stateFile must be a JSON file",
                        path = path
                    )
                }
            }
            val duplicatePaths = manifest.files
                .groupBy { it.path }
                .filterValues { it.size > 1 }
                .keys
            duplicatePaths.forEach { path ->
                diagnostics += error(
                    code = "MANIFEST_FILE_DUPLICATE",
                    message = "Manifest file path is duplicated",
                    path = path
                )
            }
            manifest.files.forEach { file ->
                validateManifestRelativePath(file.path, "files.path", diagnostics)
                file.sha256?.takeUnless(SHA256_REGEX::matches)?.let {
                    diagnostics += error(
                        code = "MANIFEST_FILE_HASH_INVALID",
                        message = "Manifest file SHA-256 must contain 64 hexadecimal characters",
                        path = file.path
                    )
                }
            }
            val entryDeclarations = manifest.files.filter { it.role == WebAppProjectFileRole.ENTRY }
            if (entryDeclarations.size > 1 ||
                entryDeclarations.singleOrNull()?.path?.let { it != manifest.entryPoint } == true
            ) {
                diagnostics += error(
                    code = "MANIFEST_ENTRY_CONFLICT",
                    message = "ENTRY file role must match entryPoint exactly"
                )
            }
            diagnostics += WebAppSpecValidator.validate(manifest)
            return diagnostics
        }

        private fun validateManifestRelativePath(
            path: String,
            field: String,
            diagnostics: MutableList<WebProjectValidationDiagnostic>
        ) {
            runCatching { WebProjectPaths.normalizeRelativePath(path) }
                .onFailure { error ->
                    diagnostics += error(
                        code = "MANIFEST_PATH_INVALID",
                        message = "$field: ${error.message}",
                        path = path
                    )
                }
        }

        private fun error(
            code: String,
            message: String,
            path: String? = null
        ) = WebProjectValidationDiagnostic(
            severity = WebProjectDiagnosticSeverity.ERROR,
            code = code,
            message = message,
            path = path
        )
    }
}

