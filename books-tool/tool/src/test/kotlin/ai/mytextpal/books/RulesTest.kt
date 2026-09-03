package ai.mytextpal.books

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RulesTest {

    @Test
    fun naturalSortPutsTwoBeforeTen() {
        val names = listOf("part 10.mp3", "part 2.mp3", "part 1.mp3", "part 02b.mp3")
        val sorted = names.sortedWith(::naturalCompare)
        assertEquals(listOf("part 1.mp3", "part 2.mp3", "part 02b.mp3", "part 10.mp3"), sorted)
    }

    @Test
    fun rewindGrowsWithTimeAway() {
        assertEquals(0L, resumeRewindMs(30_000L))
        assertEquals(10_000L, resumeRewindMs(5 * 60_000L))
        assertEquals(20_000L, resumeRewindMs(5 * 3_600_000L))
        assertEquals(30_000L, resumeRewindMs(3 * 86_400_000L))
    }

    @Test
    fun startPointResumesWithRewindAndClampsIndex() {
        val now = 1_000_000_000L
        val saved = SavedPosition(fileIndex = 7, posMs = 65_000L, speed = 1.5f, updatedAt = now - 2 * 3_600_000L)
        val start = startPoint(saved, fileCount = 3, nowMs = now)
        assertEquals(2, start.fileIndex)
        assertEquals(45_000L, start.posMs)
        assertEquals(1.5f, start.speed)
    }

    @Test
    fun startPointRestartsFinishedBookButKeepsSpeed() {
        val start = startPoint(SavedPosition(fileIndex = 2, posMs = 10L, speed = 2f, finished = true), 3, 0L)
        assertEquals(StartPoint(0, 0L, 2f), start)
        assertEquals(StartPoint(0, 0L, 1f), startPoint(null, 3, 0L))
    }

    @Test
    fun rewindNeverGoesNegative() {
        val saved = SavedPosition(fileIndex = 0, posMs = 4_000L, updatedAt = 0L)
        assertEquals(0L, startPoint(saved, 1, 86_400_000L * 2).posMs)
    }

    @Test
    fun finishedOnlyAtEndOfLastPart() {
        assertTrue(isBookFinished(fileIndex = 2, fileCount = 3, positionMs = 599_000L, durationMs = 600_000L))
        assertFalse(isBookFinished(fileIndex = 1, fileCount = 3, positionMs = 599_000L, durationMs = 600_000L))
        assertFalse(isBookFinished(fileIndex = 2, fileCount = 3, positionMs = 100_000L, durationMs = 600_000L))
        assertFalse(isBookFinished(fileIndex = 2, fileCount = 3, positionMs = 100_000L, durationMs = 0L))
    }

    @Test
    fun speedCyclesAndWraps() {
        assertEquals(1.25f, nextSpeed(1f))
        assertEquals(0.75f, nextSpeed(2f))
        assertEquals(1f, nextSpeed(0.7501f))
        assertEquals("1×", speedLabel(1f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("1.5×", speedLabel(1.5f))
    }

    @Test
    fun sleepStepsUpThenOff() {
        assertEquals(15 * 60_000L, nextSleepMs(0L))
        assertEquals(30 * 60_000L, nextSleepMs(14 * 60_000L + 30_000L))
        assertEquals(30 * 60_000L, nextSleepMs(15 * 60_000L))
        assertEquals(45 * 60_000L, nextSleepMs(30 * 60_000L))
        assertEquals(60 * 60_000L, nextSleepMs(59 * 60_000L))
        assertEquals(0L, nextSleepMs(60 * 60_000L))
    }

    @Test
    fun durationFormatting() {
        assertEquals("0:05", formatDuration(5_000L))
        assertEquals("12:05", formatDuration(725_000L))
        assertEquals("1:02:33", formatDuration(3_753_000L))
        assertEquals("0:00", formatDuration(-10L))
    }

    @Test
    fun progressAcrossParts() {
        val parts = listOf(600_000L, 600_000L, 600_000L)
        val p = bookProgress(parts, SavedPosition(fileIndex = 1, posMs = 300_000L))
        assertEquals(0.5f, p.fraction)
        assertEquals(900_000L, p.remainingMs)
        assertEquals("50% · 15:00 left", statusLine(p, SavedPosition(fileIndex = 1, posMs = 300_000L)))
        assertEquals("Not started · 30:00", statusLine(bookProgress(parts, null), null))
        assertEquals("Finished", statusLine(bookProgress(parts, SavedPosition(finished = true)), SavedPosition(finished = true)))
    }
}
