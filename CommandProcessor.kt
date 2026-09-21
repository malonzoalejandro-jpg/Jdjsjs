package com.example.jarvis

import android.app.DownloadManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar
import java.util.regex.Pattern

/**
 * Turns recognized speech into phone actions.
 *
 * Primary path: send the text to Gemini, which returns a structured JSON
 * action (handles natural phrasing, and can answer general questions
 * directly). Fallback path: a simple local keyword/regex parser, used if
 * Gemini is unreachable or no key is configured (no internet, for example)
 * — covers the most common commands without needing a network call at all.
 */
object CommandProcessor {

    private const val TAG = "CommandProcessor"

    private val TIME_PATTERN = Pattern.compile(
        "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE
    )
    private val URL_PATTERN = Pattern.compile("https?://\\S+")

    fun process(context: Context, spokenText: String) {
        val text = spokenText.trim()
        if (text.isEmpty()) return
        Log.d(TAG, "Processing command: \"$text\"")

        val geminiKey = BuildConfig.GEMINI_API_KEY
        if (geminiKey.isNullOrBlank()) {
            processLocally(context, text)
            return
        }

        GeminiInterpreter.interpret(geminiKey, text) { result ->
            if (result == null) {
                processLocally(context, text)
            } else {
                dispatch(context, result, text)
            }
        }
    }

    private fun dispatch(context: Context, result: JSONObject, originalText: String) {
        when (result.optString("action")) {
            "set_alarm" -> {
                val hour = result.optInt("hour", -1)
                val minute = result.optInt("minute", 0)
                if (hour in 0..23) setAlarm(context, hour, minute)
                else processLocally(context, originalText)
            }
            "set_timer" -> {
                val seconds = result.optInt("seconds", -1)
                if (seconds > 0) setTimer(context, seconds)
                else respond(context, "I didn't catch how long to set the timer for.")
            }
            "get_time" -> handleWhatTime(context)
            "play_music" -> {
                val query = result.optString("query")
                if (query.isNotBlank()) handlePlayMusic(context, query)
                else respond(context, "I didn't catch what to play.")
            }
            "open_app" -> {
                val appName = result.optString("app_name")
                if (appName.isNotBlank()) handleOpenApp(context, appName)
                else respond(context, "I didn't catch which app.")
            }
            "flashlight" -> handleFlashlight(context, result.optString("state", "on"))
            "call" -> {
                val contact = result.optString("contact")
                if (contact.isNotBlank()) handleCall(context, contact)
                else respond(context, "I didn't catch who to call.")
            }
            "text" -> {
                val contact = result.optString("contact")
                val message = result.optString("message")
                if (contact.isNotBlank() && message.isNotBlank()) handleText(context, contact, message)
                else respond(context, "I didn't catch the contact or the message.")
            }
            "adjust_volume" -> handleVolume(context, result.optString("direction", "up"))
            "web_search" -> handleWebSearch(context, result.optString("query"))
            "wifi_settings" -> openSettingsScreen(context, Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
            "bluetooth_settings" -> openSettingsScreen(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
            "download_file" -> {
                val url = result.optString("url")
                if (url.isNotBlank()) handleDownload(context, url)
                else respond(context, "I didn't catch a link to download.")
            }
            "share_last_media" -> handleShareLastMedia(context)
            "chat" -> respond(context, result.optString("reply").ifBlank { "I'm here, sir." })
            else -> respond(context, result.optString("reply").ifBlank { "I'm here, sir. Could you say that again?" })
        }
    }

    /**
     * Offline fallback used when there's no internet or no Gemini key —
     * simple keyword matching instead of real language understanding, but
     * covers the commands that don't strictly need a network call to
     * execute anyway (everything except playing a specific song, which
     * needs Spotify's own search to work).
     */
    private fun processLocally(context: Context, text: String) {
        val lower = text.lowercase()
        when {
            lower.contains("alarm") -> {
                val parsed = parseTime(lower)
                if (parsed != null) setAlarm(context, parsed.first, parsed.second)
                else respond(context, "I heard \"alarm\" but couldn't figure out the time.")
            }
            lower.contains("timer") -> respond(context, "I need a network connection to set a timer by voice right now, sir.")
            lower.contains("what time") || lower.contains("current time") -> handleWhatTime(context)
            lower.contains("flashlight") || lower.contains("torch") ->
                handleFlashlight(context, if (lower.contains("off")) "off" else "on")
            lower.contains("volume") || lower.contains("louder") || lower.contains("quieter") -> {
                val direction = when {
                    lower.contains("up") || lower.contains("louder") || lower.contains("raise") -> "up"
                    lower.contains("mute") || lower.contains("silence") -> "mute"
                    else -> "down"
                }
                handleVolume(context, direction)
            }
            lower.contains("wifi") || lower.contains("wi-fi") ->
                openSettingsScreen(context, Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
            lower.contains("bluetooth") ->
                openSettingsScreen(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
            lower.startsWith("open ") -> handleOpenApp(context, lower.removePrefix("open ").trim())
            URL_PATTERN.matcher(text).find() && lower.contains("download") -> {
                val matcher = URL_PATTERN.matcher(text)
                if (matcher.find()) handleDownload(context, matcher.group())
            }
            lower.contains("share") || lower.contains("upload") -> handleShareLastMedia(context)
            else -> respond(context, "I'm having trouble reaching the network right now, sir, but here's what's still working offline: alarms, the time, flashlight, volume, opening apps, and Wi-Fi/Bluetooth settings.")
        }
    }

    private fun setAlarm(context: Context, hour: Int, minute: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
            respond(context, "Alarm set for %02d:%02d, sir.".format(hour, minute))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm: ${e.message}", e)
            respond(context, "Couldn't set the alarm — no clock app found?")
        }
    }

    private fun setTimer(context: Context, seconds: Int) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
            respond(context, "Timer started, sir.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set timer: ${e.message}", e)
            respond(context, "Couldn't set the timer — no clock app found?")
        }
    }

    private fun handleWhatTime(context: Context) {
        val now = Calendar.getInstance()
        respond(context, "It's %02d:%02d, sir.".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)))
    }

    private fun handlePlayMusic(context: Context, query: String) {
        respond(context, "Here's $query on Spotify, sir — just hit play.")
        SpotifyController.searchAndOpen(context, query)
    }

    private fun handleOpenApp(context: Context, appName: String) {
        val opened = try {
            AppLauncher.open(context, appName)
        } catch (e: Exception) {
            Log.e(TAG, "Open app failed: ${e.message}", e)
            false
        }
        if (opened) respond(context, "Opening $appName, sir.")
        else respond(context, "Couldn't find an app called $appName.")
    }

    private fun handleFlashlight(context: Context, state: String) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, state.equals("on", ignoreCase = true))
                respond(context, "Flashlight ${if (state.equals("on", true)) "on" else "off"}, sir.")
            } else {
                respond(context, "This device doesn't seem to have a flashlight.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flashlight toggle failed: ${e.message}", e)
            respond(context, "Couldn't control the flashlight.")
        }
    }

    private fun handleCall(context: Context, contact: String) {
        respond(context, "Calling $contact, sir.")
        PhoneActions.call(context, contact)
    }

    private fun handleText(context: Context, contact: String, message: String) {
        respond(context, "Texting $contact, sir.")
        PhoneActions.sendText(context, contact, message)
    }

    private fun handleVolume(context: Context, direction: String) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val adjustment = when (direction.lowercase()) {
                "down" -> AudioManager.ADJUST_LOWER
                "mute" -> AudioManager.ADJUST_MUTE
                else -> AudioManager.ADJUST_RAISE
            }
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, AudioManager.FLAG_SHOW_UI)
            respond(context, "Volume ${direction.lowercase()}, sir.")
        } catch (e: Exception) {
            Log.e(TAG, "Volume adjustment failed: ${e.message}", e)
            respond(context, "Couldn't adjust the volume.")
        }
    }

    private fun handleWebSearch(context: Context, query: String) {
        if (query.isBlank()) {
            respond(context, "What should I search for?")
            return
        }
        respond(context, "Searching for $query, sir.")
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(webIntent)
        }
    }

    /** Opens a system settings screen. Android doesn't let third-party apps
     *  silently toggle Wi-Fi/Bluetooth anymore (a privacy restriction since
     *  Android 10) — this gets you one tap away instead. */
    private fun openSettingsScreen(context: Context, action: String, label: String) {
        try {
            val intent = Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
            respond(context, "Opening $label, sir.")
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't open $label: ${e.message}", e)
            respond(context, "Couldn't open $label.")
        }
    }

    /** Downloads a direct file URL into the phone's Downloads folder using
     *  Android's own Download Manager — works for a direct video/file link,
     *  not for pulling videos out of an app like YouTube or Instagram
     *  (those platforms don't allow that, by design). */
    private fun handleDownload(context: Context, url: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    url.substringAfterLast('/').ifBlank { "download" }
                )
                setAllowedOverMetered(true)
            }
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            respond(context, "Downloading that now, sir — check your notifications when it's done.")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            respond(context, "Couldn't start that download.")
        }
    }

    /** Opens the share sheet with your most recent photo/video so you can
     *  send it to whichever app you want — Drive, WhatsApp, Instagram, etc.
     *  Actually auto-posting to a specific platform isn't something I can
     *  build without using that platform's own upload API directly (most
     *  don't allow this from an unofficial app), so this gets you one tap
     *  from sending it anywhere. */
    private fun handleShareLastMedia(context: Context) {
        try {
            val uri = latestMediaUri(context)
            if (uri == null) {
                respond(context, "I couldn't find any recent photos or videos.")
                return
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share via").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            respond(context, "Here's your most recent media, sir — pick where to send it.")
        } catch (e: Exception) {
            Log.e(TAG, "Share last media failed: ${e.message}", e)
            respond(context, "Couldn't open sharing — check media permissions in Settings.")
        }
    }

    private fun latestMediaUri(context: Context): Uri? {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC LIMIT 1"

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    /** Speaks the response via Fish Audio if configured, logs it to the
     *  in-app activity feed, and always shows a toast as a fallback. */
    private fun respond(context: Context, message: String) {
        showToast(context, message)
        JarvisEventLog.add("Jarvis: $message")
        val fishKey = BuildConfig.FISH_API_KEY
        val voiceId = BuildConfig.FISH_VOICE_REFERENCE_ID ?: ""
        if (!fishKey.isNullOrBlank()) {
            FishTtsClient.speak(context, fishKey, voiceId, message)
        }
    }

    private fun parseTime(text: String): Pair<Int, Int>? {
        val matcher = TIME_PATTERN.matcher(text)
        if (!matcher.find()) return null

        var hour = matcher.group(1)?.toIntOrNull() ?: return null
        val minute = matcher.group(2)?.toIntOrNull() ?: 0
        val meridiem = matcher.group(3)?.lowercase()

        if (meridiem == "pm" && hour < 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0

        if (hour !in 0..23 || minute !in 0..59) return null
        return Pair(hour, minute)
    }

    private fun showToast(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
