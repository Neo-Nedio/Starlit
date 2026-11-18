package com.hfad.starlit.webapp.project

import java.io.File

object WebAppProjectDataFiles {

    fun dataFileForHtml(htmlFile: File): File {
        val name = htmlFile.name
        val stem = if (htmlFile.extension.isNotBlank()) {
            name.substringBeforeLast('.')
        } else {
            name
        }
        return File(htmlFile.parentFile ?: File("."), "$stem.json")
    }

    fun ensureWebAppDataFile(htmlFile: File): File {
        val dataFile = dataFileForHtml(htmlFile)
        if (!dataFile.exists()) {
            dataFile.parentFile?.mkdirs()
            dataFile.writeText("{}")
        }
        return dataFile
    }
}
