package com.example.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import com.example.data.repository.AssistantRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Runs on a schedule in the background (even when the app is closed) to
 * fetch fresh Saharanpur news, rewrite it with AI, and auto-publish to the
 * user's Facebook Page -- only when "Auto-publish" is switched on in the
 * app's Facebook settings. This is what makes the page post itself.
 */
class NewsAutoPublishWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext, CoroutineScope(Dispatchers.IO))
            val repository = AssistantRepository(
                dao = database.assistantDao(),
                communicationManager = CommunicationManager(applicationContext),
                calendarManager = CalendarManager(applicationContext),
                callScreenerManager = CallScreenerManager(applicationContext),
                context = applicationContext
            )

            val config = repository.getFacebookConfig()
            if (config.autoPublishEnabled) {
                repository.searchAndFetchSaharanpurNews("")
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
