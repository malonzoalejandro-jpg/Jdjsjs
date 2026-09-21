package com.example.jarvis

import android.content.Context
import android.content.pm.PackageManager

/**
 * Finds an installed app whose label matches [appName] (partial, case
 * insensitive) and launches it. Requires the QUERY_ALL_PACKAGES permission
 * (declared in the manifest) so the app can see other installed apps on
 * Android 11+ — fine for a personal sideload, but Play Store review
 * restricts this permission, so this approach isn't publish-ready as-is.
 */
object AppLauncher {

    fun open(context: Context, appName: String): Boolean {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val target = apps.firstOrNull { app ->
            pm.getApplicationLabel(app).toString().contains(appName, ignoreCase = true)
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(target.packageName) ?: return false
        context.startActivity(launchIntent)
        return true
    }
}
