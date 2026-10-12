package com.bearinmind.equalizer314.audio

import java.util.concurrent.ConcurrentHashMap

/** Sessions apps announced with OPEN_AUDIO_EFFECT_CONTROL_SESSION (session id → package): virtual surround's only session source without DUMP (issue #128). */
object AnnouncedSessions {

    private val sessions = ConcurrentHashMap<Int, String>()

    /** Fired on any change, on the caller's thread. */
    @Volatile var onChange: (() -> Unit)? = null

    fun open(sessionId: Int, pkg: String) {
        if (sessionId <= 0 || pkg.isEmpty()) return
        if (sessions.put(sessionId, pkg) != pkg) onChange?.invoke()
    }

    fun close(sessionId: Int) {
        if (sessions.remove(sessionId) != null) onChange?.invoke()
    }

    fun snapshot(): Map<Int, String> = HashMap(sessions)
}
