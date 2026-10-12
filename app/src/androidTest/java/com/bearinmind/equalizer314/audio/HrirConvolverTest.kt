package com.bearinmind.equalizer314.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bearinmind.equalizer314.dsp.HrirSet
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/** PipeWire's convolver through the JNI bridge vs. a plain direct convolution, fed in awkward chunk sizes. */
@RunWith(AndroidJUnit4::class)
class HrirConvolverTest {

    private val rnd = Random(128)

    private fun ir(len: Int) = FloatArray(len) { (rnd.nextFloat() - 0.5f) * exp(-it / (len / 5f)) }
    private fun noise(n: Int) = FloatArray(n) { rnd.nextFloat() - 0.5f }

    private fun direct(x: FloatArray, h: FloatArray?): DoubleArray {
        val y = DoubleArray(x.size)
        h ?: return y
        for (n in x.indices) {
            var acc = 0.0
            val kMax = minOf(n, h.size - 1)
            for (k in 0..kMax) acc += h[k].toDouble() * x[n - k]
            y[n] = acc
        }
        return y
    }

    /** Runs [frames] of stereo noise through [set] in chunks of [sizes] (repeating); returns max error relative to the signal peak. */
    private fun relativeError(set: HrirSet, frames: Int, sizes: IntArray): Double {
        val conv = HrirConvolver.create(set, 1024)
        assertNotNull("native engine", conv)
        val xl = noise(frames)
        val xr = noise(frames)
        val buf = FloatArray(frames * 2) { i -> if (i % 2 == 0) xl[i / 2] else xr[i / 2] }
        var pos = 0
        var i = 0
        while (pos < frames) {
            val n = minOf(sizes[i++ % sizes.size], frames - pos)
            val chunk = buf.copyOfRange(pos * 2, (pos + n) * 2)
            conv!!.process(chunk, n)
            chunk.copyInto(buf, pos * 2)
            pos += n
        }
        conv!!.release()
        val crossfeed = set.lr != null && set.rl != null
        val ll = direct(xl, set.ll); val rr = direct(xr, set.rr)
        val lr = if (crossfeed) direct(xl, set.lr) else DoubleArray(frames)
        val rl = if (crossfeed) direct(xr, set.rl) else DoubleArray(frames)
        var maxErr = 0.0
        var peak = 0.0
        for (n in 0 until frames) {
            val el = ll[n] + rl[n]
            val er = lr[n] + rr[n]
            peak = maxOf(peak, abs(el), abs(er))
            maxErr = maxOf(maxErr, abs(buf[2 * n] - el), abs(buf[2 * n + 1] - er))
        }
        return maxErr / peak
    }

    @Test
    fun trueStereoMatchesDirectConvolution() {
        // 3000 taps: one head partition; chunks smaller, equal and larger than the 1024-frame block.
        val set = HrirSet(ir(3000), ir(3000), ir(3000), ir(3000), 48000, 14, 48000)
        val err = relativeError(set, 20000, intArrayOf(1024, 333, 1024, 7, 2000, 512, 1))
        assertTrue("relative error $err", err < 1e-4)
    }

    @Test
    fun longImpulseUsesTailPartitions() {
        // 20000 taps: past the 8192-sample head, so the 4096-block tail partition runs too (PipeWire's tail-timing bug showed here as ~4.5%).
        val set = HrirSet(ir(20000), ir(20000), ir(20000), ir(20000), 48000, 4, 48000)
        val err = relativeError(set, 24000, intArrayOf(1024, 333, 2000, 7, 4096))
        assertTrue("relative error $err", err < 1e-4)
    }

    @Test
    fun plainStereoKeepsSidesApart() {
        val set = HrirSet(ir(800), null, null, ir(800), 48000, 2, 48000)
        val err = relativeError(set, 8000, intArrayOf(1024, 100))
        assertTrue("relative error $err", err < 1e-4)
    }
}
