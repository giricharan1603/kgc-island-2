package com.kgc.island2.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kgc.island2.MainActivity

/**
 * Prevents Permission Revocation on Reboot / OTA Updates:
 * Verifies Accessibility & Notification Listener status immediately on boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
            val hasNotifPermission = enabledListeners.contains(context.packageName)

            val enabledAccessibility = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val hasAccessibility = enabledAccessibility.contains(context.packageName)

            if (!hasNotifPermission || !hasAccessibility) {
                showRestoreServiceNotification(context)
            }
        }
    }

    private fun showRestoreServiceNotification(context: Context) {
        val channelId = "kgc_island_restore"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Island Engine Recovery",
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kgc Island 2 Needs Reactivation")
            .setContentText("Tap to restore Accessibility & Notification permissions after reboot.")
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(1001, notification)
    }
}