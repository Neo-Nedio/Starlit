package com.hfad.starlit.tool

import com.hfad.starlit.tool.generated.GeneratedStarlitToolsDoc
import com.hfad.starlit.tool.generated.GeneratedToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedToolMetadataTest {

    @Test
    fun kspGeneratesDocumentAndRuntimeRegistry() {
        assertEquals(
            listOf("alarm", "calendar", "camera", "clipboard", "flashlight", "storage", "toast", "vibration"),
            GeneratedStarlitToolsDoc.documentedToolNames
        )
        assertEquals(
            listOf("alarm", "calendar", "camera", "clipboard", "flashlight", "toast", "vibration"),
            GeneratedToolRegistry.toolNames
        )
        assertTrue(GeneratedStarlitToolsDoc.markdown.contains("window.StarlitApp.alarm.alarmSet"))
        assertTrue(GeneratedStarlitToolsDoc.markdown.contains("window.StarlitApp.storage.storageWrite"))
        assertEquals(GeneratedStarlitToolsDoc.documentedToolNames, GeneratedStarlitToolsDoc.tools.map { it.name })
        assertTrue(GeneratedStarlitToolsDoc.tools.first { it.name == "camera" }.retrievalText.contains("拍照"))
        assertTrue(
            GeneratedStarlitToolsDoc.tools
                .first { it.name == "calendar" }
                .markdown
                .contains("window.StarlitApp.calendar.calendarAdd")
        )
        assertFalse(GeneratedToolRegistry.toolNames.contains("storage"))
    }
}
