package com.example.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Resolves a spoken contact name to a phone number, and places calls /
 * sends texts. Requires READ_CONTACTS, CALL_PHONE, and SEND_SMS permissions
 * (requested at runtime in MainActivity).
 */
object PhoneActions {

    private const val TAG = "PhoneActions"

    fun call(context: Context, contactOrNumber: String) {
        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            toast(context, "Call permission not granted.")
            return
        }
        val number = resolveNumber(context, contactOrNumber)
        if (number == null) {
            toast(context, "Couldn't find a number for \"$contactOrNumber\".")
            return
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Call failed: ${e.message}", e)
            toast(context, "Couldn't place the call.")
        }
    }

    fun sendText(context: Context, contactOrNumber: String, message: String) {
        if (!hasPermission(context, Manifest.permission.SEND_SMS)) {
            toast(context, "Text permission not granted.")
            return
        }
        val number = resolveNumber(context, contactOrNumber)
        if (number == null) {
            toast(context, "Couldn't find a number for \"$contactOrNumber\".")
            return
        }
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, message, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Send text failed: ${e.message}", e)
            toast(context, "Couldn't send the text.")
        }
    }

    /** If [nameOrNumber] already looks like a phone number, use it directly.
     *  Otherwise look it up in Contacts by display name. */
    private fun resolveNumber(context: Context, nameOrNumber: String): String? {
        val digitCount = nameOrNumber.count { it.isDigit() }
        if (digitCount >= 7) return nameOrNumber

        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return null

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameOrNumber%")

        context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                return cursor.getString(numberIndex)
            }
        }
        return null
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun toast(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
