
package com.hopeone.myledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 고객 테이블과 거래 테이블을 하나의 데이터베이스로 관리합니다.
// 데이터 구조가 변경되면 version을 올리고 마이그레이션해야 합니다.
@Database(
    entities = [
        CustomerEntity::class,
        TransactionEntity::class,
        TransactionPhotoEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class LedgerDatabase : RoomDatabase() {

    // Room이 LedgerDao의 실제 구현 객체를 제공합니다.
    abstract fun ledgerDao(): LedgerDao

    companion object {

        // 기존 고객과 거래는 그대로 두고 사진 테이블과 인덱스만 추가합니다.
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS transaction_photos (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        transactionId INTEGER NOT NULL,
                        relativePath TEXT NOT NULL,
                        FOREIGN KEY(transactionId) REFERENCES transactions(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transaction_photos_transactionId " +
                        "ON transaction_photos (transactionId)"
                )
            }
        }

        // 앱 전체에서 데이터베이스 객체 하나만 공유합니다.
        @Volatile
        private var INSTANCE: LedgerDatabase? = null

        // 데이터베이스 객체를 가져오는 함수입니다.
        fun getDatabase(context: Context): LedgerDatabase {

            // 이미 생성된 객체가 있으면 재사용합니다.
            return INSTANCE ?: synchronized(this) {

                // SQLite 데이터베이스 객체를 생성합니다.
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    "myledger_database"
                ).addMigrations(MIGRATION_1_2).build()

                INSTANCE = instance

                instance
            }
        }
    }
}
