package ai.alagent.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object AlAgentMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN privacy_mode INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE turns ADD COLUMN tool_definitions_json TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE turns ADD COLUMN verification_status TEXT")
            db.execSQL("ALTER TABLE turns ADD COLUMN errors_json TEXT NOT NULL DEFAULT '[]'")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
