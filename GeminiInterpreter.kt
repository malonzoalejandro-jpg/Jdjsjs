package com.example.jarvis

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends the spoken command text to Gemini to turn natural language into a
 * structured action, e.g.:
 *   "set an alarm for half past six in the evening"
 *   -> {"action":"set_alarm","hour":18,"minute":30}
 *
 * Calls back with null on any error — the caller should fall back to a
 * simpler local parser in that case.
 */
object GeminiInterpreter {

    private const val TAG = "GeminiInterpreter"
    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val executor = Executors.newSingleThreadExecutor()

    private val SYSTEM_PROMPT = """
        You turn a spoken voice command into a single JSON object describing
        the action to take. Only ever respond with raw JSON, nothing else —
        no markdown, no code fences, no explanation.

        Supported actions:
        - {"action":"set_alarm","hour":<0-23>,"minute":<0-59>}
        - {"action":"set_timer","seconds":<positive integer>}
        - {"action":"get_time"}
        - {"action":"play_music","query":"<song name, and artist if mentioned>"}
        - {"action":"open_app","app_name":"<app name>"}
        - {"action":"flashlight","state":"on"|"off"}
        - {"action":"call","contact":"<contact name or phone number>"}
        - {"action":"text","contact":"<contact name or phone number>","message":"<message body>"}
        - {"action":"adjust_volume","direction":"up"|"down"|"mute"}
        - {"action":"web_search","query":"<what to search for>"}
        - {"action":"wifi_settings"}
        - {"action":"bluetooth_settings"}
        - {"action":"download_file","url":"<a direct URL mentioned in the command>"}
        - {"action":"share_last_media"}
        - {"action":"chat","reply":"<a natural, spoken-friendly reply>"}

        Use "chat" for anything that isn't clearly one of the device actions
        above — general questions, small talk, or a command you're not sure
        how to map to a device action. Always give a real, helpful,
        conversational reply — never say you don't understand.
    """.trimIndent()

    fun interpret(apiKey: String, spokenText: String, callback: (JSONObject?) -> Unit) {
        executor.execute {
            val result = try {
                callGemini(apiKey, spokenText)
            } catch (e: Exception) {
                Log.e(TAG, "Gemini call failed: ${e.message}", e)
                null
            }
            callback(result)
        }
    }

    private fun callGemini(apiKey: String, spokenText: String): JSONObject? {
        val url = URL(ENDPOINT)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-goog-api-key", apiKey)
        connection.doOutput = true
        connection.connectTimeout = 8000
        connection.readTimeout = 8000

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
            ))
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", spokenText)))
            ))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0)
            })
        }

        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream.bufferedReader().use { it.readText() }

        if (responseCode !in 200..299) {
            Log.e(TAG, "Gemini error ($responseCode): $responseText")
            return null
        }

        val content = JSONObject(responseText)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()

        return try {
            JSONObject(content)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't parse model output as JSON: $content")
            null
        }
    }
}
