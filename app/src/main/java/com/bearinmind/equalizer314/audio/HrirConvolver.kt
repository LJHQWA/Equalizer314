package com.bearinmind.equalizer314.audio

import com.bearinmind.equalizer314.dsp.HrirSet

/** JNI side of libeq314hrir (src/main/cpp: PipeWire's convolver + pffft). */
internal object HrirNative {
    /** False when the library can't load; virtual surround then stays unavailable. */
    val loaded: Boolean by lazy {
        try { System.loadLibrary("eq314hrir"); true } catch (_: Throwable) { false }
    }

    @JvmStatic external fun create(ll: FloatArray, lr: FloatArray?, rl: FloatArray?, rr: FloatArray, maxFrames: Int, headBlock: Int): Long
    @JvmStatic external fun process(handle: Long, buf: FloatArray, frames: Int)
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun destroy(handle: Long)
}

/** Stereo virtual-speaker convolver over an [HrirSet]: [process] works in place on interleaved float frames. Owned by one audio thread. */
class HrirConvolver private constructor(private var handle: Long) {

    fun process(buf: FloatArray, frames: Int) {
        if (handle != 0L) HrirNative.process(handle, buf, frames)
    }

    fun reset() {
        if (handle != 0L) HrirNative.reset(handle)
    }

    fun release() {
        if (handle != 0L) HrirNative.destroy(handle)
        handle = 0L
    }

    companion object {
        val available: Boolean get() = HrirNative.loaded

        /** [maxFrames] = the largest block passed to [process]; it also sets the convolver's first partition (power of two, 64–4096). */
        fun create(set: HrirSet, maxFrames: Int): HrirConvolver? {
            if (!HrirNative.loaded || maxFrames <= 0) return null
            val head = Integer.highestOneBit(maxFrames.coerceIn(64, 4096))
            val h = HrirNative.create(set.ll, set.lr, set.rl, set.rr, maxFrames, head)
            return if (h != 0L) HrirConvolver(h) else null
        }
    }
}
