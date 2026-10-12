package com.bearinmind.equalizer314.state

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bearinmind.equalizer314.dsp.HrirLoader
import com.bearinmind.equalizer314.dsp.HrirSet
import java.io.File

/** Imported HRIR / IRS files for virtual surround (issue #128), kept in filesDir/hrir under their own names. */
object HrirLibrary {

    private const val MAX_BYTES = 32L shl 20

    private fun dir(context: Context) = File(context.filesDir, "hrir").apply { mkdirs() }

    fun names(context: Context): List<String> =
        dir(context).listFiles()?.filter { it.isFile }?.map { it.name }?.sortedBy { it.lowercase() }.orEmpty()

    fun exists(context: Context, name: String?): Boolean = name != null && File(dir(context), name).isFile

    /** Load [name] resampled to [sampleRate]; throws when missing or unreadable. */
    fun load(context: Context, name: String, sampleRate: Int): HrirSet =
        HrirLoader.load(File(dir(context), name).readBytes(), sampleRate)

    /** Copy [uri] in under its display name once it parses (same name = replaced); returns the stored name. */
    fun import(context: Context, uri: Uri): String {
        val cr = context.contentResolver
        val raw = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "hrir.wav"
        val name = raw.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_")
            .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "hrir.wav"
        val bytes = cr.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw HrirLoader.UnsupportedHrirException("larger than 32 MB")
            }
            out.toByteArray()
        } ?: throw java.io.IOException("can't open file")
        val set = HrirLoader.load(bytes, 48000)
        File(dir(context), name).writeBytes(bytes)
        EqPreferencesManager(context).saveHrirInfo(name, describe(set))
        return name
    }

    fun delete(context: Context, name: String) {
        File(dir(context), name).delete()
        val prefs = EqPreferencesManager(context)
        prefs.saveHrirInfo(name, null)
        if (prefs.getVirtualSurroundHrir() == name) prefs.saveVirtualSurroundHrir(null)
    }

    /** "14 ch · 48 kHz · 107 ms" from the file's own channel count and rate. */
    private fun describe(set: HrirSet): String {
        val khz = set.sourceRate / 1000.0
        val rate = if (khz % 1.0 == 0.0) "${khz.toInt()}" else "%.1f".format(java.util.Locale.ROOT, khz)
        return "${set.sourceChannels} ch · $rate kHz · ${set.lengthMs} ms"
    }
}
