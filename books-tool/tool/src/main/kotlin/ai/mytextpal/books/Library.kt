package ai.mytextpal.books

import android.media.MediaMetadataRetriever
import android.util.Log
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A book is either a single audio file in the Audiobooks folder, or a subfolder whose
 * audio files (sorted naturally, so "2.mp3" < "10.mp3") are its parts.
 */
data class Book(
    /** Path relative to the Audiobooks root; stable across rescans and reinstalls. */
    val id: String,
    val title: String,
    val files: List<File>,
)

/**
 * The phone's audiobooks, read straight off shared storage.
 *
 * A tool can't query MediaStore (android.content is a blocked import), but it can hold
 * READ_MEDIA_AUDIO and walk the folder with java.io — the same thing the Amp music tool
 * does. Durations come from [MediaMetadataRetriever], which takes a plain path.
 */
object Library {
    private const val TAG = "BooksLibrary"

    const val FOLDER = "Audiobooks"
    const val PERMISSION = "android.permission.READ_MEDIA_AUDIO"

    /** Both entries are the same directory; /sdcard is a symlink. Deduplicated below. */
    private val ROOTS = listOf("/storage/emulated/0/$FOLDER", "/sdcard/$FOLDER")

    private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "mka")

    private fun isAudio(f: File) = f.isFile && f.extension.lowercase() in AUDIO_EXT

    private fun root(): File? = ROOTS.map(::File).firstOrNull { it.isDirectory }

    /** What stands between the tool and the folder, if anything. */
    enum class Access { GRANTED, NOT_GRANTED, BLOCKED_BY_LIGHTOS, UNKNOWN }

    /**
     * Without the permission, listing the folder returns an empty array rather than an
     * error, so a full folder and a forbidden one look alike from here. LightOS is the one
     * that knows. Off LightOS (plain emulator) there is no answer, hence [Access.UNKNOWN].
     */
    suspend fun access(): Access {
        val result = checkPermission(PERMISSION).asKotlinResult
            .map { it.permissionResult }
            .getOrNull()
        Log.i(TAG, "Audiobooks access: lightos=${result ?: "no answer"}")
        return when (result) {
            LightServiceMethod.GetPermission.Result.Granted -> Access.GRANTED
            LightServiceMethod.GetPermission.Result.Denied -> Access.NOT_GRANTED
            LightServiceMethod.GetPermission.Result.BlockedByServer -> Access.BLOCKED_BY_LIGHTOS
            else -> Access.UNKNOWN
        }
    }

    suspend fun scan(): List<Book> = withContext(Dispatchers.IO) {
        val root = root() ?: return@withContext emptyList()
        val entries = root.listFiles()?.filter { !it.name.startsWith(".") } ?: return@withContext emptyList()
        val books = mutableListOf<Book>()
        for (entry in entries) {
            if (entry.isDirectory) {
                val parts = entry.walkTopDown()
                    .maxDepth(4)
                    .filter { isAudio(it) }
                    .sortedWith { a, b -> naturalCompare(a.absolutePath.lowercase(), b.absolutePath.lowercase()) }
                    .toList()
                if (parts.isNotEmpty()) books += Book(entry.name, entry.name, parts)
            } else if (isAudio(entry)) {
                books += Book(entry.name, entry.nameWithoutExtension, listOf(entry))
            }
        }
        books.sortedWith { a, b -> naturalCompare(a.title.lowercase(), b.title.lowercase()) }
    }

    /** Cache key for a file's duration: a replaced file re-extracts. */
    fun durationKey(file: File): String = "${file.absolutePath}:${file.length()}"

    /** Slow (opens the file), so callers cache it through [PositionStore]. */
    fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            Log.w(TAG, "No duration for ${file.name}: $e")
            0L
        } finally {
            retriever.release()
        }
    }
}
