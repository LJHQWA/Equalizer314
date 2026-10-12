package com.bearinmind.equalizer314.dsp

import com.bearinmind.equalizer314.audio.SimpleFFT
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Virtual-speaker impulses at [sampleRate]: [ll]/[lr] = left speaker to left/right ear, [rl]/[rr] = right speaker; the crossfeed pair is null for plain stereo IRs. */
class HrirSet(
    val ll: FloatArray,
    val lr: FloatArray?,
    val rl: FloatArray?,
    val rr: FloatArray,
    val sampleRate: Int,
    val sourceChannels: Int,
    val sourceRate: Int,
) {
    val lengthMs: Int get() = (maxOf(ll.size, rr.size, lr?.size ?: 0, rl?.size ?: 0) * 1000L / sampleRate).toInt()
}

/** Reads HeSuVi (14 / 7 ch), IRS true-stereo (4 ch: LL LR RL RR), stereo and mono impulse WAVs (.wav / .irs). */
object HrirLoader {

    class UnsupportedHrirException(message: String) : Exception(message)

    private const val MAX_SECONDS = 4

    /** Parse [bytes], map the channels to speaker→ear paths, resample to [targetRate] and level-normalise (see [normalize]). */
    fun load(bytes: ByteArray, targetRate: Int): HrirSet {
        val wav = parseWav(bytes)
        val done = HashMap<Int, FloatArray>()
        val r = { ch: Int -> if (ch < 0) null else done.getOrPut(ch) { resample(wav.channel(ch), wav.rate, targetRate) } }
        val (ll, lr, rl, rr) = pathChannels(wav.channelCount).map(r)
        return normalize(HrirSet(ll!!, lr, rl, rr!!, targetRate, wav.channelCount, wav.rate))
    }

    /** File channel per path (LL, LR, RL, RR; -1 = none). HeSuVi per PipeWire's sink-virtual-surround-7.1-hesuvi.conf, 7 ch = HeSuVi's mirrored layout, 4 ch per JamesDSP. */
    private fun pathChannels(count: Int): List<Int> = when (count) {
        14 -> listOf(0, 1, 8, 7)
        7 -> listOf(0, 1, 1, 0)
        4 -> listOf(0, 1, 2, 3)
        2 -> listOf(0, -1, -1, 1)
        1 -> listOf(0, -1, -1, 0)
        else -> throw UnsupportedHrirException("$count channels")
    }

    /** Header facts plus a decoder for one channel at a time (only the 2–4 used channels are ever decoded). */
    private class Wav(val channelCount: Int, val rate: Int, val channel: (Int) -> FloatArray)

    private fun parseWav(b: ByteArray): Wav {
        fun tag(at: Int) = String(b, at, 4, Charsets.US_ASCII)
        if (b.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") throw UnsupportedHrirException("not a WAV file")
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var format = -1; var channels = 0; var rate = 0; var align = 0; var bits = 0
        var dataOff = -1; var dataLen = 0
        var pos = 12L
        while (pos + 8 <= b.size) {
            val at = pos.toInt()
            val size = bb.getInt(at + 4).toLong() and 0xFFFFFFFFL
            val body = at + 8
            when (tag(at)) {
                "fmt " -> if (size >= 16) {
                    format = bb.getShort(body).toInt() and 0xFFFF
                    channels = bb.getShort(body + 2).toInt() and 0xFFFF
                    rate = bb.getInt(body + 4)
                    align = bb.getShort(body + 12).toInt() and 0xFFFF
                    bits = bb.getShort(body + 14).toInt() and 0xFFFF
                    // WAVE_FORMAT_EXTENSIBLE: the real format is the SubFormat GUID's first two bytes.
                    if (format == 0xFFFE && size >= 26) format = bb.getShort(body + 24).toInt() and 0xFFFF
                }
                // Streamed writers leave the size at 0 / 0xFFFFFFFF: take what's there.
                "data" -> if (dataOff < 0) {
                    dataOff = body
                    dataLen = (if (size == 0L || size > b.size - body) (b.size - body).toLong() else size).toInt()
                }
            }
            pos = body + size + (size and 1L)
        }
        if (format < 0 || dataOff < 0) throw UnsupportedHrirException("no fmt/data chunk")
        if (channels <= 0 || rate <= 0 || align <= 0) throw UnsupportedHrirException("bad header")
        val bytesPer = bits / 8
        if (align < channels * bytesPer) throw UnsupportedHrirException("bad block size")
        val decode: (Int) -> Float = when {
            format == 1 && bits == 8 -> { o -> ((b[o].toInt() and 0xFF) - 128) / 128f }
            format == 1 && bits == 16 -> { o -> bb.getShort(o) / 32768f }
            format == 1 && bits == 24 -> { o ->
                ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() shl 16)) / 8388608f
            }
            format == 1 && bits == 32 -> { o -> (bb.getInt(o) / 2147483648.0).toFloat() }
            format == 3 && bits == 32 -> { o -> bb.getFloat(o) }
            format == 3 && bits == 64 -> { o -> bb.getDouble(o).toFloat() }
            else -> throw UnsupportedHrirException("format $format, $bits-bit")
        }
        val frames = minOf(dataLen / align, rate * MAX_SECONDS)
        if (frames <= 0) throw UnsupportedHrirException("empty")
        pathChannels(channels) // reject unsupported layouts before any decoding
        val cache = HashMap<Int, FloatArray>()
        return Wav(channels, rate) { ch ->
            cache.getOrPut(ch) { FloatArray(frames) { f -> decode(dataOff + f * align + ch * bytesPer) } }
        }
    }

    // ---- Resampling: Hann-windowed sinc, tabulated once per call (IRs can run to seconds) ----

    private const val ZERO_CROSSINGS = 24
    private const val TABLE_STEPS = 512

    internal fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = to.toDouble() / from
        val cutoff = minOf(1.0, ratio) * 0.95
        val reach = ZERO_CROSSINGS / cutoff // kernel half-width, in input samples
        val table = DoubleArray((reach * TABLE_STEPS).toInt() + 2) { i ->
            val d = i.toDouble() / TABLE_STEPS
            val a = PI * d * cutoff
            val sinc = if (a == 0.0) 1.0 else sin(a) / a
            if (d >= reach) 0.0 else cutoff * sinc * 0.5 * (1 + cos(PI * d / reach))
        }
        val y = FloatArray(ceil(x.size * ratio).toInt())
        for (n in y.indices) {
            val t = n / ratio
            val k0 = maxOf(0, ceil(t - reach).toInt())
            val k1 = minOf(x.size - 1, floor(t + reach).toInt())
            var acc = 0.0
            for (k in k0..k1) {
                val p = abs(t - k) * TABLE_STEPS
                val i = p.toInt()
                if (i + 1 >= table.size) continue
                val w = table[i] + (table[i + 1] - table[i]) * (p - i)
                acc += x[k] * w
            }
            y[n] = acc.toFloat()
        }
        return y
    }

    // ---- Level: scale so no ear can exceed full scale at any audible frequency ----

    /** Peak over 20 Hz–20 kHz of |LL|+|RL| (left ear) and |LR|+|RR| (right ear), scaled to 0 dB: a full-scale stereo input can't clip, whatever its inter-channel phase. */
    private fun normalize(s: HrirSet): HrirSet {
        val irs = listOfNotNull(s.ll, s.lr, s.rl, s.rr)
        // 4x zero-padding: bins close enough that a sharp resonance can't hide between them.
        var n = 8192
        while (n < 4 * irs.maxOf { it.size }) n *= 2
        fun spectrum(x: FloatArray?): FloatArray? {
            x ?: return null
            val re = FloatArray(n); val im = FloatArray(n)
            x.copyInto(re)
            SimpleFFT.fft(re, im)
            return FloatArray(n / 2 + 1) { k -> hypot(re[k], im[k]) }
        }
        val ll = spectrum(s.ll)!!; val lr = spectrum(s.lr); val rl = spectrum(s.rl); val rr = spectrum(s.rr)!!
        val lo = maxOf(1, (20.0 * n / s.sampleRate).toInt())
        val hi = minOf(n / 2, (20000.0 * n / s.sampleRate).toInt())
        var peak = 0f
        for (k in lo..hi) {
            val left = ll[k] + (rl?.get(k) ?: 0f)
            val right = rr[k] + (lr?.get(k) ?: 0f)
            peak = maxOf(peak, left, right)
        }
        if (peak <= 0f) throw UnsupportedHrirException("silent")
        val g = 1f / peak
        fun scaled(x: FloatArray?) = x?.let { a -> FloatArray(a.size) { a[it] * g } }
        return HrirSet(scaled(s.ll)!!, scaled(s.lr), scaled(s.rl), scaled(s.rr)!!, s.sampleRate, s.sourceChannels, s.sourceRate)
    }
}
