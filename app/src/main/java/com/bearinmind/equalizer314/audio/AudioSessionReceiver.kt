package com.bearinmind.equalizer314.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log

/**
 * Manifest receiver for the standard audio-effect control session broadcasts, sent by opt-in music
 * apps on session start/stop:
 *   - `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` / `..._CLOSE_...`
 * Both carry `EXTRA_AUDIO_SESSION` (int, session ID) and `EXTRA_PACKAGE_NAME` (String, sender app).
 * Wavelet uses this same mechanism (`a6/n0.java` constructs a `DynamicsProcessing` at
 * `Integer.MAX_VALUE` priority). We forward both extras to [EqService] via a custom action; it hands
 * them to [SessionEffectManager].
 */
class AudioSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(
            AudioEffect.EXTRA_AUDIO_SESSION,
            AudioEffect.ERROR_BAD_VALUE,
        )
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()

        if (sessionId == AudioEffect.ERROR_BAD_VALUE) {
            Log.w(TAG, "Missing EXTRA_AUDIO_SESSION on ${intent.action} from $packageName")
            return
        }

        Log.d(TAG, "${intent.action} session=$sessionId package=$packageName")

        val forwardAction = when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> EqService.ACTION_ATTACH_SESSION
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> EqService.ACTION_DETACH_SESSION
            else -> return
        }
        if (forwardAction == EqService.ACTION_ATTACH_SESSION) AnnouncedSessions.open(sessionId, packageName)
        else AnnouncedSessions.close(sessionId)

        val serviceIntent = Intent(context, EqService::class.java).apply {
            action = forwardAction
            putExtra(EqService.EXTRA_SESSION_ID, sessionId)
            putExtra(EqService.EXTRA_PACKAGE_NAME, packageName)
        }

        // startForegroundService if not already running; service promotes itself via startForeground
        // on first onStartCommand to meet the 5-second FGS deadline.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not forward session intent to EqService", t)
        }
    }

    companion object {
        private const val TAG = "AudioSessionReceiver"
    }
}
