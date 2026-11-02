package com.strictfocus

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Posts a focus-completion notification using an inexact alarm to avoid special exact-alarm access. */
class FocusSessionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val channelId = "strictfocus_session_complete"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Focus session reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifies you when a focus session is scheduled to finish"
                }
            )
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Focus session complete")
            .setContentText("Your planned focus time has ended. Take a short break and celebrate your progress.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(
                context, 801, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(801, notification) }
    }

    companion object {
        fun schedule(context: Context, endMillis: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, FocusSessionAlarmReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, 801, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, pending)
        }
    }
}
