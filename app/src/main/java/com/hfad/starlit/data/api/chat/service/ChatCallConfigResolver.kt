package com.hfad.starlit.data.api.chat.service

import android.content.Context
import com.hfad.starlit.data.api.chat.model.ChatCallConfig
import com.hfad.starlit.data.preferences.provider.ActiveModelStore
import com.hfad.starlit.data.repository.provider.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves the current user-selected model and its provider configuration. */
object ChatCallConfigResolver {

    suspend fun resolve(
        context: Context,
        providerRepository: ProviderRepository
    ): ChatCallConfig? {
        val providerId = ActiveModelStore.getActiveProviderId(context) ?: return null
        val modelName = ActiveModelStore.getActiveModelName(context)?.takeIf { it.isNotBlank() } ?: return null
        val provider = withContext(Dispatchers.IO) {
            providerRepository.getProviderById(providerId)
        } ?: return null
        if (provider.baseUrl.isBlank()) return null
        return ChatCallConfig(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = modelName,
            apiFormat = provider.apiFormat
        )
    }
}
