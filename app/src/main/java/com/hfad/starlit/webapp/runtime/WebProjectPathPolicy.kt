package com.hfad.starlit.webapp.runtime

import java.net.URLDecoder
import java.util.Locale

internal object WebProjectPathPolicy {
    internal const val MOUNT_SEGMENT = "__starlit_project__"
    private val BLOCKED_PATH_SEGMENTS = setOf(".starlit", ".git", MOUNT_SEGMENT)
    private const val HEX = "0123456789ABCDEF"

    internal fun decodePathSegments(rawPath: String): List<String>? {
        val rawSegments = rawPath.removePrefix("/").split('/').filter(String::isNotEmpty)
        val decoded = ArrayList<String>(rawSegments.size)
        for (rawSegment in rawSegments) {
            val segment = decodePathSegment(rawSegment) ?: return null
            if (segment.isEmpty() || segment == "." || segment == ".." ||
                '/' in segment || '\\' in segment || '\u0000' in segment
            ) {
                return null
            }
            decoded += segment
        }
        return decoded
    }

    internal fun decodeReferencePathSegments(rawPath: String): List<String>? {
        val rawSegments = rawPath.removePrefix("/").split('/').filter(String::isNotEmpty)
        return rawSegments.map { rawSegment ->
            val segment = decodePathSegment(rawSegment) ?: return null
            if ('/' in segment || '\\' in segment || '\u0000' in segment) return null
            segment
        }
    }

    private fun decodePathSegment(rawSegment: String): String? {
        return runCatching {
            URLDecoder.decode(rawSegment.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrNull()
    }

    internal fun normalizeSegments(segments: List<String>): List<String>? {
        val normalized = mutableListOf<String>()
        for (segment in segments) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (normalized.isEmpty()) return null else normalized.removeAt(normalized.lastIndex)
                else -> {
                    if ('/' in segment || '\\' in segment || '\u0000' in segment || isBlockedSegment(segment)) {
                        return null
                    }
                    normalized += segment
                }
            }
        }
        return normalized
    }

    internal fun encodeRelativePath(relativePath: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return relativePath.split('/').joinToString("/") { segment ->
            buildString {
                for (byte in segment.toByteArray(Charsets.UTF_8)) {
                    val value = byte.toInt() and 0xff
                    val character = value.toChar()
                    if (character in unreserved) {
                        append(character)
                    } else {
                        append('%')
                        append(HEX[value ushr 4])
                        append(HEX[value and 0x0f])
                    }
                }
            }
        }
    }

    internal fun isBlockedSegment(segment: String): Boolean {
        return segment.lowercase(Locale.US) in BLOCKED_PATH_SEGMENTS
    }

}
