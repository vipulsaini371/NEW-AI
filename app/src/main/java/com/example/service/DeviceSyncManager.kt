package com.example.service

import android.content.ContentResolver
import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import com.example.data.model.CommunicationLog

/**
 * Pulls REAL data from the phone's own SMS inbox and Call Log into the app's
 * communication_logs table, so "Calls" and "Messages" screens (and the
 * "who said what" memory used by the AI) reflect the user's actual phone --
 * not demo/simulated entries.
 *
 * Requires READ_SMS and READ_CALL_LOG permission to be granted at runtime
 * before calling these (see MainActivity's permission request list).
 */
class DeviceSyncManager(private val context: Context) {

    private val TAG = "DeviceSyncManager"
    private val prefs = context.getSharedPreferences("ai_assistant_prefs", Context.MODE_PRIVATE)

    /** Reads SMS inbox messages newer than the last sync point. */
    fun fetchNewSms(): List<CommunicationLog> {
        val results = mutableListOf<CommunicationLog>()
        val lastSynced = prefs.getLong("last_sms_sync", 0L)
        var newestSeen = lastSynced

        try {
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            )
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} > ?",
                arrayOf(lastSynced.toString()),
                "${Telephony.Sms.DATE} ASC"
            )
            cursor?.use {
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)

                while (it.moveToNext()) {
                    val address = if (addressIdx >= 0) it.getString(addressIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx >= 0) it.getLong(dateIdx) else System.currentTimeMillis()
                    val smsType = if (typeIdx >= 0) it.getInt(typeIdx) else Telephony.Sms.MESSAGE_TYPE_INBOX
                    val isOutgoing = smsType == Telephony.Sms.MESSAGE_TYPE_SENT ||
                        smsType == Telephony.Sms.MESSAGE_TYPE_OUTBOX

                    results.add(
                        CommunicationLog(
                            contactName = resolveContactName(address),
                            contactNumber = address,
                            platform = "SMS",
                            type = if (isOutgoing) "OUTGOING" else "INCOMING",
                            content = body,
                            timestamp = date
                        )
                    )
                    if (date > newestSeen) newestSeen = date
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading device SMS inbox: ${e.message}", e)
        }

        if (newestSeen > lastSynced) {
            prefs.edit().putLong("last_sms_sync", newestSeen).apply()
        }
        return results
    }

    /** Reads call log entries newer than the last sync point. */
    fun fetchNewCallLog(): List<CommunicationLog> {
        val results = mutableListOf<CommunicationLog>()
        val lastSynced = prefs.getLong("last_call_sync", 0L)
        var newestSeen = lastSynced

        try {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DATE} > ?",
                arrayOf(lastSynced.toString()),
                "${CallLog.Calls.DATE} ASC"
            )
            cursor?.use {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                while (it.moveToNext()) {
                    val number = if (numberIdx >= 0) it.getString(numberIdx) ?: "Unknown" else "Unknown"
                    val cachedName = if (nameIdx >= 0) it.getString(nameIdx) else null
                    val callType = if (typeIdx >= 0) it.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                    val date = if (dateIdx >= 0) it.getLong(dateIdx) else System.currentTimeMillis()
                    val duration = if (durationIdx >= 0) it.getInt(durationIdx) else 0

                    val type = when (callType) {
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "MISSED"
                        else -> "INCOMING"
                    }

                    results.add(
                        CommunicationLog(
                            contactName = cachedName ?: resolveContactName(number),
                            contactNumber = number,
                            platform = "CALL",
                            type = type,
                            content = when (type) {
                                "MISSED" -> "Missed call"
                                "OUTGOING" -> "Outgoing call"
                                else -> "Incoming call"
                            },
                            timestamp = date,
                            callDurationSeconds = duration
                        )
                    )
                    if (date > newestSeen) newestSeen = date
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading device call log: ${e.message}", e)
        }

        if (newestSeen > lastSynced) {
            prefs.edit().putLong("last_call_sync", newestSeen).apply()
        }
        return results
    }

    /** Looks up a saved contact's display name for a phone number, if any. */
    private fun resolveContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown") return "Unknown"
        try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        val name = cursor.getString(nameIdx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for $phoneNumber: ${e.message}")
        }
        return phoneNumber
    }
}
