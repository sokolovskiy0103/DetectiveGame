package com.sokolovskiy.party.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sokolovskiy.party.MainActivity

/**
 * MY_PACKAGE_REPLACED after a successful update (the old process is killed by the install, so a notification
 * invites the user back into the new version).
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> notifyUpdated(context)
        }
    }

    private fun notifyUpdated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Оновлення", NotificationManager.IMPORTANCE_DEFAULT))
        val name = runCatching { Updater.installedName(context) }.getOrDefault("")
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(com.sokolovskiy.party.R.drawable.ic_stat_party)
            .setContentTitle("${com.sokolovskiy.party.core.PartyApp.config.name} оновлено до v$name")
            .setContentText("Натисни, щоб відкрити")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    companion object {
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 7

        fun clearNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
