package com.hfad.starlit.webapp.project

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal object WebProjectPaths {
    private val WINDOWS_ABSOLUTE_PATH_REGEX = Regex("^[A-Za-z]:[/\\\\].*")

    fun normalizeRelativePath(path: String, allowRoot: Boolean = false): String {
        if (allowRoot && (path.isBlank() || path == ".")) return ""
        if (path.isBlank()) throw WebAppProjectException("Path must not be blank")
        if (path.indexOf('\u0000') >= 0) throw WebAppProjectException("Path contains a NUL byte")
        if (path.startsWith('/') || path.startsWith('\\') || WINDOWS_ABSOLUTE_PATH_REGEX.matches(path)) {
            throw WebAppProjectException("Path must be relative")
        }
        if (path.contains('\\')) throw WebAppProjectException("Path must use forward slashes")
        val segments = path.split('/')
        if (segments.any { it.isBlank() }) throw WebAppProjectException("Path contains an empty segment")
        if (segments.any { it == "." || it == ".." }) {
            throw WebAppProjectException("Path contains a traversal segment")
        }
        if (segments.any { it == WebAppProjectWorkspace.METADATA_DIRECTORY_NAME }) {
            throw WebAppProjectException("Path targets reserved project metadata")
        }
        return segments.joinToString("/")
    }

    fun resolve(root: File, relativePath: String): File {
        val normalized = normalizeRelativePath(relativePath)
        val canonicalRoot = root.canonicalFile
        val unresolved = File(canonicalRoot, normalized)
        ensureNoSymlinkSegments(canonicalRoot, unresolved)
        val canonicalTarget = unresolved.canonicalFile
        if (!canonicalTarget.toPath().startsWith(canonicalRoot.toPath())) {
            throw WebAppProjectException("Path escapes the project root")
        }
        return canonicalTarget
    }

    fun relativePath(root: File, file: File): String =
        root.canonicalFile.toPath().relativize(file.canonicalFile.toPath())
            .joinToString("/") { it.toString() }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    fun writeTextAtomically(target: File, content: String) {
        target.parentFile?.mkdirsOrThrow()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                stream.write(bytes)
                stream.fd.sync()
            }
            moveAtomically(temporary, target, replaceExisting = true)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun copyDirectory(source: File, target: File) {
        if (!source.isDirectory) throw WebAppProjectException("Copy source is not a directory: ${source.path}")
        if (target.exists()) throw WebAppProjectException("Copy target already exists: ${target.path}")
        target.mkdirsOrThrow()
        Files.walk(source.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val sourcePath = iterator.next()
                if (sourcePath == source.toPath()) continue
                val relative = source.toPath().relativize(sourcePath)
                val targetPath = target.toPath().resolve(relative)
                if (Files.isSymbolicLink(sourcePath)) {
                    throw WebAppProjectException("Symbolic links cannot be copied into a project version")
                }
                if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(targetPath)
                } else if (Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    fun moveAtomically(source: File, target: File, replaceExisting: Boolean = false) {
        target.parentFile?.mkdirsOrThrow()
        val options = mutableListOf<StandardCopyOption>()
        if (replaceExisting) options += StandardCopyOption.REPLACE_EXISTING
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                *(options + StandardCopyOption.ATOMIC_MOVE).toTypedArray()
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), *options.toTypedArray())
        }
    }

    private fun ensureNoSymlinkSegments(root: File, target: File) {
        val rootPath = root.toPath()
        val unresolvedPath = target.absoluteFile.toPath().normalize()
        if (!unresolvedPath.startsWith(rootPath)) {
            throw WebAppProjectException("Path escapes the project root")
        }
        var current = rootPath
        for (segment in rootPath.relativize(unresolvedPath)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw WebAppProjectException("Symbolic links are not allowed in project paths")
            }
        }
    }
}
