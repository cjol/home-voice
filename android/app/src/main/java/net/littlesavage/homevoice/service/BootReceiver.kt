package net.littlesavage.homevoice.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import net.littlesavage.homevoice.R
import net.littlesavage.homevoice.Settings
import net.littlesavage.homevoice.ui.StartActivity

/**
 * Android 14+ forbids starting a microphone foreground service straight from
 * BOOT_COMPLETED. With "Display over other apps" granted we may launch an
 * (invisible) activity from the background, which then starts the service.
 * Without it, fall back to a tap-to-start notification.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings(context).enabled) return
        val start = Intent(context, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.provider.Settings.canDrawOverlays(context)) {
            context.startActivity(start)
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(VoiceService.CHANNEL_ALERTS, "Assistant screens", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(
            4,
            NotificationCompat.Builder(context, VoiceService.CHANNEL_ALERTS).setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Home Voice is off").setContentText("Tap to start listening")
                .setContentIntent(PendingIntent.getActivity(context, 4, start, PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build(),
        )
    }
}
