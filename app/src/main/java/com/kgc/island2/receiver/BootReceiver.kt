package com.kgc.island2.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.service.IslandNotificationService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                IslandStateEngine.loadPersistedPreferences(context)
                NotificationListenerService.requestRebind(
                    ComponentName(context, IslandNotificationService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}