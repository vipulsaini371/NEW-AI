package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.data.local.AppDatabase
import com.example.data.model.CommunicationLog
import com.example.service.DeviceSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires the instant a new SMS arrives on the device, so the assistant's
 * "who said what" memory is updated live -- not only when the app is opened.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val appContext = context.applicationContext
        val dao = AppDatabase.getDatabase(appContext, CoroutineScope(Dispatchers.IO)).assistantDao()
        val syncManager = DeviceSyncManager(appContext)

        CoroutineScope(Dispatchers.IO).launch {
            // Group multipart SMS into a single message body.
            val sender = messages.first().originatingAddress ?: "Unknown"
            val body = messages.joinToString("") { it.messageBody ?: "" }

            dao.insertCommunicationLog(
                CommunicationLog(
                    contactName = sender,
                    contactNumber = sender,
                    platform = "SMS",
                    type = "INCOMING",
                    content = body,
                    timestamp = System.currentTimeMillis()
                )
            )
            // Keep the sync watermark in step so the next manual/app-open sync
            // does not re-insert this same message.
            syncManager.fetchNewSms()
        }
    }
}
