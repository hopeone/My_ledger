package com.hopeone.myledger

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hopeone.myledger.data.LedgerDatabase
import com.hopeone.myledger.data.TransactionPhotoEntity
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerPhotoMigrationTest {
    @Test
    fun migrationPreservesLedgerAndLimitsCascadeToDeletedTransaction() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "photo-migration-${UUID.randomUUID()}.db"
        try {
            // 운영 DB와 별개인 파일에 v1 스키마와 기존 거래를 준비합니다.
            context.openOrCreateDatabase(name, 0, null).use { old ->
                createVersionOne(old)
                old.execSQL("INSERT INTO customers VALUES (1, '기존 고객', '010', '분류', '고객 메모')")
                old.execSQL("INSERT INTO customers VALUES (2, '다른 고객', '', '', '')")
                old.execSQL("INSERT INTO transactions VALUES (11, 1, '외상', 10000, 100, '거래 메모')")
                old.execSQL("INSERT INTO transactions VALUES (12, 1, '상환', 3000, 200, '상환 메모')")
                old.execSQL("INSERT INTO transactions VALUES (21, 2, '외상', 5000, 100, '')")
            }
            val database = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
                .addMigrations(LedgerDatabase.MIGRATION_1_2).build()
            try {
                    val dao = database.ledgerDao()
                    assertEquals("고객 메모", dao.getCustomer(1).first()?.memo)
                    val entries = dao.getCustomerTransactions(1).first()
                    assertEquals(listOf(12L, 11L), entries.map { it.id })
                    assertEquals(listOf("상환", "외상"), entries.map { it.type })
                    assertEquals(listOf(3000L, 10000L), entries.map { it.amount })
                    assertEquals(listOf("상환 메모", "거래 메모"), entries.map { it.memo })
                    assertEquals(7000L, dao.getCustomerBalance(1).first())
                    assertTrue(dao.getCustomerPhotos(1).first().isEmpty())

                    dao.insertPhotos(listOf(
                        TransactionPhotoEntity(31, 11, "first.jpg"),
                        TransactionPhotoEntity(32, 11, "second.jpg"),
                        TransactionPhotoEntity(33, 12, "payment.jpg"),
                        TransactionPhotoEntity(34, 21, "other.jpg")
                    ))
                    assertEquals(1, dao.deletePhoto(31, 11))
                    assertEquals(entries, dao.getCustomerTransactions(1).first())
                    assertEquals(0, dao.deleteTransactionById(11, 2))
                    assertEquals(1, dao.deleteTransactionById(11, 1))
                    assertEquals(listOf("payment.jpg"), dao.getCustomerPhotos(1).first().map { it.relativePath })
                    assertEquals(listOf("other.jpg"), dao.getCustomerPhotos(2).first().map { it.relativePath })
                    assertEquals(-3000L, dao.getCustomerBalance(1).first())
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createVersionOne(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE customers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, phone TEXT NOT NULL, category TEXT NOT NULL, memo TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "customerId INTEGER NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, " +
                "date INTEGER NOT NULL, memo TEXT NOT NULL, FOREIGN KEY(customerId) " +
                "REFERENCES customers(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX index_transactions_customerId ON transactions(customerId)")
        db.version = 1
    }
}
