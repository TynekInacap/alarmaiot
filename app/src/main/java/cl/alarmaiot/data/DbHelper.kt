package cl.alarmaiot.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base de datos SQLite local (alarmaiot.db).
 *
 * - users:    cuentas, hash de contraseña, rol y control de bloqueo.
 * - events:   bitácora de alertas, comandos, cambios de estado y eventos de seguridad.
 * - readings: lecturas de distancia del sensor (telemetría).
 *
 * Todas las consultas usan parámetros (?) para evitar inyección SQL.
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE users(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                pass_hash BLOB NOT NULL,
                salt BLOB NOT NULL,
                iterations INTEGER NOT NULL,
                role TEXT NOT NULL,
                failed_attempts INTEGER NOT NULL DEFAULT 0,
                locked_until INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                recovery_hash BLOB,
                recovery_salt BLOB
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                category TEXT NOT NULL,
                detail TEXT NOT NULL,
                state TEXT,
                distance REAL,
                source TEXT NOT NULL,
                username TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
        db.execSQL(
            """
            CREATE TABLE readings(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                distance REAL NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_readings_ts ON readings(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: código de recuperación de contraseña (solo su hash).
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE users ADD COLUMN recovery_hash BLOB")
            db.execSQL("ALTER TABLE users ADD COLUMN recovery_salt BLOB")
        }
    }

    companion object {
        const val NAME = "alarmaiot.db"
        const val VERSION = 2
    }
}
