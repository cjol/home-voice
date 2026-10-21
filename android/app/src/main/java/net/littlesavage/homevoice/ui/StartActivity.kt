package net.littlesavage.homevoice.ui

import android.app.Activity
import android.os.Bundle
import net.littlesavage.homevoice.service.VoiceService

/** Invisible trampoline: being a visible activity lets us start the mic service (boot, notification tap). */
class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceService.start(this)
        finish()
    }
}
