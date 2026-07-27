package ai.mytextpal.audiobook

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File

/**
 * A book is either a single audio file in /sdcard/Audiobooks, or a subfolder whose audio
 * files (sorted naturally, so "2.mp3" < "10.mp3") are its parts.
 */
data class Book(
    val id: String, // path relative to the Audiobooks root; stable across rescans
    val title: String,
    val files: List<File>,
)

object Library {
    val root: File get() = File(Environment.getExternalStorageDirectory(), "Audiobooks")

    private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "mka")

    private fun isAudio(f: File) = f.isFile && f.extension.lowercase() in AUDIO_EXT

    fun scan(): List<Book> {
        val entries = root.listFiles()?.filter { !it.name.startsWith(".") } ?: return emptyList()
        val books = mutableListOf<Book>()
        for (entry in entries) {
            if (entry.isDirectory) {
                val parts = entry.walkTopDown()
                    .filter { isAudio(it) }
                    .sortedWith { a, b -> naturalCompare(a.absolutePath.lowercase(), b.absolutePath.lowercase()) }
                    .toList()
                if (parts.isNotEmpty()) books += Book(entry.name, entry.name, parts)
            } else if (isAudio(entry)) {
                books += Book(entry.name, entry.nameWithoutExtension, listOf(entry))
            }
        }
        return books.sortedWith { a, b -> naturalCompare(a.title.lowercase(), b.title.lowercase()) }
    }

    /** Compares strings treating digit runs as numbers, so "part 2" sorts before "part 10". */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var x = i
                while (x < a.length && a[x].isDigit()) x++
                var y = j
                while (y < b.length && b[y].isDigit()) y++
                val na = a.substring(i, x).trimStart('0')
                val nb = b.substring(j, y).trimStart('0')
                val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (c != 0) return c
                i = x
                j = y
            } else {
                val c = a[i].compareTo(b[j])
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/**
 * Caches per-file durations (metadata extraction is slow) keyed by path + size, so a
 * replaced file re-extracts. Used for the library's progress % / time-remaining lines.
 */
class DurationCache(context: Context) {
    private val prefs = context.getSharedPreferences("durations", Context.MODE_PRIVATE)

    fun durationMs(file: File): Long {
        val key = "${file.absolutePath}:${file.length()}"
        val cached = prefs.getLong(key, -1L)
        if (cached >= 0) return cached
        val ms = try {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(file.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
        prefs.edit().putLong(key, ms).apply()
        return ms
    }

    fun fileDurations(book: Book): List<Long> = book.files.map { durationMs(it) }
}
