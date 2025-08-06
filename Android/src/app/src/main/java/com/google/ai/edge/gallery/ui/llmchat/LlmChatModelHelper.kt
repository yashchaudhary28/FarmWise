/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.MAX_IMAGE_COUNT
import com.google.ai.edge.gallery.data.Model
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
//start-Sudarson:VectorStore
import org.anichakra.app.rag.VectorStore
import org.anichakra.app.data.PlantDisease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
//end-Sudarson:VectorStore

//start-Sudarson:RAG-Integration
import org.anichakra.app.rag.PaddyDiseaseRagApp
//import java.util.Properties
//end-Sudarson:RAG-Integration

private const val TAG = "AGLlmChatModelHelper"

typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

typealias CleanUpListener = () -> Unit

//start-Sudarson:RAG-Integration
private var ragApp: PaddyDiseaseRagApp? = null
//end-Sudarson:RAG-Integration

data class LlmModelInstance(val engine: LlmInference, var session: LlmInferenceSession)

object LlmChatModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  //start-Sudarson:VectorStore
  // Separate VectorStore instance - not tied to any specific model
  private var vectorStore: VectorStore? = null
  private var isVectorStoreInitialized: Boolean = false
  //end-Sudarson:VectorStore

  fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {

    //start-Sudarson:RAG-Integration
// Initialize RAG app
    initializeRagApp(context)
//end-Sudarson:RAG-Integration

    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKey.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKey.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKey.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKey.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKey.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    Log.d(TAG, "Initializing...")
    val preferredBackend =
      when (accelerator) {
        Accelerator.CPU.label -> LlmInference.Backend.CPU
        Accelerator.GPU.label -> LlmInference.Backend.GPU
        else -> LlmInference.Backend.GPU
      }
    val optionsBuilder =
      LlmInference.LlmInferenceOptions.builder()
        .setModelPath(model.getPath(context = context))
        .setMaxTokens(maxTokens)
        .setPreferredBackend(preferredBackend)
        .setMaxNumImages(if (model.llmSupportImage) MAX_IMAGE_COUNT else 0)
    val options = optionsBuilder.build()

    // Create an instance of the LLM Inference task and session.
    try {
      val llmInference = LlmInference.createFromOptions(context, options)

      val session =
        LlmInferenceSession.createFromOptions(
          llmInference,
          LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(topK)
            .setTopP(topP)
            .setTemperature(temperature)
            .setGraphOptions(
              GraphOptions.builder()
                .setEnableVisionModality(model.llmSupportImage)
                .build()
            )
            .build(),
        )
      model.instance = LlmModelInstance(engine = llmInference, session = session)

      //start-Sudarson:VectorStore
      // Initialize vector store only once (shared across all models)
      if (!isVectorStoreInitialized) {
        initializeVectorStore(context)
      }
      //end-Sudarson:VectorStore

    } catch (e: Exception) {
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
    onDone("")
  }


  //start-Sudarson:RAG-Integration
  /**
   * Initialize RAG application
   */
  private fun initializeRagApp(context: Context) {
    try {
      // Load OpenAI properties
      val properties = Properties()
      context.assets.open("config.properties").use { inputStream ->
        properties.load(inputStream)
      }

      // Initialize RAG app
      ragApp = PaddyDiseaseRagApp( properties, context, "plant_diseases.csv")
      Log.d(TAG, "RAG app initialized successfully")

    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize RAG app: ${e.message}")
      ragApp = null
    }
  }
//end-Sudarson:RAG-Integration

  //start-Sudarson:RAG-Integration
  /**
   * Process query with RAG enhancement
   */
  fun processQueryWithRAG(query: String, imagePath: String? = null): String {
    return ragApp?.processQueryWithImageSupport(query, imagePath)
      ?: "RAG system not available. Please ensure config.properties and plant_diseases.csv are in assets folder."
  }

  /**
   * Process simple query with RAG
   */
  fun processSimpleQueryWithRAG(query: String): String {
    return ragApp?.processQuery(query)
      ?: "RAG system not available."
  }

  /**
   * Reset RAG conversation
   */
  fun resetRAGConversation() {
    ragApp?.resetConversation()
  }

  /**
   * Check if RAG is available
   */
  fun isRAGAvailable(): Boolean {
    return ragApp != null
  }

  /**
   * Enhanced runInference that can use RAG preprocessing
   */
  fun runInferenceWithRAG(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    images: List<Bitmap> = listOf(),
    audioClips: List<ByteArray> = listOf(),
    useRAG: Boolean = true,
    imagePath: String? = null
  ) {
    val processedInput = if (useRAG && ragApp != null) {
      ragApp!!.processQueryWithImageSupport(input, imagePath)
    } else {
      input
    }

    // Call original runInference with processed input
    runInference(model, processedInput, resultListener, cleanUpListener, images, audioClips)
  }
//end-Sudarson:RAG-Integration



  //start-Sudarson:VectorStore
  /**
   * Initialize vector store (shared instance)
   */
  private fun initializeVectorStore(context: Context) {
    try {
      //start-Sudarson:VectorStore-MediaPipe
      // First try to create with MediaPipe using Gemma model
      val embeddingModelPath = "${context.getExternalFilesDir(null)}/gemma2_embedding_model" // Adjust path as needed
      vectorStore = VectorStore.createWithMediaPipe(context, embeddingModelPath)

      if (vectorStore == null) {
        Log.w(TAG, "MediaPipe vector store initialization failed, falling back to OpenAI")
        // Fallback to OpenAI if MediaPipe fails
        val properties = Properties()
        context.assets.open("config.properties").use { inputStream ->
          properties.load(inputStream)
        }
        vectorStore = VectorStore.createWithOpenAI(properties)
      }
      //end-Sudarson:VectorStore-MediaPipe

      if (vectorStore != null) {
        // Load plant disease data
        val diseases = loadPlantDiseases(context)
        vectorStore!!.addDiseases(diseases)
        isVectorStoreInitialized = true
        Log.d(TAG, "Vector store initialized successfully with ${diseases.size} diseases")
      } else {
        Log.e(TAG, "Failed to initialize vector store - both MediaPipe and OpenAI failed")
      }

    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize vector store: ${e.message}")
      e.printStackTrace()
      // Don't fail the entire model initialization if vector store fails
      vectorStore = null
      isVectorStoreInitialized = false
    }
  }

  /**
   * Load plant disease data from assets JSON file
   */
  private fun loadPlantDiseases(context: Context): List<PlantDisease> {
    return try {
      // Load from JSON file in assets
      val jsonString = context.assets.open("plant_diseases.json").bufferedReader().use { it.readText() }

      // Parse JSON and return list of PlantDisease objects
      // You would need to implement JSON parsing based on your PlantDisease structure
      // For example using Gson or Kotlinx Serialization

      // Placeholder - replace with actual JSON parsing
      val gson = com.google.gson.Gson()
      val plantDiseaseArray = gson.fromJson(jsonString, Array<PlantDisease>::class.java)
      plantDiseaseArray.toList()

    } catch (e: Exception) {
      Log.e(TAG, "Failed to load plant diseases: ${e.message}")
      emptyList()
    }
  }

  /**
   * Search for relevant context using the shared vector store
   */
  fun searchRelevantContext(query: String): List<PlantDisease> {
    return vectorStore?.search(query) ?: emptyList()
  }

  /**
   * Get the current vector store instance
   */
  fun getVectorStore(): VectorStore? {
    return vectorStore
  }

  /**
   * Check if vector store is available
   */
  fun isVectorStoreAvailable(): Boolean {
    return vectorStore != null && isVectorStoreInitialized
  }

  /**
   * Manually reinitialize vector store if needed
   */
  fun reinitializeVectorStore(context: Context) {
    cleanupVectorStore()
    initializeVectorStore(context)
  }

  /**
   * Clean up vector store
   */
  private fun cleanupVectorStore() {
    vectorStore?.cleanup()
    vectorStore = null
    isVectorStoreInitialized = false
  }
  //end-Sudarson:VectorStore

  fun resetSession(model: Model) {
    try {
      Log.d(TAG, "Resetting session for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      val session = instance.session
      session.close()

      val inference = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKey.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKey.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKey.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val newSession =
        LlmInferenceSession.createFromOptions(
          inference,
          LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(topK)
            .setTopP(topP)
            .setTemperature(temperature)
            .setGraphOptions(
              GraphOptions.builder()
                .setEnableVisionModality(model.llmSupportImage)
                .build()
            )
            .build(),
        )
      instance.session = newSession
      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset session", e)
    }
  }

  fun cleanUp(model: Model) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as LlmModelInstance

    try {
      instance.session.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the LLM Inference session: ${e.message}")
    }

    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the LLM Inference engine: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }

    model.instance = null
    Log.d(TAG, "Clean up done.")
  }

  /**
   * Clean up all resources including shared vector store
   * Call this when the entire application is shutting down
   */
  fun cleanUpAll() {
    //start-Sudarson:VectorStore
    cleanupVectorStore()
    //end-Sudarson:VectorStore

    //start-Sudarson:RAG-Integration
    ragApp?.resetConversation()
    ragApp = null
    //end-Sudarson:RAG-Integration

    // Clean up any remaining listeners
    cleanUpListeners.clear()
    Log.d(TAG, "All resources cleaned up.")
  }

  fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    images: List<Bitmap> = listOf(),
    audioClips: List<ByteArray> = listOf(),
  ) {
    val instance = model.instance as LlmModelInstance

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    // Start async inference.
    //
    // For a model that supports image modality, we need to add the text query chunk before adding
    // image.
    val session = instance.session
    if (input.trim().isNotEmpty()) {
      session.addQueryChunk(input)
    }
    for (image in images) {
      session.addImage(BitmapImageBuilder(image).build())
    }
    for (audioClip in audioClips) {
      // Uncomment when audio is supported.
      // session.addAudio(audioClip)
    }
    val unused = session.generateResponseAsync(resultListener)
  }
}