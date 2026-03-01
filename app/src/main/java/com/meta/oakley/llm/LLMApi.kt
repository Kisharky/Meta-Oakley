package com.meta.oakley.llm

/**
 * OpenAI-style chat message for any compatible provider (Groq, OpenAI, etc.).
 */
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * Abstraction for chat completion. Implement with Groq, OpenAI, or local models.
 */
interface LLMApi {
    suspend fun chat(messages: List<ChatMessage>): Result<String>
}
