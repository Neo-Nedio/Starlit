package com.hfad.starlit.webapp.generation

internal data class UnifiedDiffPatch(
    val oldPath: String,
    val newPath: String,
    val hunks: List<UnifiedDiffHunk>,
    val hasOldNoNewlineMarker: Boolean,
    val hasNewNoNewlineMarker: Boolean
)

internal data class UnifiedDiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<UnifiedDiffLine>
)

internal data class UnifiedDiffLine(
    val kind: DiffLineKind,
    val content: String,
    val noNewline: Boolean = false
)

internal enum class DiffLineKind {
    CONTEXT,
    REMOVE,
    ADD
}

internal class UnifiedDiffParser(
    private val maxDiffBytes: Int,
    private val maxHunks: Int
) {

    fun parse(rawDiff: String, expectedPath: String): UnifiedDiffPatch {
        val byteSize = rawDiff.toByteArray(Charsets.UTF_8).size
        if (byteSize > maxDiffBytes) {
            throw LocalDiffFileTool.DiffException("Diff exceeds the $maxDiffBytes byte limit")
        }
        if (rawDiff.indexOf('\u0000') >= 0) {
            throw LocalDiffFileTool.DiffException("Diff contains a NUL byte")
        }

        val content = unwrapFence(LocalDiffFileTool.normalizeLineEndings(rawDiff))
        val lines = LocalDiffFileTool.splitLines(content)
        if (lines.isEmpty()) throw LocalDiffFileTool.DiffException("Diff is empty")

        var index = 0
        var gitHeaderCount = 0
        while (index < lines.size && !lines[index].startsWith("--- ")) {
            val line = lines[index]
            when {
                line.startsWith("diff --git ") -> {
                    gitHeaderCount++
                    if (gitHeaderCount > 1) throw LocalDiffFileTool.DiffException("Multiple file patches are not supported")
                }

                line.startsWith("index ") -> Unit
                LocalDiffFileTool.UNSUPPORTED_METADATA_PREFIXES.any(line::startsWith) -> {
                    throw LocalDiffFileTool.DiffException("File creation, deletion, rename, mode, and binary patches are not supported")
                }

                else -> throw LocalDiffFileTool.DiffException("Unexpected content before the file header: $line")
            }
            index++
        }

        if (index >= lines.size) throw LocalDiffFileTool.DiffException("Missing old-file header")
        val oldPath = parseHeaderPath(lines[index], "--- ", "a/")
        index++
        if (index >= lines.size || !lines[index].startsWith("+++ ")) {
            throw LocalDiffFileTool.DiffException("Missing new-file header")
        }
        val newPath = parseHeaderPath(lines[index], "+++ ", "b/")
        index++
        if (oldPath != expectedPath || newPath != expectedPath) {
            throw LocalDiffFileTool.DiffException("Diff header path must exactly match $expectedPath")
        }

        val hunks = mutableListOf<UnifiedDiffHunk>()
        var hasOldNoNewlineMarker = false
        var hasNewNoNewlineMarker = false
        var previousOldEnd = 0
        var previousNewEnd = 0

        while (index < lines.size) {
            val headerLine = lines[index]
            if (headerLine.startsWith("--- ") || headerLine.startsWith("diff --git ")) {
                throw LocalDiffFileTool.DiffException("Multiple file patches are not supported")
            }
            val headerMatch = LocalDiffFileTool.HUNK_HEADER_REGEX.matchEntire(headerLine)
                ?: throw LocalDiffFileTool.DiffException(
                    "Expected a hunk header at diff line ${index + 1} but found: ${LocalDiffFileTool.previewDiffLine(headerLine)}"
                )
            if (hunks.size >= maxHunks) throw LocalDiffFileTool.DiffException("Diff contains more than $maxHunks hunks")

            val oldStart = parseCoordinate(headerMatch.groupValues[1], "old start")
            val declaredOldCount = parseCount(headerMatch.groupValues[2], "old count")
            val newStart = parseCoordinate(headerMatch.groupValues[3], "new start")
            val declaredNewCount = parseCount(headerMatch.groupValues[4], "new count")
            validateCoordinate(oldStart, declaredOldCount, "old")
            validateCoordinate(newStart, declaredNewCount, "new")

            index++
            var consumedOld = 0
            var producedNew = 0
            var changedLines = 0
            val hunkLines = mutableListOf<UnifiedDiffLine>()
            var canMarkPreviousLine = false

            while (index < lines.size && !LocalDiffFileTool.HUNK_HEADER_REGEX.matches(lines[index])) {
                val bodyLine = lines[index]
                if (bodyLine == LocalDiffFileTool.NO_NEWLINE_MARKER) {
                    if (!canMarkPreviousLine || hunkLines.isEmpty() || hunkLines.last().noNewline) {
                        throw LocalDiffFileTool.DiffException("No-newline marker has no preceding hunk line")
                    }
                    val markedLine = hunkLines.last().copy(noNewline = true)
                    hunkLines[hunkLines.lastIndex] = markedLine
                    when (markedLine.kind) {
                        DiffLineKind.CONTEXT -> {
                            if (hasOldNoNewlineMarker || hasNewNoNewlineMarker) {
                                throw LocalDiffFileTool.DiffException("Duplicate no-newline marker")
                            }
                            hasOldNoNewlineMarker = true
                            hasNewNoNewlineMarker = true
                        }

                        DiffLineKind.REMOVE -> {
                            if (hasOldNoNewlineMarker) throw LocalDiffFileTool.DiffException("Duplicate old-file no-newline marker")
                            hasOldNoNewlineMarker = true
                        }

                        DiffLineKind.ADD -> {
                            if (hasNewNoNewlineMarker) throw LocalDiffFileTool.DiffException("Duplicate new-file no-newline marker")
                            hasNewNoNewlineMarker = true
                        }
                    }
                    canMarkPreviousLine = false
                    index++
                    continue
                }
                if (bodyLine.isEmpty()) throw LocalDiffFileTool.DiffException("Every hunk line must have a unified-diff prefix")

                val hunkLine = when (bodyLine[0]) {
                    ' ' -> {
                        consumedOld++
                        producedNew++
                        UnifiedDiffLine(DiffLineKind.CONTEXT, bodyLine.substring(1))
                    }

                    '-' -> {
                        consumedOld++
                        changedLines++
                        UnifiedDiffLine(DiffLineKind.REMOVE, bodyLine.substring(1))
                    }

                    '+' -> {
                        producedNew++
                        changedLines++
                        UnifiedDiffLine(DiffLineKind.ADD, bodyLine.substring(1))
                    }

                    else -> throw LocalDiffFileTool.DiffException(
                        "Invalid hunk line prefix '${bodyLine[0]}' at diff line ${index + 1}: " +
                            LocalDiffFileTool.previewDiffLine(bodyLine)
                    )
                }
                hunkLines.add(hunkLine)
                canMarkPreviousLine = true
                index++
            }

        val hasFollowingHunk = index < lines.size && LocalDiffFileTool.HUNK_HEADER_REGEX.matches(lines[index])
        if (!hasFollowingHunk &&
                (consumedOld != declaredOldCount || producedNew != declaredNewCount)
            ) {
                throw LocalDiffFileTool.DiffException(
                    "Final hunk body count does not match its header: " +
                        "expected -$declaredOldCount/+$declaredNewCount but found -$consumedOld/+$producedNew"
                )
            }
        if (changedLines == 0) throw LocalDiffFileTool.DiffException("Hunk does not change any lines")
            validateCoordinate(oldStart, consumedOld, "old")
            validateCoordinate(newStart, producedNew, "new")

            val oldIndex = LocalDiffFileTool.oldCoordinateIndex(oldStart, consumedOld)
            val newIndex = LocalDiffFileTool.oldCoordinateIndex(newStart, producedNew)
            if (oldIndex < previousOldEnd || newIndex < previousNewEnd) {
                throw LocalDiffFileTool.DiffException("Hunks are out of order or overlap")
            }
            previousOldEnd = checkedEnd(oldIndex, consumedOld)
            previousNewEnd = checkedEnd(newIndex, producedNew)
            hunks.add(UnifiedDiffHunk(oldStart, consumedOld, newStart, producedNew, hunkLines))
        }

        if (hunks.isEmpty()) throw LocalDiffFileTool.DiffException("Diff contains no hunks")
        return UnifiedDiffPatch(
            oldPath = oldPath,
            newPath = newPath,
            hunks = hunks,
            hasOldNoNewlineMarker = hasOldNoNewlineMarker,
            hasNewNoNewlineMarker = hasNewNoNewlineMarker
        )
    }

    private fun parseHeaderPath(line: String, prefix: String, gitPrefix: String): String {
        if (!line.startsWith(prefix)) throw LocalDiffFileTool.DiffException("Invalid file header")
        var path = line.removePrefix(prefix).substringBefore('\t')
        if (path == LocalDiffFileTool.DEV_NULL) throw LocalDiffFileTool.DiffException("File creation and deletion patches are not supported")
        if (path.startsWith('"') || path.endsWith('"')) {
            throw LocalDiffFileTool.DiffException("Quoted diff paths are not supported")
        }
        if (path.startsWith(gitPrefix)) path = path.removePrefix(gitPrefix)
        LocalDiffFileTool.validateRelativePath(path, "Diff header path")
        return path
    }

    private fun parseCoordinate(raw: String, label: String): Int {
        val value = raw.toLongOrNull() ?: throw LocalDiffFileTool.DiffException("Invalid $label")
        if (value !in 0..Int.MAX_VALUE.toLong()) throw LocalDiffFileTool.DiffException("$label is out of range")
        return value.toInt()
    }

    private fun parseCount(raw: String, label: String): Int {
        if (raw.isEmpty()) return 1
        return parseCoordinate(raw, label)
    }

    private fun validateCoordinate(start: Int, count: Int, side: String) {
        if (count > 0 && start == 0) throw LocalDiffFileTool.DiffException("$side hunk start must be at least 1 when count is nonzero")
        checkedEnd(LocalDiffFileTool.oldCoordinateIndex(start, count), count)
    }

    private fun checkedEnd(start: Int, count: Int): Int {
        val end = start.toLong() + count.toLong()
        if (end > Int.MAX_VALUE) throw LocalDiffFileTool.DiffException("Hunk coordinates are out of range")
        return end.toInt()
    }

    private fun unwrapFence(normalizedDiff: String): String {
        val boundaryTrimmed = normalizedDiff.trim('\n')
        val lines = LocalDiffFileTool.splitLines(boundaryTrimmed)
        if (lines.isEmpty()) return ""
        if (!lines.first().startsWith("```")) {
            if (lines.any { it.trimStart().startsWith("```") }) {
                throw LocalDiffFileTool.DiffException("Malformed diff code fence")
            }
            return boundaryTrimmed
        }

        val opening = lines.first().trim()
        if (!LocalDiffFileTool.FENCE_REGEX.matches(opening)) throw LocalDiffFileTool.DiffException("Only diff code fences are supported")
        if (lines.size < 2 || lines.last().trim() != "```") {
            throw LocalDiffFileTool.DiffException("Diff code fence is not closed")
        }
        return lines.subList(1, lines.lastIndex).joinToString("\n")
    }
}

