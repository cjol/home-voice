package net.littlesavage.homevoice.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import net.littlesavage.homevoice.Settings
import net.littlesavage.homevoice.protocol.UiMessage
import net.littlesavage.homevoice.service.VoiceService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { SettingsScreen(settings) } } }
    }

    @Composable
    private fun SettingsScreen(settings: Settings) {
        var url by remember { mutableStateOf(settings.serverUrl) }
        var token by remember { mutableStateOf(settings.token) }
        val status by VoiceService.status.collectAsState()
        val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // A first install must wait for permission before starting a microphone service.
            if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                VoiceService.start(this@MainActivity)
            } else {
                settings.enabled = false
                android.widget.Toast.makeText(this@MainActivity, "Microphone permission is needed to listen", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        Column(Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Home Voice", style = MaterialTheme.typography.headlineLarge)
            Text("Device id: ${settings.deviceId}")
            OutlinedTextField(url, { url = it }, label = { Text("Agent URL (https://home-voice-agent.<sub>.workers.dev)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                token, { token = it }, label = { Text("Device token") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    settings.serverUrl = url; settings.token = token; settings.enabled = true
                    VoiceService.stop(this@MainActivity)
                    perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                }) { Text("Save & start") }
                OutlinedButton(onClick = { settings.enabled = false; VoiceService.stop(this@MainActivity) }) { Text("Stop") }
            }
            var threshold by remember { mutableFloatStateOf(settings.wakeThreshold) }
            Text("Wake word threshold: ${"%.2f".format(threshold)} (higher wakes less often; applies on Save & start)")
            Slider(
                value = threshold, onValueChange = { threshold = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { settings.wakeThreshold = threshold },
                valueRange = 0.3f..0.95f,
            )
            HorizontalDivider()
            Text("Permissions for background use", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }) { Text(if (android.provider.Settings.canDrawOverlays(this@MainActivity)) "Display over other apps: granted" else "Grant 'Display over other apps'") }
            val pm = getSystemService(PowerManager::class.java)
            OutlinedButton(onClick = {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }) { Text(if (pm.isIgnoringBatteryOptimizations(packageName)) "Battery optimisation: off" else "Disable battery optimisation") }
            OutlinedButton(onClick = {
                UiBus.showFullscreen(UiMessage.Card(id = "test", title = "Test card", body = "Overlay rendering works.", ttlMs = 5000))
                OverlayActivity.bringToFront(this@MainActivity, "Test")
            }) { Text("Test overlay") }
            var typed by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(typed, { typed = it }, label = { Text("Type a request (skips STT)") }, modifier = Modifier.weight(1f))
                Button(onClick = { VoiceService.sendText(this@MainActivity, typed); typed = "" }) { Text("Send") }
            }
            HorizontalDivider()
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text("Connected: ${status.connected}   Mode: ${status.mode}   Agent: ${status.agent}")
            Text("Wake score: ${"%.2f".format(status.wakeScore)}   Last wake: ${"%.2f".format(status.lastWakeScore)}")
            Text("You: ${status.lastTranscript}")
            Text("Assistant: ${status.lastReply}")
        }
    }
}
