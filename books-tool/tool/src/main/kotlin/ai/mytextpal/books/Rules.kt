package ai.mytextpal.books

import kotlinx.serialization.Serializable

/**
 * Pure, context-free rules shared by the player and the library. Everything here is
 * unit-tested; the Android-facing classes are thin wrappers around these.
 */

/** Where a listener is in a book. Persisted as JSON, one record per book. */
@Serializable
data class SavedPosition(
    val fileIndex: Int = 0,
    val posMs: Long = 0L,
    val speed: Float = 1f,
    val updatedAt: Long = 0L,
    val finished: Boolean = false,
)

/** Where to start a book: index, position, speed. */
data class StartPoint(val fileIndex: Int, val posMs: Long, val speed: Float)

/**
 * Rewind a little on resume so the listener gets context back; more the longer they've
 * been away. Under a minute is a "pocket pause" and gets nothing.
 */
fun resumeRewindMs(awayMs: Long): Long = when {
    awayMs < 60_000L -> 0L
    awayMs < 3_600_000L -> 10_000L
    awayMs < 86_400_000L -> 20_000L
    else -> 30_000L
}

/** Starting point for a book given what was saved (if anything). A finished book restarts. */
fun startPoint(saved: SavedPosition?, fileCount: Int, nowMs: Long): StartPoint {
    if (saved == null || saved.finished || fileCount == 0) {
        return StartPoint(0, 0L, saved?.speed ?: 1f)
    }
    val rewind = resumeRewindMs(nowMs - saved.updatedAt)
    return StartPoint(
        fileIndex = saved.fileIndex.coerceIn(0, fileCount - 1),
        posMs = (saved.posMs - rewind).coerceAtLeast(0L),
        speed = saved.speed,
    )
}

/** Playback stopped on the last part within a couple of seconds of its end ⇒ the book is done. */
fun isBookFinished(fileIndex: Int, fileCount: Int, positionMs: Long, durationMs: Long): Boolean =
    fileCount > 0 && fileIndex == fileCount - 1 && durationMs > 0L && positionMs >= durationMs - 2_000L

val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Next speed in the cycle after [current] (tolerant of float drift). Wraps around. */
fun nextSpeed(current: Float): Float {
    val i = SPEEDS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
    return SPEEDS[(i + 1).mod(SPEEDS.size)]
}

fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0').trimEnd('.')) + "×"

val SLEEP_STEPS_MIN = listOf(15L, 30L, 45L, 60L)

/**
 * Tapping the sleep control walks off → 15 → 30 → 45 → 60 → off. A running timer bumps to
 * the next step above what's left, so a tap always adds time until it wraps to off.
 * Returns the new timer length in ms, or 0 for off.
 */
fun nextSleepMs(remainingMs: Long): Long {
    if (remainingMs <= 0L) return SLEEP_STEPS_MIN.first() * 60_000L
    // Whole minutes, rounded up: a timer set to 15 and tapped a second later reads 15, not 14.
    val remainingMin = (remainingMs + 59_999L) / 60_000L
    val next = SLEEP_STEPS_MIN.firstOrNull { it > remainingMin } ?: return 0L
    return next * 60_000L
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

/** "1:02:33" above an hour, "12:05" below. */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Progress through a multi-part book from per-part durations and the saved spot. */
data class BookProgress(val fraction: Float, val listenedMs: Long, val remainingMs: Long)

fun bookProgress(partDurationsMs: List<Long>, saved: SavedPosition?): BookProgress {
    val total = partDurationsMs.sum()
    val listened = if (saved == null || saved.finished) {
        if (saved?.finished == true) total else 0L
    } else {
        partDurationsMs.take(saved.fileIndex.coerceIn(0, partDurationsMs.size)).sum() + saved.posMs
    }.coerceIn(0L, total.coerceAtLeast(0L))
    val fraction = if (total > 0L) (listened.toFloat() / total).coerceIn(0f, 1f) else 0f
    return BookProgress(fraction, listened, (total - listened).coerceAtLeast(0L))
}

/** The one-line status under a book in the library. */
fun statusLine(progress: BookProgress, saved: SavedPosition?): String = when {
    saved?.finished == true -> "Finished"
    saved == null || progress.listenedMs <= 0L -> "Not started · " + formatDuration(progress.remainingMs)
    else -> "${(progress.fraction * 100).toInt()}% · ${formatDuration(progress.remainingMs)} left"
}
