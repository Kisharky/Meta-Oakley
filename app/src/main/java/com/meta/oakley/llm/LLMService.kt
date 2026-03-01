package com.meta.oakley.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LLM service that delegates to a swappable [LLMApi]. Default implementation uses Groq (OpenAI-compatible).
 */
class LLMService(
    private val api: LLMApi
) {
    /**
     * Sends [messages] to the LLM and returns the assistant reply, or a failure.
     */
    suspend fun respond(messages: List<ChatMessage>): Result<String> = withContext(Dispatchers.IO) {
        api.chat(messages)
    }
}

/**
 * Groq-backed implementation of [LLMApi].
 */
class GroqLLMApi(
    private val retrofitApi: GroqRetrofitApi,
    private val model: String = "llama-3.1-70b-versatile"
) : LLMApi {
    override suspend fun chat(messages: List<ChatMessage>): Result<String> = runCatching {
        val body = GroqRequest(
            model = model,
            messages = messages.map { GroqMessage(it.role, it.content) }
        )
        val response = retrofitApi.chatCompletions(body)
        val content = response.choices?.firstOrNull()?.message?.content
            ?: return Result.failure(IllegalStateException("Empty response from Groq"))
        content
    }
}
