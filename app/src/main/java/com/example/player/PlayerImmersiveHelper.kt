package com.example.player

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.data.local.KrasRepository

object PlayerImmersiveHelper {

    /**
     * Hides system status bar and navigation bar completely.
     * Uses transient bars by swipe so bars briefly appear only when user swipes from screen edge.
     * Allows video to fill display notch / cutout area and keeps screen awake.
     */
    fun enterImmersiveMode(activity: Activity?) {
        val window = activity?.window ?: return
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Restores normal system bars, cutout mode, and screen flags when exiting video playback.
     */
    fun exitImmersiveMode(activity: Activity?) {
        val window = activity?.window ?: return
        try {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.show(WindowInsetsCompat.Type.systemBars())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                window.attributes = lp
            }

            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Enters Android Picture-in-Picture (PiP) mode on compatible devices (Android 8.0+ / One UI).
     */
    fun enterPictureInPicture(activity: Activity?): Boolean {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            activity.enterPictureInPictureMode(params)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Checks if Do Not Disturb (Notification Policy) access is granted.
     */
    fun isDndPermissionGranted(context: Context): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.isNotificationPolicyAccessGranted == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Opens Android System Settings to grant DND access.
     */
    fun openDndSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Enables Do Not Disturb (Priority only) during playback if permission is granted.
     * Records the previous filter state to allow perfect restoration upon playback completion.
     */
    fun enableDndIfAllowed(context: Context, repository: KrasRepository) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.isNotificationPolicyAccessGranted) {
                val currentFilter = nm.currentInterruptionFilter
                repository.saveInterruptionFilter(currentFilter)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Restores the user's previous Do Not Disturb filter state.
     * Safe to call repeatedly and cleans up persisted filter state.
     */
    fun restoreDnd(context: Context, repository: KrasRepository) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.isNotificationPolicyAccessGranted) {
                val savedFilter = repository.getSavedInterruptionFilter()
                if (savedFilter != null && savedFilter != -1) {
                    nm.setInterruptionFilter(savedFilter)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                repository.saveInterruptionFilter(null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
