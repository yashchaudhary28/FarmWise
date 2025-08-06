package org.anichakra.app.rag

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.anichakra.app.data.PaddyDiseaseKnowledgeBase
import org.anichakra.app.data.PlantDisease
import java.io.IOException
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.Base64
import android.content.Context

/**
 * A simplified RAG application for paddy disease diagnosis
 */
class PaddyDiseaseRagApp(
    private val openAiProperties: Properties,
    private val context: Context,
    // private val openAiProperties: Properties,
    //private val openAiProperties: Properties,
    private val csvFileName: String
) {
    private val knowledgeBase: PaddyDiseaseKnowledgeBase = PaddyDiseaseKnowledgeBase(context, csvFileName)
    private val apiKey: String = openAiProperties.getProperty("openai.api.key")
    private val model: String = openAiProperties.getProperty("openai.model")
    private val apiUrl: String = openAiProperties.getProperty("openai.api.url")
    private val temperature: Double = openAiProperties.getProperty("openai.temperature", "0.7").toDouble()
    private val connectTimeout: Long = openAiProperties.getProperty("http.connect.timeout", "30").toLong()
    private val readTimeout: Long = openAiProperties.getProperty("http.read.timeout", "60").toLong()
    private val writeTimeout: Long = openAiProperties.getProperty("http.write.timeout", "30").toLong()
    private val httpClient: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }

    // Vector store for semantic search
    private val vectorStore: VectorStore

    // Store conversation history
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    // Store the last image analysis results for use in follow-up queries
    private var lastImageAnalysis: String? = null

    init {
        httpClient = OkHttpClient.Builder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)
            .build()

        // Initialize vector store
        vectorStore = VectorStore.createWithOpenAI(openAiProperties)

        // Load diseases into vector store
        val diseases = knowledgeBase.getAllDiseases()
        vectorStore.addDiseases(diseases)

        println("Loaded ${knowledgeBase.size()} plant diseases into vector store")
    }

    /**
     * Process a user query and return a response
     */
    fun processQuery(query: String): String {
        return processQueryWithImageSupport(query, null)
    }

    /**
     * Process a user query with optional image path support
     */
    fun processQueryWithImageSupport(query: String, imagePath: String?): String {
        // Add query to conversation history
        conversationHistory.add(Pair("user", query))

        // Check if this is a generic query that might benefit from image analysis
        if (imagePath == null && isGenericQuery(query)) {
            val response = """
                I'd be happy to help you with your plant/crop concern! To provide you with the most accurate diagnosis and recommendations, you have two options:
                
                1. **Ask a specific question** - Please describe the specific symptoms you're seeing, such as:
                   - What parts of the plant are affected (leaves, stems, roots)?
                   - What do the symptoms look like (spots, discoloration, wilting, etc.)?
                   - When did you first notice the problem?
                   - Any other specific details about the condition?
                
                2. **Upload an image** - Provide the full path to an image file showing the problem (e.g., /path/to/your/image.jpg). I can analyze images in formats like JPG, PNG, GIF, BMP, or WebP.
                
                If you choose to upload an image, I'll analyze it and share my observations about what I see, then ask you to confirm if my understanding is correct before proceeding with specific recommendations.
            """.trimIndent()

            conversationHistory.add(Pair("assistant", response))
            return response
        }

        // If image path is provided, analyze the image first
        if (imagePath != null) {
            val imageAnalysis = analyzeImageWithOpenAI(imagePath, query)

            // Store the image analysis for use in follow-up queries
            lastImageAnalysis = imageAnalysis

            val response = """
                Based on my analysis of the image you provided, here's what I observe:
                
                $imageAnalysis
                
                Is this understanding correct? Does this match what you're seeing with your plant? Please confirm (yes/no) so I can proceed with specific recommendations based on these observations.
            """.trimIndent()

            conversationHistory.add(Pair("assistant", response))
            return response
        }

        // Handle confirmation responses
        val lowerQuery = query.lowercase().trim()
        if (lowerQuery in listOf("yes", "y", "correct", "right", "that's right", "exactly", "true")) {
            // User confirmed the image analysis, proceed with normal RAG flow
            // Use the stored image analysis results for vector database query
            if (lastImageAnalysis != null) {
                return processNormalQuery(lastImageAnalysis!!)
            } else {
                return processNormalQuery("Based on the confirmed image analysis, please provide treatment recommendations.")
            }
        } else if (lowerQuery in listOf("no", "n", "wrong", "incorrect", "not right", "false")) {
            // User disagreed with image analysis, ask for clarification
            val response = """
                I understand my analysis might not be completely accurate. Could you please tell me more specifically what you're seeing with your plant? 
                
                For example:
                - What symptoms are you observing?
                - Which parts of the plant are affected?
                - When did you first notice the problem?
                - Any other details that might help me understand the issue better?
            """.trimIndent()

            conversationHistory.add(Pair("assistant", response))
            return response
        }

        // Normal query processing
        return processNormalQuery(query)
    }

    /**
     * Process a normal query using the existing RAG flow
     */
    private fun processNormalQuery(query: String): String {
        // Find relevant diseases
        val relevantDiseases = findRelevantDiseases(query)

        // Create context from relevant diseases
        val context = if (relevantDiseases.isNotEmpty()) {
            relevantDiseases.joinToString("\n\n") { it.toFormattedString() }
        } else {
            "No specific plant diseases found matching the query. Please ask for more details."
        }

        // Create messages for the chat model
        val messages = mutableListOf<Map<String, String>>()

        // Add system message
        messages.add(mapOf(
            "role" to "system",
            "content" to """
                You are a helpful agricultural assistant specializing in paddy (rice) plant diseases and issues.
                
                You have access to a knowledge base of plant diseases, their symptoms, and recommended treatments.
                
                Follow these guidelines:
                1. When providing treatment recommendations, be precise about dosages and application methods exactly as mentioned in the knowledge base.
                2. If multiple diseases match the symptoms, explain the differences and help the user distinguish between them.
                3. Always maintain a conversational tone and be empathetic to the farmer's concerns.
                4. If you're not confident about a diagnosis based on limited information, clearly state that and ask for more details.
                5. When appropriate, suggest preventive measures in addition to treatments.
                6. Base your recommendations on the provided knowledge base information.
                
                Your goal is to provide accurate, actionable advice that helps farmers identify and treat paddy plant diseases effectively.
            """.trimIndent()
        ))

        // Add conversation history (limited to last 5 exchanges)
        conversationHistory.takeLast(5).forEach { (role, content) ->
            messages.add(mapOf("role" to role, "content" to content))
        }

        // Add context message
        messages.add(mapOf(
            "role" to "system",
            "content" to """
                Here is information from the knowledge base that might be relevant to the user's query:
                
                $context
            """.trimIndent()
        ))

        // Call OpenAI API
        val response = callOpenAiChatApi(messages)

        // Add response to conversation history
        conversationHistory.add(Pair("assistant", response))

        return response
    }

    /**
     * Find relevant diseases for a query using vector embeddings
     */
    private fun findRelevantDiseases(query: String, maxResults: Int = 3): List<PlantDisease> {
        try {
            // Use vector store to find semantically similar diseases
            val diseases = vectorStore.search(query)

            // If no matches found using vector search, fall back to keyword matching
            if (diseases.isEmpty()) {
                println("No vector matches found, falling back to keyword matching")
                return findRelevantDiseasesWithKeywords(query, maxResults)
            }

            return diseases
        } catch (e: Exception) {
            println("Error in vector search: ${e.message}")
            e.printStackTrace()

            // Fall back to keyword matching if vector search fails
            return findRelevantDiseasesWithKeywords(query, maxResults)
        }
    }

    /**
     * Fallback method using keyword matching
     */
    private fun findRelevantDiseasesWithKeywords(query: String, maxResults: Int = 3): List<PlantDisease> {
        // Simple keyword matching as fallback
        val queryWords = query.lowercase().split(Regex("\\W+")).filter { it.length > 3 }

        // Score each disease based on keyword matches
        val scoredDiseases = knowledgeBase.getAllDiseases().map { disease ->
            val text = (disease.name + " " + disease.symptoms).lowercase()
            val score = queryWords.count { word -> text.contains(word) }
            Pair(disease, score)
        }

        // Return top matches
        return scoredDiseases
            .filter { it.second > 0 }  // Only return diseases with at least one match
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first }
    }

    /**
     * Call OpenAI Chat API
     */
    private fun callOpenAiChatApi(messages: List<Map<String, String>>): String {
        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { message ->
                    addJsonObject {
                        put("role", message["role"])
                        put("content", message["content"])
                    }
                }
            })
            put("temperature", temperature)
        }.toString()

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected response code: ${response.code}")
                }

                val responseBody = response.body?.string() ?: throw IOException("Empty response body")
                val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

                val choices = jsonResponse["choices"]?.jsonArray
                if (choices.isNullOrEmpty()) {
                    throw IOException("No response choices returned from OpenAI API")
                }

                return choices[0].jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?: throw IOException("Could not extract content from response")
            }
        } catch (e: Exception) {
            println("Error calling OpenAI API: ${e.message}")
            return "I'm sorry, I encountered an error while processing your request. Please try again."
        }
    }

    /**
     * Reset the conversation history
     */
    fun resetConversation() {
        conversationHistory.clear()
        lastImageAnalysis = null
    }

    /**
     * Validate if the provided image path exists and is a valid image file
     */
    private fun validateImagePath(imagePath: String): Boolean {
        val file = File(imagePath)
        if (!file.exists() || !file.isFile) {
            return false
        }

        val validExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        val extension = file.extension.lowercase()
        return validExtensions.contains(extension)
    }

    /**
     * Encode image file to base64 string
     */
    private fun encodeImageToBase64(imagePath: String): String {
        val file = File(imagePath)
        val imageBytes = file.readBytes()
        return Base64.getEncoder().encodeToString(imageBytes)
    }

    /**
     * Get MIME type for image file
     */
    private fun getImageMimeType(imagePath: String): String {
        val extension = File(imagePath).extension.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> "image/jpeg" // default
        }
    }

    /**
     * Call OpenAI Vision API to analyze an image
     */
    private fun analyzeImageWithOpenAI(imagePath: String, query: String): String {
        if (!validateImagePath(imagePath)) {
            return "Invalid image path. Please provide a valid path to an image file (jpg, jpeg, png, gif, bmp, webp)."
        }

        try {
            val base64Image = encodeImageToBase64(imagePath)
            val mimeType = getImageMimeType(imagePath)

            val requestBody = buildJsonObject {
                put("model", "gpt-4o")
                put("messages", buildJsonArray {
                    addJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            addJsonObject {
                                put("type", "text")
                                put("text", """
                                    You are an agricultural expert specializing in paddy (rice) plant diseases. 
                                    Analyze this image of a crop/plant and provide ONLY detailed observations about what you see. Do NOT provide any treatment recommendations, solutions, or advice.
                                    
                                    Provide observations about:
                                    1. What type of plant/crop you see
                                    2. Any visible symptoms, diseases, or problems you observe
                                    3. Condition of leaves, stems, roots (if visible)
                                    4. Any signs of pests, discoloration, or abnormal growth
                                    5. Overall plant health assessment based on visual appearance
                                    
                                    User's question: $query
                                    
                                    Please be specific and detailed in your observations only. Do not suggest treatments, chemicals, or management practices. Simply describe what you observe in the image.
                                """.trimIndent())
                            }
                            addJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:$mimeType;base64,$base64Image")
                                })
                            }
                        })
                    }
                })
                put("temperature", 0.1)
                put("max_tokens", 500)
            }.toString()

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected response code: ${response.code}")
                }

                val responseBody = response.body?.string() ?: throw IOException("Empty response body")
                val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

                val choices = jsonResponse["choices"]?.jsonArray
                if (choices.isNullOrEmpty()) {
                    throw IOException("No response choices returned from OpenAI API")
                }

                return choices[0].jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?: throw IOException("Could not extract content from response")
            }
        } catch (e: Exception) {
            println("Error analyzing image: ${e.message}")
            return "I'm sorry, I encountered an error while analyzing the image. Please make sure the image path is correct and try again."
        }
    }

    /**
     * Check if a query is generic and requires image analysis
     */
    private fun isGenericQuery(query: String): Boolean {
        val lowerQuery = query.lowercase()

        // If query mentions specific disease names, it's not generic
        val specificDiseaseTerms = listOf(
            "blast", "blight", "hopperburn", "stem borer", "leaf folder",
            "brown planthopper", "white tip", "nematode", "sheath blight",
            "bacterial leaf blight", "tungro", "grassy stunt", "ragged stunt"
        )

        if (specificDiseaseTerms.any { lowerQuery.contains(it) }) {
            return false
        }

        // If query has specific symptoms described, it's not generic
        val specificSymptoms = listOf(
            "brown spots", "white tips", "dead hearts", "diamond shaped",
            "lesions", "yellowing", "wilting", "stunted growth", "holes in leaves"
        )

        if (specificSymptoms.any { lowerQuery.contains(it) }) {
            return false
        }

        // Check for truly generic patterns
        val genericPatterns = listOf(
            "what.*wrong.*plant",
            "help.*crop",
            "what.*this",
            "identify.*disease",
            "diagnose.*plant",
            "what.*happening.*plant",
            "plant.*sick",
            "crop.*problem",
            "problem.*crop",
            "crops.*damaged",
            "bad.*condition.*plant",
            "what.*can.*do.*crop",
            "crop.*not.*good"
        )

        return genericPatterns.any { pattern ->
            lowerQuery.matches(Regex(".*$pattern.*"))
        } || (lowerQuery.split(" ").size <= 4 &&
                (lowerQuery.contains("help") && !lowerQuery.contains("how")) ||
                (lowerQuery == "what is this") ||
                (lowerQuery.contains("problem") && lowerQuery.split(" ").size <= 3)) ||
                // Additional patterns for the specific examples from issue description
                (lowerQuery.contains("problem") && lowerQuery.contains("crop")) ||
                (lowerQuery.contains("crops") && lowerQuery.contains("damaged")) ||
                (lowerQuery.contains("bad condition") && lowerQuery.contains("plant")) ||
                (lowerQuery.contains("what can") && lowerQuery.contains("crop"))
    }
}