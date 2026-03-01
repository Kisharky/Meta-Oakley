package com.meta.oakley.llm

import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// --- DTOs (OpenAI-compatible) ---

data class GroqRequest(
    val model: String,
    val messages: List<GroqMessage>,
    @SerializedName("max_tokens") val maxTokens: Int = 1024,
    val temperature: Float = 0.7f
)

data class GroqMessage(
    val role: String,
    val content: String
)

data class GroqResponse(
    val id: String? = null,
    val choices: List<GroqChoice>? = null,
    val usage: GroqUsage? = null
)

data class GroqChoice(
    val index: Int? = null,
    val message: GroqMessage? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class GroqUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int? = null,
    @SerializedName("completion_tokens") val completionTokens: Int? = null,
    @SerializedName("total_tokens") val totalTokens: Int? = null
)

// --- Retrofit API ---

interface GroqRetrofitApi {
    @POST("openai/v1/chat/completions")
    suspend fun chatCompletions(@Body body: GroqRequest): GroqResponse
}

object GroqApiFactory {
    private const val BASE_URL = "https://api.groq.com/"

    fun create(apiKey: String): GroqRetrofitApi {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(GroqRetrofitApi::class.java)
    }
}
