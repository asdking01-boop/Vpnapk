package com.example

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.size.Precision
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class KrasApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15) // Limit memory cache to 15% of available RAM
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB disk cache
                    .build()
            }
            .precision(Precision.INEXACT) // Downsample images efficiently to card dimensions
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        setupGlobalCrashHandler()
        try {
            com.example.player.PlayerImmersiveHelper.restoreDnd(applicationContext, com.example.data.local.KrasRepository(applicationContext))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stringWriter = StringWriter()
                val printWriter = PrintWriter(stringWriter)
                throwable.printStackTrace(printWriter)
                val stackTrace = stringWriter.toString()

                val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val timestamp = timeFormat.format(Date())

                val crashDetails = buildString {
                    appendLine("=== Kras TV Crash Report ===")
                    appendLine("Timestamp: $timestamp")
                    appendLine("Thread: ${thread.name} (id: ${thread.id})")
                    appendLine("Exception: ${throwable::class.java.name}")
                    appendLine("Message: ${throwable.message ?: "No error message"}")
                    appendLine()
                    appendLine("--- Stack Trace ---")
                    append(stackTrace)
                }

                val prefs = getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CRASH_REPORT, crashDetails)
                    .putLong(KEY_CRASH_TIMESTAMP, System.currentTimeMillis())
                    .commit() // Commit synchronously to guarantee file write before process termination
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    com.example.player.PlayerImmersiveHelper.restoreDnd(applicationContext, com.example.data.local.KrasRepository(applicationContext))
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable)
                } else {
                    exitProcess(1)
                }
            }
        }
    }

    companion object {
        const val PREFS_CRASH = "kras_crash_prefs"
        const val KEY_CRASH_REPORT = "last_crash_report"
        const val KEY_CRASH_TIMESTAMP = "last_crash_time"
    }
}
