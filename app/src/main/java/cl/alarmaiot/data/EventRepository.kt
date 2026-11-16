package cl.alarmaiot.data

import android.content.ContentValues

/** Bitácora de eventos y lecturas del sensor guardadas en SQLite. */
class EventRepository(private val db: DbHelper) {

    fun log(
        category: EventCategory,
        detail: String,
        source: String,
        state: String? = null,
        distance: Float? = null,
        username: String? = null,
        ts: Long = System.currentTimeMillis(),
    ) {
        val values = ContentValues().apply {
            put("ts", ts)
            put("category", category.name)
            put("detail", detail.take(300))
            put("state", state)
            if (distance != null) put("distance", distance) else putNull("distance")
            put("source", source)
            put("username", username)
        }
        db.writableDatabase.insert("events", null, values)
        if (++writes % TRIM_EVERY == 0) trim()
    }

    fun recent(limit: Int = 200, category: EventCategory? = null): List<EventRecord> {
        val where = if (category != null) "WHERE category = ?" else ""
        val args = buildList { category?.let { add(it.name) }; add(limit.toString()) }.toTypedArray()
        val out = mutableListOf<EventRecord>()
        db.readableDatabase.rawQuery(
            "SELECT id, ts, category, detail, state, distance, source, username FROM events $where ORDER BY ts DESC, id DESC LIMIT ?",
            args,
        ).use { c ->
            while (c.moveToNext()) {
                out += EventRecord(
                    id = c.getLong(0),
                    ts = c.getLong(1),
                    category = EventCategory.parse(c.getString(2)),
                    detail = c.getString(3),
                    state = c.getString(4),
                    distance = if (c.isNull(5)) null else c.getFloat(5),
                    source = c.getString(6),
                    username = c.getString(7),
                )
            }
        }
        return out
    }

    fun addReading(distance: Float, source: String, ts: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("ts", ts)
            put("distance", distance)
            put("source", source)
        }
        db.writableDatabase.insert("readings", null, values)
        if (++writes % TRIM_EVERY == 0) trim()
    }

    /** Últimas [limit] lecturas, en orden cronológico. */
    fun recentReadings(limit: Int = 60): List<Reading> {
        val out = mutableListOf<Reading>()
        db.readableDatabase.rawQuery(
            "SELECT ts, distance FROM readings ORDER BY ts DESC, id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c -> while (c.moveToNext()) out += Reading(c.getLong(0), c.getFloat(1)) }
        return out.asReversed()
    }

    fun readingStats(nearThresholdCm: Float): ReadingStats =
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(distance), AVG(distance), SUM(CASE WHEN distance < ? THEN 1 ELSE 0 END) FROM readings",
            arrayOf(nearThresholdCm.toString()),
        ).use { c ->
            c.moveToFirst()
            ReadingStats(
                count = c.getInt(0),
                min = if (c.isNull(1)) null else c.getFloat(1),
                avg = if (c.isNull(2)) null else c.getFloat(2),
                nearCount = if (c.isNull(3)) 0 else c.getInt(3),
            )
        }

    fun clearAll() {
        db.writableDatabase.delete("events", null, null)
        db.writableDatabase.delete("readings", null, null)
    }

    /** Retención: se conservan solo los registros más recientes (minimización de datos). */
    private fun trim() {
        val w = db.writableDatabase
        w.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT $MAX_EVENTS)")
        w.execSQL("DELETE FROM readings WHERE id NOT IN (SELECT id FROM readings ORDER BY id DESC LIMIT $MAX_READINGS)")
    }

    private var writes = 0

    companion object {
        private const val TRIM_EVERY = 100
        private const val MAX_EVENTS = 2_000
        private const val MAX_READINGS = 5_000
    }
}
