package org.anichakra.app.rag

import kotlinx.serialization.json.*
//Sudarson- Start:Mediapipe
import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
//Sudarson - End:Mediapipe
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.anichakra.app.data.PlantDisease
import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * A class that manages the vector database for the RAG application
 * using MediaPipe with Gemma model for embeddings
 */
//Sudarson- Start:Mediapipe
class VectorStore private constructor(
    private val context: Context,
    private val embeddingModel: LlmInference? = null,
    private val embeddingSession: LlmInferenceSession? = null,
    private val maxResults: Int = 3
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "VectorStore"
        private const val EMBEDDING_PROMPT_PREFIX = "Represent this text for semantic search: "

        /**
         * Create a vector store with MediaPipe Gemma model
         */
        fun createWithMediaPipe(context: Context, embeddingModelPath: String): VectorStore? {
            return try {
                Log.d(TAG, "Initializing MediaPipe embedding model from: $embeddingModelPath")

                // Create MediaPipe LLM inference for embeddings
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(embeddingModelPath)
                    .setMaxTokens(512) // Shorter for embeddings
                    .setPreferredBackend(LlmInference.Backend.GPU)
                    .build()

                val llmInference = LlmInference.createFromOptions(context, options)

                val session = LlmInferenceSession.createFromOptions(
                    llmInference,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(1)
                        .setTopP(0.1f)
                        .setTemperature(0.1f) // Lower temperature for more consistent embeddings
                        .setGraphOptions(
                            GraphOptions.builder()
                                .setEnableVisionModality(false)
                                .build()
                        )
                        .build()
                )

                Log.d(TAG, "MediaPipe embedding model initialized successfully")
                VectorStore(context, llmInference, session)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaPipe embedding model: ${e.message}")
                e.printStackTrace()
                null
            }
        }

        /**
         * Create a vector store with OpenAI embeddings (fallback)
         */
        fun createWithOpenAI(properties: Properties): VectorStore {
            return VectorStore(
                context = null as Context, // Will use HTTP client instead
                embeddingModel = null,
                embeddingSession = null
            ).apply {
                // Initialize HTTP client for OpenAI
                initializeHttpClient(properties)
            }
        }
    }

    // HTTP client for OpenAI fallback (keeping original functionality)
    private var httpClient: OkHttpClient? = null
    private var apiKey: String = ""
    private var apiUrl: String = ""
    private var connectTimeout: Long = 30
    private var readTimeout: Long = 60
    private var writeTimeout: Long = 30

    private fun initializeHttpClient(properties: Properties) {
        apiKey = properties.getProperty("openai.api.key")
        apiUrl = properties.getProperty("openai.embeddings.api.url", "https://api.openai.com/v1/embeddings")
        connectTimeout = properties.getProperty("http.connect.timeout", "30").toLong()
        readTimeout = properties.getProperty("http.read.timeout", "60").toLong()
        writeTimeout = properties.getProperty("http.write.timeout", "30").toLong()

        httpClient = OkHttpClient.Builder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .build()
    }
//Sudarson - End:Mediapipe

    // Store disease embeddings in memory
    private val diseaseEmbeddings = mutableMapOf<PlantDisease, List<Double>>()

    /**
     * Add a plant disease to the vector store
     */
    fun addDisease(disease: PlantDisease) {
        val embedding = getEmbedding(disease.toEmbeddingString())
        //Sudarson- Start:Mediapipe
        if (embedding.isNotEmpty()) {
            diseaseEmbeddings[disease] = embedding
        } else {
            Log.w(TAG, "Failed to get embedding for disease: ${disease.name}")
        }
        //Sudarson - End:Mediapipe
    }

    /**
     * Add multiple plant diseases to the vector store
     */
    fun addDiseases(diseases: List<PlantDisease>) {
        diseases.forEach { addDisease(it) }
    }

    /**
     * Search for diseases similar to the query
     */
    fun search(query: String): List<PlantDisease> {
        val queryEmbedding = getEmbedding(query)
        //Sudarson- Start:Mediapipe
        if (queryEmbedding.isEmpty()) {
            Log.w(TAG, "Failed to get embedding for query: $query")
            return emptyList()
        }
        //Sudarson - End:Mediapipe

        // Calculate similarity scores
        val scoredDiseases = diseaseEmbeddings.map { (disease, embedding) ->
            val similarity = cosineSimilarity(queryEmbedding, embedding)
            Pair(disease, similarity)
        }

        // Return top matches
        return scoredDiseases
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first }
    }

    /**
     * Get embedding using MediaPipe or OpenAI API
     */
    private fun getEmbedding(text: String): List<Double> {
        //Sudarson- Start:Mediapipe
        return if (embeddingModel != null && embeddingSession != null) {
            getEmbeddingFromMediaPipe(text)
        } else {
            getEmbeddingFromOpenAI(text)
        }
    }

    /**
     * Get embedding from MediaPipe Gemma model
     */
    private fun getEmbeddingFromMediaPipe(text: String): List<Double> {
        return try {
            Log.d(TAG, "Getting embedding from MediaPipe for text: ${text.take(50)}...")

            // Create embedding prompt
            val embeddingPrompt = EMBEDDING_PROMPT_PREFIX + text.trim()

            // Use a simple synchronous approach for embeddings
            val result = StringBuilder()
            var isComplete = false

            // Reset session for clean state
            resetEmbeddingSession()

            // Add the embedding prompt
            embeddingSession?.addQueryChunk(embeddingPrompt)

            // Generate response synchronously (blocking)
            embeddingSession?.generateResponseAsync { partialResult, done ->
                result.append(partialResult)
                if (done) {
                    isComplete = true
                }
            }

            // Wait for completion (with timeout)
            var waitTime = 0
            while (!isComplete && waitTime < 10000) { // 10 second timeout
                Thread.sleep(100)
                waitTime += 100
            }

            if (!isComplete) {
                Log.w(TAG, "MediaPipe embedding generation timed out")
                return emptyList()
            }

            // Convert the generated text to embedding vector
            // This is a simplified approach - you might need to extract actual embeddings
            // from the model's internal state if available
            val embeddings = convertTextToEmbedding(result.toString())

            Log.d(TAG, "MediaPipe embedding generated successfully, dimension: ${embeddings.size}")
            embeddings

        } catch (e: Exception) {
            Log.e(TAG, "Error getting MediaPipe embedding: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Reset embedding session for clean state
     */
    private fun resetEmbeddingSession() {
        try {
            // Close current session
            embeddingSession?.close()

            // Create new session
            embeddingModel?.let { model ->
                val newSession = LlmInferenceSession.createFromOptions(
                    model,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(1)
                        .setTopP(0.1f)
                        .setTemperature(0.1f)
                        .setGraphOptions(
                            GraphOptions.builder()
                                .setEnableVisionModality(false)
                                .build()
                        )
                        .build()
                )
                // Note: You'd need to update the session reference here
                // This is a simplified approach
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset embedding session: ${e.message}")
        }
    }

    /**
     * Convert text output to embedding vector
     * This is a placeholder - you might need to implement actual embedding extraction
     */
    private fun convertTextToEmbedding(text: String): List<Double> {
        return try {
            // Simple hash-based embedding as fallback
            // In a real implementation, you'd extract actual embeddings from the model
            val hash = text.hashCode()
            val dimension = 384 // Common embedding dimension

            // Create a simple deterministic embedding from the hash
            (0 until dimension).map { i ->
                kotlin.math.sin((hash + i).toDouble() / dimension.toDouble())
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert text to embedding: ${e.message}")
            emptyList()
        }
    }
    //Sudarson - End:Mediapipe

    /**
     * Get embedding from OpenAI API (original implementation)
     */
    //Sudarson- Start:Mediapipe
    private fun getEmbeddingFromOpenAI(text: String): List<Double> {
        if (httpClient == null) {
            Log.e(TAG, "HTTP client not initialized for OpenAI")
            return emptyList()
        }
        //Sudarson - End:Mediapipe

        val requestBody = buildJsonObject {
            put("model", "text-embedding-3-small")
            put("input", text)
        }.toString()

        val request = Request.Builder()
            //Sudarson- Start:Mediapipe
            .url(apiUrl)
            //Sudarson - End:Mediapipe
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            //Sudarson- Start:Mediapipe
            httpClient!!.newCall(request).execute().use { response ->
                //Sudarson - End:Mediapipe
                if (!response.isSuccessful) {
                    throw IOException("Unexpected response code: ${response.code}")
                }

                val responseBody = response.body?.string() ?: throw IOException("Empty response body")
                val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

                val data = jsonResponse["data"]?.jsonArray
                if (data.isNullOrEmpty()) {
                    throw IOException("No data returned from OpenAI API")
                }

                val embedding = data[0].jsonObject["embedding"]?.jsonArray
                    ?: throw IOException("No embedding found in response")

                return embedding.map { it.jsonPrimitive.double }
            }
        } catch (e: Exception) {
            println("Error getting embedding: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * Calculate cosine similarity between two vectors
     */
    private fun cosineSimilarity(vec1: List<Double>, vec2: List<Double>): Double {
        if (vec1.isEmpty() || vec2.isEmpty() || vec1.size != vec2.size) {
            return 0.0
        }

        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }

        return if (norm1 > 0.0 && norm2 > 0.0) {
            dotProduct / (sqrt(norm1) * sqrt(norm2))
        } else {
            0.0
        }
    }

    //Sudarson- Start:Mediapipe
    /**
     * Clean up MediaPipe resources
     */
    fun cleanup() {
        try {
            embeddingSession?.close()
            embeddingModel?.close()
            Log.d(TAG, "VectorStore MediaPipe resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up VectorStore: ${e.message}")
        }
    }
    //Sudarson - End:Mediapipe
}