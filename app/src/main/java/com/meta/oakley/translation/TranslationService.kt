package com.meta.oakley.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Optional translation: convert text to a target language. Swappable via [TranslationApi].
 */
interface TranslationApi {
    suspend fun translate(text: String, sourceLang: String?, targetLang: String): Result<String>
}

/**
 * No-op implementation: returns [text] unchanged. Replace with Google Translate or another provider.
 */
class NoOpTranslationApi : TranslationApi {
    override suspend fun translate(text: String, sourceLang: String?, targetLang: String): Result<String> =
        Result.success(text)
}

/**
 * Service that delegates to [TranslationApi]. Use for assistant or user text before TTS/display.
 */
class TranslationService(
    private val api: TranslationApi
) {
    suspend fun translateToTarget(text: String, targetLang: String, sourceLang: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            api.translate(text, sourceLang, targetLang)
        }
}
