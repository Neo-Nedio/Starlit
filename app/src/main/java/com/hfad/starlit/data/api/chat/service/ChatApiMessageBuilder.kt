package com.hfad.starlit.data.api.chat.service

import com.hfad.starlit.chat.context.ChatContextFormatter
import com.hfad.starlit.data.api.chat.model.ApiMessage
import com.hfad.starlit.data.api.chat.model.ContentPart
import com.hfad.starlit.data.api.chat.model.ImageUrl
import com.hfad.starlit.data.database.chat.ChatMessageEntity

/** Converts stored chat history into the model-facing message shape. */
object ChatApiMessageBuilder {

    fun build(
        historyMessages: List<ChatMessageEntity>,
        systemPrompt: String,
        currentImageBase64List: List<String> = emptyList()
    ): List<ApiMessage> = buildList {
        add(ApiMessage(role = "system", content = systemPrompt))

        historyMessages.forEachIndexed { index, entity ->
            val isLastUserMessage = index == historyMessages.lastIndex && entity.role == "user"
            val contextContent = ChatContextFormatter.contentForContext(entity)
            if (isLastUserMessage && currentImageBase64List.isNotEmpty()) {
                val contentParts = buildList {
                    if (contextContent.isNotEmpty()) add(ContentPart(type = "text", text = contextContent))
                    currentImageBase64List.forEach { base64 ->
                        add(ContentPart(type = "image_url", imageUrl = ImageUrl(url = base64)))
                    }
                }
                add(ApiMessage(role = entity.role, content = contentParts))
            } else {
                add(ApiMessage(role = entity.role, content = contextContent))
            }
        }
    }
}
