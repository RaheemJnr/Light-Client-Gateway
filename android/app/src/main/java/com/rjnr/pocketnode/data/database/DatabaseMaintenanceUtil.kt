package com.rjnr.pocketnode.data.database

import android.database.Cursor

object DatabaseMaintenanceUtil {

    /** ~30 days, matches the periodic-VACUUM cadence we run from startup idle. */
    const val VACUUM_INTERVAL_MS: Long = 30L * 24 * 60 * 60 * 1000

    fun vacuum(db: AppDatabase) {
        db.openHelper.writableDatabase.execSQL("VACUUM")
    }

    /**
     * Run VACUUM only if the last run was longer than [intervalMs] ago.
     * Returns true if VACUUM ran, false if the throttle window blocked it.
     */
    fun vacuumIfDue(
        db: AppDatabase,
        lastRunMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        intervalMs: Long = VACUUM_INTERVAL_MS
    ): Boolean {
        if (nowMs - lastRunMs < intervalMs) return false
        vacuum(db)
        return true
    }

    fun getDatabaseSizeBytes(db: AppDatabase): Long {
        val cursor: Cursor = db.openHelper.readableDatabase.query(
            "SELECT page_count * page_size as size FROM pragma_page_count(), pragma_page_size()"
        )
        return cursor.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }
}
