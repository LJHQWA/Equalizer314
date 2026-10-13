package com.bearinmind.equalizer314.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class HrirLoaderTest {

    /** Float32 (format 3) or PCM16 (format 1) WAV with [channels], interleaved. */
    private fun wav(channels: List<FloatArray>, rate: Int, float: Boolean = true, extensible: Boolean = false): ByteArray {
        val n = channels[0].size
        val bytesPer = if (float) 4 else 2
        val data = ByteBuffer.allocate(n * channels.size * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until n) for (c in channels) {
            if (float) data.putFloat(c[f]) else data.putShort((c[f] * 32767f).toInt().toShort())
        }
        val fmtSize = if (extensible) 40 else 16
        val fmt = ByteBuffer.allocate(fmtSize).order(ByteOrder.LITTLE_ENDIAN)
        fmt.putShort((if (extensible) 0xFFFE else if (float) 3 else 1).toShort())
        fmt.putShort(channels.size.toShort())
        fmt.putInt(rate)
        fmt.putInt(rate * channels.size * bytesPer)
        fmt.putShort((channels.size * bytesPer).toShort())
        fmt.putShort((bytesPer * 8).toShort())
        if (extensible) {
            fmt.putShort(22); fmt.putShort((bytesPer * 8).toShort()); fmt.putInt(0)
            fmt.putShort((if (float) 3 else 1).toShort()); fmt.put(ByteArray(14))
        }
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        out.write("RIFF".toByteArray()); out.write(le32(4 + 8 + fmtSize + 8 + data.capacity()))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le32(fmtSize)); out.write(fmt.array())
        out.write("data".toByteArray()); out.write(le32(data.capacity())); out.write(data.array())
        return out.toByteArray()
    }

    /** Channel c = a single tap of height (c + 1) at sample 10 + c, so every path is identifiable. */
    private fun taggedChannels(count: Int, len: Int = 256) = List(count) { c -> FloatArray(len).also { it[10 + c] = (c + 1) / 100f } }

    private fun tagOf(ir: FloatArray): Int {
        val i = ir.indices.maxByOrNull { abs(ir[it]) }!!
        return i - 10
    }

    @Test
    fun hesuvi14MapsFrontPairs() {
        val s = HrirLoader.load(wav(taggedChannels(14), 48000), 48000)
        assertEquals(0, tagOf(s.ll))
        assertEquals(1, tagOf(s.lr!!))
        assertEquals(8, tagOf(s.rl!!))
        assertEquals(7, tagOf(s.rr))
        assertEquals(14, s.sourceChannels)
    }

    @Test
    fun hesuvi7IsMirrored() {
        val s = HrirLoader.load(wav(taggedChannels(7), 48000), 48000)
        assertEquals(0, tagOf(s.ll)); assertEquals(1, tagOf(s.lr!!))
        assertEquals(1, tagOf(s.rl!!)); assertEquals(0, tagOf(s.rr))
    }

    @Test
    fun trueStereo4AndPlainStereo() {
        val t = HrirLoader.load(wav(taggedChannels(4), 48000, extensible = true), 48000)
        assertEquals(listOf(0, 1, 2, 3), listOf(tagOf(t.ll), tagOf(t.lr!!), tagOf(t.rl!!), tagOf(t.rr)))
        val s = HrirLoader.load(wav(taggedChannels(2), 48000, float = false), 48000)
        assertEquals(0, tagOf(s.ll)); assertEquals(1, tagOf(s.rr))
        assertNull(s.lr); assertNull(s.rl)
    }

    @Test(expected = HrirLoader.UnsupportedHrirException::class)
    fun sixChannelsRejected() {
        HrirLoader.load(wav(taggedChannels(6), 48000), 48000)
    }

    @Test
    fun levelNeverExceedsFullScale() {
        // Loud, resonant paths: after loading, |LL|+|RL| and |LR|+|RR| peak at exactly 0 dB in 20 Hz-20 kHz.
        val len = 512
        val res = FloatArray(len) { i -> (3.0 * sin(2 * PI * 3000 * i / 48000.0) * Math.exp(-i / 60.0)).toFloat() }
        val chans = List(14) { c -> if (c in setOf(0, 1, 7, 8)) res.copyOf() else FloatArray(len) }
        val s = HrirLoader.load(wav(chans, 48000), 48000)
        var peak = 0.0
        for (k in 1..400) {
            val f = 20.0 * Math.pow(1000.0, k / 400.0)
            fun mag(x: FloatArray?): Double {
                x ?: return 0.0
                var re = 0.0; var im = 0.0
                for (i in x.indices) { re += x[i] * Math.cos(2 * PI * f * i / 48000); im -= x[i] * Math.sin(2 * PI * f * i / 48000) }
                return Math.hypot(re, im)
            }
            peak = maxOf(peak, mag(s.ll) + mag(s.rl), mag(s.lr) + mag(s.rr))
        }
        assertTrue("peak $peak", peak in 0.97..1.0005)
    }

    @Test
    fun resampleKeepsAToneAndItsLength() {
        val x = FloatArray(4410) { i -> sin(2 * PI * 1000 * i / 44100.0).toFloat() }
        val y = HrirLoader.resample(x, 44100, 48000)
        assertEquals(4800, y.size)
        // Away from the edges the output is the same 1 kHz sine at the new rate.
        for (n in 200 until 4600) {
            val expected = sin(2 * PI * 1000 * n / 48000.0)
            assertEquals("sample $n", expected, y[n].toDouble(), 0.01)
        }
    }
}
