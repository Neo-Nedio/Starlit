package com.hfad.starlit.webapp.runtime

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

internal object WebProjectFileScanner {
    internal fun scanProject(root: File, limits: WebProjectContentServer.Limits): List<WebProjectContentServer.ProjectFile> {
        val files = mutableListOf<WebProjectContentServer.ProjectFile>()
        val caseInsensitivePaths = mutableSetOf<String>()
        var totalBytes = 0L

        fun visit(directory: File, relativeDirectory: String, depth: Int) {
            require(depth <= limits.maxDepth) { "Project directory depth exceeds ${limits.maxDepth}" }
            val children = directory.listFiles()
                ?: throw IllegalArgumentException("Unable to read project directory: $relativeDirectory")
            for (child in children.sortedBy { it.name }) {
                require(!Files.isSymbolicLink(child.toPath())) {
                    "Symbolic links are not allowed in web projects"
                }
                val relativePath = listOf(relativeDirectory, child.name)
                    .filter(String::isNotEmpty)
                    .joinToString("/")
                require(relativePath.length <= limits.maxRelativePathChars) {
                    "Project path exceeds ${limits.maxRelativePathChars} characters"
                }
                require(child.name != "." && child.name != ".." && '\u0000' !in child.name) {
                    "Project contains an unsafe path"
                }
                if (child.isDirectory) {
                    visit(child, relativePath, depth + 1)
                } else {
                    require(child.isFile) { "Project contains a non-regular file: $relativePath" }
                    require(child.length() <= limits.maxFileBytes) {
                        "Project file exceeds ${limits.maxFileBytes} bytes: $relativePath"
                    }
                    require(files.size < limits.maxFiles) {
                        "Project contains more than ${limits.maxFiles} files"
                    }
                    totalBytes += child.length()
                    require(totalBytes <= limits.maxProjectBytes) {
                        "Project exceeds ${limits.maxProjectBytes} bytes"
                    }
                    require(caseInsensitivePaths.add(relativePath.lowercase(Locale.US))) {
                        "Project contains case-conflicting paths: $relativePath"
                    }
                    files += WebProjectContentServer.ProjectFile(child.canonicalFile, relativePath, child.length())
                }
            }
        }

        visit(root, "", 0)
        require(files.isNotEmpty()) { "Project does not contain any files" }
        return files
    }

    internal fun computeRevision(files: List<WebProjectContentServer.ProjectFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        for (projectFile in files.sortedBy(WebProjectContentServer.ProjectFile::relativePath)) {
            digest.update(projectFile.relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            FileInputStream(projectFile.file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.update(0)
        }
        return digest.digest().toHex().take(24)
    }

}
