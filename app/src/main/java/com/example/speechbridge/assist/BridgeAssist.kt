package com.example.speechbridge.assist

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Exists only so the app can be chosen as the system "Digital assistant app", which is how Android
 * lets an app become the default speech recognizer (the recognitionService attribute in
 * res/xml/voice_interaction_service.xml). It never does any assistant work.
 */
class BridgeVoiceInteractionService : VoiceInteractionService()

class BridgeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = BridgeSession(android.content.ContextWrapper(this))
}

class BridgeSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        hide() // No assistant UI.
    }
}
