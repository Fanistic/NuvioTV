package com.nuvio.tv.livetv.record

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * The places recordings can be saved: this device, and each USB drive (or SD card) that's
 * plugged in. Each one uses the app's own folder on that drive, which needs no permission and
 * no folder picker. A drive is remembered by its id, so it's found again when plugged back in.
 */
object RecordingStorage {
    data class Choice(
        /** "" for this device, else the drive's id. */
        val id: String,
        val label: String,
        /** The recordings folder on it. */
        val dir: File,
        val freeBytes: Long,
        val totalBytes: Long
    ) {
        val isDevice: Boolean get() = id.isEmpty()
    }

    /** Every place recordings can go right now (this device first). */
    fun choices(context: Context): List<Choice> {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val dirs = runCatching { context.getExternalFilesDirs(null).toList() }.getOrDefault(emptyList())
        val out = ArrayList<Choice>()
        dirs.forEachIndexed { i, base ->
            if (base == null) return@forEachIndexed
            val mounted = runCatching { Environment.getExternalStorageState(base) == Environment.MEDIA_MOUNTED }.getOrDefault(i == 0)
            if (!mounted) return@forEachIndexed
            val vol = runCatching { sm?.getStorageVolume(base) }.getOrNull()
            val primary = i == 0 || vol?.isPrimary == true
            val id = if (primary) "" else (vol?.uuid ?: base.absolutePath)
            val label = if (primary) "This Device"
                else runCatching { vol?.getDescription(context) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "USB Drive"
            out += Choice(
                id = id,
                label = label,
                dir = File(base, "recordings"),
                freeBytes = runCatching { base.usableSpace }.getOrDefault(0L),
                totalBytes = runCatching { base.totalSpace }.getOrDefault(0L)
            )
        }
        if (out.none { it.isDevice }) {
            val f = File(context.filesDir, "recordings")
            out.add(0, Choice("", "This Device", f, runCatching { context.filesDir.usableSpace }.getOrDefault(0L), runCatching { context.filesDir.totalSpace }.getOrDefault(0L)))
        }
        return out.distinctBy { it.id }
    }

    /** The place for [id], or this device if that drive isn't plugged in. */
    fun resolve(context: Context, id: String): Choice {
        val all = choices(context)
        return all.firstOrNull { it.id == id } ?: all.first { it.isDevice }
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} KB"
    }
}

/**
 * Where you'd got to in each recording, so it carries on from there next time (saved by Nuvio's
 * player while a recording plays; kept out of Continue Watching and your watch history).
 */
object RecordingResume {
    private const val PREFS = "livetv_recording_resume"
    const val CONTENT_TYPE = "recording"
    private const val PREFIX = "rec:"

    fun contentIdFor(recordingId: String) = PREFIX + recordingId
    fun isRecording(contentType: String?) = contentType.equals(CONTENT_TYPE, ignoreCase = true)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, contentId: String?, positionMs: Long, durationMs: Long) {
        val id = contentId?.removePrefix(PREFIX) ?: return
        // Near the end counts as watched: start from the beginning next time.
        val done = durationMs > 0 && positionMs >= durationMs - 60_000L
        runCatching {
            prefs(context).edit().apply {
                if (done) remove(id) else putString(id, "$positionMs,$durationMs")
            }.apply()
        }
    }

    /** Position and length, or null if not started (or finished). */
    fun load(context: Context, contentId: String?): Pair<Long, Long>? {
        val id = contentId?.removePrefix(PREFIX) ?: return null
        val v = runCatching { prefs(context).getString(id, null) }.getOrNull() ?: return null
        val p = v.split(',')
        val pos = p.getOrNull(0)?.toLongOrNull() ?: return null
        return pos to (p.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun clear(context: Context, recordingId: String) {
        runCatching { prefs(context).edit().remove(recordingId).apply() }
    }
}
