package ai.mytextpal.audiobook

import android.content.Context
import org.json.JSONObject

/**
 * The whole point of this app: remembers where you are in every book, across process
 * death, reboots, and reinstalls (SharedPreferences survives `adb install -r`).
 */
class PositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    data class Saved(
        val fileIndex: Int,
        val posMs: Long,
        val speed: Float,
        val updatedAt: Long,
        val finished: Boolean,
    )

    fun load(bookId: String): Saved? {
        val raw = prefs.getString(bookId, null) ?: return null
        return try {
            val o = JSONObject(raw)
            Saved(
                fileIndex = o.optInt("i", 0),
                posMs = o.optLong("p", 0L),
                speed = o.optDouble("s", 1.0).toFloat(),
                updatedAt = o.optLong("t", 0L),
                finished = o.optBoolean("f", false),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(bookId: String, s: Saved) {
        val o = JSONObject()
            .put("i", s.fileIndex)
            .put("p", s.posMs)
            .put("s", s.speed.toDouble())
            .put("t", s.updatedAt)
            .put("f", s.finished)
        prefs.edit().putString(bookId, o.toString()).apply()
    }

    fun clear(bookId: String) {
        prefs.edit().remove(bookId).apply()
    }
}
