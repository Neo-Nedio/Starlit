package com.hfad.starlit.webapp.runtime

import java.util.Locale

internal object WebProjectMimeTypes {
    private const val MIME_JAVASCRIPT = "application/javascript"
    private const val MIME_JSON = "application/json"
    private const val MIME_BINARY = "application/octet-stream"

    internal fun mimeTypeFor(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html", "htm" -> WebProjectContentServer.MIME_HTML
            "css" -> "text/css"
            "js", "mjs", "cjs" -> MIME_JAVASCRIPT
            "json", "map" -> MIME_JSON
            "webmanifest" -> "application/manifest+json"
            "txt", "md" -> "text/plain"
            "xml" -> "application/xml"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> MIME_BINARY
        }
    }

    internal fun isTextMimeType(mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
            mimeType == MIME_JAVASCRIPT ||
            mimeType == MIME_JSON ||
            mimeType.endsWith("+json") ||
            mimeType.endsWith("+xml") ||
            mimeType == "application/xml" ||
            mimeType == "image/svg+xml"
    }

}
