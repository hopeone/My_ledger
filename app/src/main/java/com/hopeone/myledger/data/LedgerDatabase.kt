
package com.hopeone.myledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 고객 테이블과 거래 테이블을 하나의 데이터베이스로 관리합니다.
// 데이터 구조가 변경되면 version을 올리고 마이그레이션해야 합니다.
@Database(
    entities = [
        CustomerEntity::class,
        TransactionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LedgerDatabase : RoomDatabase() {

    // Room이 LedgerDao의 실제 구현 객체를 제공합니다.
    abstract fun ledgerDao(): LedgerDao

    companion object {

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
                ).build()

                INSTANCE = instance

                instance
            }
        }
    }
}
