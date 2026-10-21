package net.littlesavage.homevoice.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import net.littlesavage.homevoice.Settings
import net.littlesavage.homevoice.service.VoiceService
import net.littlesavage.homevoice.ui.StartActivity

/**
 * Debug-only adb control (not in release builds). See android/scripts/emu.sh.
 *   adb shell am broadcast -n net.littlesavage.homevoice/.debug.DebugReceiver -a net.littlesavage.homevoice.debug.CONFIG --es serverUrl https://... --es token ...
 *   ... -a net.littlesavage.homevoice.debug.SAY    --es text "what time is it"   (text turn, skips STT)
 *   ... -a net.littlesavage.homevoice.debug.INJECT --es path /data/data/net.littlesavage.homevoice/files/x.raw
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("DebugReceiver", "${intent.action} ${intent.extras?.keySet()}")
        when (intent.action) {
            "net.littlesavage.homevoice.debug.CONFIG" -> {
                val s = Settings(context)
                intent.getStringExtra("serverUrl")?.let { s.serverUrl = it }
                intent.getStringExtra("token")?.let { s.token = it }
                s.enabled = true
                VoiceService.stop(context)
                // A mic service must be started from the foreground: go through the invisible StartActivity.
                context.startActivity(Intent(context, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "net.littlesavage.homevoice.debug.SAY" -> intent.getStringExtra("text")?.let { VoiceService.sendText(context, it) }
            "net.littlesavage.homevoice.debug.INJECT" -> intent.getStringExtra("path")?.let { VoiceService.injectPcm(context, it) }
        }
    }
}
