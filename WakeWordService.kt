package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Continuously listens using Android's built-in speech recognizer and
 * watches for the word "jarvis" anywhere in what it hears. This
 * needs no external account, API key, or wake-word engine — just
 * Android's own on-device speech recognition, restarted in a loop.
 *
 * Trade-off vs a dedicated wake-word engine (like Picovoice): slightly
 * higher battery use, and a brief gap after each listening session while
 * it restarts.
 */
class WakeWordService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val notificationChannelId = "jarvis_service_channel"
    private val notificationId = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Listening for \"Jarvis\"…")
        try {
            // Android 10+ requires the foreground service type to be passed
            // explicitly here (not just declared in the manifest) or the
            // service can silently fail to keep microphone access once the
            // app isn't visibly in the foreground.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e("WakeWordService", "startForeground failed: ${e.message}", e)
            debugToast(this, "Couldn't start background listening: ${e.message}")
            return
        }
        startListeningLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListeningLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("WakeWordService", "Speech recognition not available on this device")
            updateNotification("Speech recognition isn't available on this device")
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.firstOrNull()?.lowercase()?.trim() ?: ""
                Log.d("WakeWordService", "Heard: \"$heard\"")
                if (heard.isNotBlank()) {
                    debugToast(context = this@WakeWordService, "Heard: \"$heard\"")
                    JarvisEventLog.add("You: $heard")
                }

                // Loosened to just the word "jarvis" rather than the exact
                // three-word phrase — Android's plain speech recognizer
                // often drops or garbles short filler words like "wake up",
                // so matching on the one distinctive word is far more
                // reliable in practice.
                val jarvisIndex = heard.indexOf("jarvis")
                if (jarvisIndex != -1) {
                    // If you said the command in the same breath (e.g.
                    // "Jarvis set an alarm for 6pm"), use it directly
                    // instead of waiting for you to repeat it.
                    val trailing = heard.substring(jarvisIndex + "jarvis".length)
                        .trim(' ', ',', '.', '!', '?')
                    onWakeWordDetected(trailing)
                } else {
                    restartListening()
                }
            }

            override fun onError(error: Int) {
                // ERROR_NO_MATCH (7) and ERROR_SPEECH_TIMEOUT (6) are normal
                // during silence — everything else is worth surfacing.
                if (error != 6 && error != 7) {
                    debugToast(this@WakeWordService, "Recognizer error code: $error")
                }
                restartListening()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(recognizerIntent)
    }

    private fun restartListening() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        // Small delay avoids hammering the recognizer service in a tight loop.
        mainHandler.postDelayed({ startListeningLoop() }, 300)
    }

    private fun onWakeWordDetected(embeddedCommand: String) {
        updateNotification("Heard \"Jarvis\"…")
        JarvisEventLog.add("— wake word detected —")
        speechRecognizer?.destroy()
        speechRecognizer = null

        val fishKey = BuildConfig.FISH_API_KEY
        val afterGreeting: () -> Unit = {
            if (embeddedCommand.length >= 3) {
                // You said the command in the same breath as the wake word.
                CommandProcessor.process(applicationContext, embeddedCommand)
                resumeListeningForWakeWord()
            } else {
                startCommandRecognition()
            }
        }

        if (fishKey.isNullOrBlank()) {
            afterGreeting()
        } else {
            updateNotification("Yes, sir.")
            FishTtsClient.speak(
                context = applicationContext,
                apiKey = fishKey,
                referenceId = BuildConfig.FISH_VOICE_REFERENCE_ID ?: "",
                text = "Yes, sir.",
                cacheKey = "yes_sir" // generated once, replayed from disk after that
            ) { afterGreeting() }
        }
    }

    private fun startCommandRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            resumeListeningForWakeWord()
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val command = matches?.firstOrNull() ?: ""
                Log.d("WakeWordService", "Heard command: $command")
                JarvisEventLog.add("You: $command")
                CommandProcessor.process(applicationContext, command)
                cleanupRecognizerAndResume()
            }

            override fun onError(error: Int) {
                Log.e("WakeWordService", "Speech recognition error: $error")
                cleanupRecognizerAndResume()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer.startListening(recognizerIntent)
    }

    private fun cleanupRecognizerAndResume() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        resumeListeningForWakeWord()
    }

    private fun resumeListeningForWakeWord() {
        updateNotification("Listening for \"Jarvis\"…")
        startListeningLoop()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                notificationChannelId,
                "Jarvis background listener",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, buildNotification(text))
    }

    private fun debugToast(context: android.content.Context, text: String) {
        mainHandler.post {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }

    /** Some phones stop a foreground service when the app's task is swiped
     *  away from Recents, even though plain Android is supposed to keep
     *  foreground services alive. This schedules an immediate restart as a
     *  safety net for those cases. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartIntent = Intent(applicationContext, WakeWordService::class.java).apply {
            setPackage(packageName)
        }
        val restartPendingIntent = android.app.PendingIntent.getService(
            this, 1, restartIntent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1000,
            restartPendingIntent
        )
    }
}
