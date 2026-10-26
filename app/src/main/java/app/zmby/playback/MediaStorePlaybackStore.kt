package app.zmby.playback

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.zmby.log.ZLog
import java.io.File
import java.io.IOException

/**
 * Local-STRM files in the public Download/zmby-playback/ directory, written through MediaStore.
 * On the Z9X 8K (Android 11, 2026-10-24) a targetSdk 30 app without storage permission cannot
 * create that directory with the file API, while MediaStore lets it add (and later delete) its own
 * downloads with no permission. The box has no DocumentsUI, so a SAF tree grant is not possible.
 * Files stay pending (invisible to the player) until fully written.
 */
class MediaStorePlaybackStore(private val resolver: ContentResolver, private val now: () -> Long = System::currentTimeMillis) : PlaybackFileStore {
    // Construction also happens on Android 9, where browsing/direct playback remain available.
    // Resolve only when a local STRM operation is requested, with a clear error on older devices.
    private val collection: Uri
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw IOException("本地 STRM 外挂字幕需要 Android 10 或更新版本")
            }
            return MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DIR/"

    override val location: String get() = relativePath

    override fun publish(name: String, bytes: ByteArray): File {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            // Octet-stream keeps the extension as given (.strm/.srt/.ass are not all known types).
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
        try {
            (resolver.openOutputStream(uri, "w") ?: throw IOException("cannot open $name")).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: throw IOException("no path for $name")
            // The SIZE column is filled in later by the scanner; the descriptor knows now.
            val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            val file = File(path)
            // The provider renames on a clash ("x (1).srt"); then the player would not pair the files.
            if (file.name != name || size != bytes.size.toLong()) throw IOException("stored as ${file.name}, $size bytes")
            return file
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    override fun delete(file: File) {
        val n = resolver.delete(collection, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(file.absolutePath))
        if (n == 0) ZLog.w(TAG, "nothing deleted for ${file.name}")
    }

    /** Only this app's own entries are visible without storage permission, so this never touches anything else. */
    override fun sweep(maxAgeMs: Long, keep: Set<String>): Int {
        val cutoffSeconds = (now() - maxAgeMs) / 1000
        val stale = mutableListOf<Uri>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DATE_ADDED} < ?",
            arrayOf(relativePath, cutoffSeconds.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) !in keep) stale += ContentUris.withAppendedId(collection, c.getLong(0))
            }
        }
        return stale.count { runCatching { resolver.delete(it, null, null) > 0 }.getOrDefault(false) }
    }

    private companion object {
        const val TAG = "Strm"
        const val DIR = "zmby-playback"
    }
}
