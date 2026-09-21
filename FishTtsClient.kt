package com.example.jarvis

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Converts text to speech using Fish Audio and plays it back.
 * Get an API key at https://fish.audio/app/api-keys and pick a voice on
 * fish.audio — its reference_id is in that voice's page URL
 * (fish.audio/m/<this part>).
 */
object FishTtsClient {

    private const val TAG = "FishTtsClient"
    private const val ENDPOINT = "https://api.fish.audio/v1/tts"
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Speaks [text] aloud. Calls [onDone] on the main thread once playback
     * finishes (or immediately on any failure), so callers can chain the
     * next step — e.g. only start listening for a command after Jarvis has
     * finished saying "Yes, sir."
     *
     * Pass a stable [cacheKey] for phrases that repeat often (like the
     * wake-word acknowledgment) so it's only generated once and replayed
     * from disk after that.
     */
    fun speak(
        context: Context,
        apiKey: String,
        referenceId: String,
        text: String,
        cacheKey: String? = null,
        onDone: () -> Unit = {}
    ) {
        executor.execute {
            try {
                val cacheFile = cacheKey?.let { File(context.cacheDir, "$it.mp3") }
                val audioFile = if (cacheFile != null && cacheFile.exists()) {
                    cacheFile
                } else {
                    val bytes = generateSpeech(apiKey, referenceId, text)
                    val target = cacheFile ?: File.createTempFile("jarvis_tts", ".mp3", context.cacheDir)
                    FileOutputStream(target).use { it.write(bytes) }
                    target
                }
                playAudio(context, audioFile, onDone)
            } catch (e: Exception) {
                Log.e(TAG, "Fish Audio TTS failed: ${e.message}", e)
                Handler(Looper.getMainLooper()).post {
                    // Surfaced visibly instead of failing silently — a wrong
                    // key, wrong voice ID, or network issue used to just
                    // mean "Jarvis never talks" with no clue why.
                    android.widget.Toast.makeText(
                        context, "TTS failed: ${e.message}", android.widget.Toast.LENGTH_LONG
                    ).show()
                    onDone()
                }
            }
        }
    }

    private fun generateSpeech(apiKey: String, referenceId: String, text: String): ByteArray {
        val url = URL(ENDPOINT)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("model", "s1")
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 15000

        val body = JSONObject().apply {
            put("text", text)
            put("format", "mp3")
            if (referenceId.isNotBlank()) put("reference_id", referenceId)
        }

        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = connection.responseCode
        if (code !in 200..299) {
            val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw RuntimeException("Fish Audio error ($code): $err")
        }
        return connection.inputStream.use { it.readBytes() }
    }

    private fun playAudio(context: Context, file: File, onDone: () -> Unit) {
        val player = MediaPlayer()
        player.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setDataSource(file.absolutePath)
        player.setOnCompletionListener {
            it.release()
            Handler(Looper.getMainLooper()).post { onDone() }
        }
        player.setOnErrorListener { mp, what, extra ->
            mp.release()
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context, "Playback error ($what, $extra)", android.widget.Toast.LENGTH_LONG
                ).show()
                onDone()
            }
            true
        }
        player.prepare()
        player.start()
    }
}
