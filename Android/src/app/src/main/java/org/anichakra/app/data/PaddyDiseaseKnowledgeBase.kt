package org.anichakra.app.data

import com.opencsv.CSVReaderBuilder
//Sudarson-start: Replace File with InputStream for Android assets access
//import java.io.File
import java.io.InputStream
//Sudarson-end: Replace File with InputStream for Android assets access
//Sudarson-start: Replace FileReader with InputStreamReader for Android assets access
//import java.io.FileReader
import java.io.InputStreamReader
//Sudarson-end: Replace FileReader with InputStreamReader for Android assets access
//Sudarson-start: Add Android Context for assets access
import android.content.Context
//Sudarson-end: Add Android Context for assets access

/**
 * Data class representing a plant disease entry in the knowledge base
 */
data class PlantDisease(
    val name: String,
    val stressType: String,
    val symptoms: String,
    val recommendedAgrochemicals: String
) {
    /**
     * Returns a formatted string representation of the plant disease for display
     */
    fun toFormattedString(): String {
        return """
           |Disease/Issue: $name
           |Type: $stressType
           |Symptoms: $symptoms
           |Recommended Treatment: $recommendedAgrochemicals
       """.trimMargin()
    }

    /**
     * Returns a string representation of the plant disease for embedding
     */
    fun toEmbeddingString(): String {
        return "Name: $name. Stress Type: $stressType. Symptoms: $symptoms. Recommended Agrochemicals: $recommendedAgrochemicals"
    }
}

/**
 * Class responsible for loading and managing the paddy disease knowledge base
 */
//Sudarson-start: Change constructor to accept Android Context and asset filename instead of file path
//class PaddyDiseaseKnowledgeBase(private val csvFilePath: String) {
class PaddyDiseaseKnowledgeBase(private val context: Context, private val csvFileName: String) {
    //Sudarson-end: Change constructor to accept Android Context and asset filename instead of file path
    private val diseases: List<PlantDisease>

    init {
        diseases = loadDiseases()
    }

    /**
     * Loads diseases from the CSV file
     */
    private fun loadDiseases(): List<PlantDisease> {
        val result = mutableListOf<PlantDisease>()

        try {
            //Sudarson-start: Replace File/FileReader with Android assets access using InputStream
            //val reader = CSVReaderBuilder(FileReader(File(csvFilePath)))
            val inputStream: InputStream = context.assets.open(csvFileName)
            val reader = CSVReaderBuilder(InputStreamReader(inputStream))
                //Sudarson-end: Replace File/FileReader with Android assets access using InputStream
                .withSkipLines(1) // Skip header row
                .build()

            var nextLine: Array<String>?
            while (reader.readNext().also { nextLine = it } != null) {
                if (nextLine!!.size >= 4) {
                    val disease = PlantDisease(
                        name = nextLine!![0].trim(),
                        stressType = nextLine!![1].trim(),
                        symptoms = nextLine!![2].trim(),
                        recommendedAgrochemicals = nextLine!![3].trim()
                    )
                    result.add(disease)
                }
            }

            reader.close()
            //Sudarson-start: Add InputStream cleanup for proper resource management
            inputStream.close()
            //Sudarson-end: Add InputStream cleanup for proper resource management
        } catch (e: Exception) {
            println("Error loading knowledge base: ${e.message}")
            e.printStackTrace()
        }

        return result
    }

    /**
     * Returns all diseases in the knowledge base
     */
    fun getAllDiseases(): List<PlantDisease> {
        return diseases
    }

    /**
     * Returns the number of diseases in the knowledge base
     */
    fun size(): Int {
        return diseases.size
    }
}