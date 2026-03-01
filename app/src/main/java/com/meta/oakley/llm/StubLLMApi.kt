package com.meta.oakley.llm

/**
 * Stub used when GROQ_API_KEY is not set. Returns a single message so the app runs without crashing.
 */
class StubLLMApi : LLMApi {
    override suspend fun chat(messages: List<ChatMessage>): Result<String> =
        Result.success("Add GROQ_API_KEY to local.properties to enable the LLM.")
}
