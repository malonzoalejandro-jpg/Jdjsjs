package com.example.jarvis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLEncoder

/**
 * Opens Spotify's search for [query]. This needs no API key, Client ID,
 * Client Secret, or Spotify Premium account — Spotify's developer signup
 * now requires Premium as of their February 2026 policy change, so this
 * uses a plain search deep link instead of the Web API.
 *
 * Trade-off: this opens search results in Spotify rather than starting
 * playback automatically — one tap on the top result plays it.
 */
object SpotifyController {

    private const val TAG = "SpotifyController"

    fun searchAndOpen(context: Context, query: String) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")).apply {
            setPackage("com.spotify.music")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Spotify app search failed, falling back to web: ${e.message}")
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://open.spotify.com/search/$encoded")
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(webIntent)
        }
    }
}
