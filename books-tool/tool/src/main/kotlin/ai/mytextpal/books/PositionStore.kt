package ai.mytextpal.books

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The whole point of this tool: remembers where you are in every book. Backed by the
 * SDK's DataStore, which survives process death, reboots, and reinstalls.
 */
class PositionStore(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun positionKey(bookId: String) = stringPreferencesKey("$POS_PREFIX$bookId")

    suspend fun load(bookId: String): SavedPosition? =
        dataStore.data.first()[positionKey(bookId)]?.let(::decode)

    suspend fun save(bookId: String, saved: SavedPosition) {
        dataStore.edit { it[positionKey(bookId)] = json.encodeToString(SavedPosition.serializer(), saved) }
    }

    suspend fun clear(bookId: String) {
        dataStore.edit { it.remove(positionKey(bookId)) }
    }

    /** Every saved position, keyed by book id. One read for the whole library screen. */
    suspend fun loadAll(): Map<String, SavedPosition> =
        dataStore.data.first().asMap().entries
            .filter { it.key.name.startsWith(POS_PREFIX) }
            .mapNotNull { (key, value) ->
                (value as? String)?.let(::decode)?.let { key.name.removePrefix(POS_PREFIX) to it }
            }
            .toMap()

    /** Per-file durations, cached because extracting them opens every file. */
    suspend fun partDurations(book: Book): List<Long> {
        val prefs = dataStore.data.first()
        val missing = mutableMapOf<Preferences.Key<Long>, Long>()
        val result = withContext(Dispatchers.IO) {
            book.files.map { file ->
                val key = longPreferencesKey(DUR_PREFIX + Library.durationKey(file))
                prefs[key] ?: Library.readDurationMs(file).also { missing[key] = it }
            }
        }
        if (missing.isNotEmpty()) {
            dataStore.edit { prefs -> missing.forEach { (k, v) -> prefs[k] = v } }
        }
        return result
    }

    private fun decode(raw: String): SavedPosition? =
        runCatching { json.decodeFromString(SavedPosition.serializer(), raw) }.getOrNull()

    private companion object {
        const val POS_PREFIX = "pos:"
        const val DUR_PREFIX = "dur:"
    }
}

