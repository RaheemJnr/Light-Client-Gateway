package com.rjnr.pocketnode.data.database

import android.database.Cursor

object DatabaseMaintenanceUtil {

    fun vacuum(db: AppDatabase) {
        db.openHelper.writableDatabase.execSQL("VACUUM")
    }

    fun getDatabaseSizeBytes(db: AppDatabase): Long {
        val cursor: Cursor = db.openHelper.readableDatabase.query(
            "SELECT page_count * page_size as size FROM pragma_page_count(), pragma_page_size()"
        )
        return cursor.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }
}
