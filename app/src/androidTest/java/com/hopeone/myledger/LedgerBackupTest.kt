package com.hopeone.myledger

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hopeone.myledger.data.CustomerEntity
import com.hopeone.myledger.data.LedgerBackup
import com.hopeone.myledger.data.LedgerDatabase
import com.hopeone.myledger.data.TransactionEntity
import com.hopeone.myledger.data.TransactionPhotoEntity
import com.hopeone.myledger.data.TransactionPhotoStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerBackupTest {
    @Test
    fun editsKeepPhotoLinksAndBackupRestoresSameIdsWithoutDuplicates() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File.createTempFile("ledger-test-", ".fixture", context.cacheDir)
        check(fixture.delete() && fixture.mkdir())
        val files = object : ContextWrapper(context) {
            override fun getFilesDir() = fixture
        }
        try {
            val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
            try {
                val dao = database.ledgerDao()
                val store = TransactionPhotoStore(files, database)
                val backup = LedgerBackup(database, store)
                val customer = CustomerEntity(id = 5, name = "테스트 거래처")
                val original = TransactionEntity(21, customer.id, "외상", 10000, 123, "기존 주문")
                val edited = original.copy(type = "상환", amount = 3000, memo = "수정한 주문")
                val photo = TransactionPhotoEntity(30, original.id, "fixture.jpg")
                dao.insertCustomer(customer)
                dao.insertTransaction(original)
                store.preparePhotoDirectory()
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                try {
                    store.file(photo.relativePath).outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                    }
                } finally {
                    bitmap.recycle()
                }
                val pixels = store.file(photo.relativePath).readBytes()
                dao.insertPhotos(listOf(photo))

                assertEquals(1, dao.updateTransaction(edited))
                assertEquals(-3000L, dao.getCustomerBalance(customer.id).first())
                assertEquals(listOf(photo), dao.snapshotPhotos())
                val archive = File(fixture, "valid.zip")
                backup.write(archive, backup.snapshot())
                dao.updateTransaction(original)
                val changed = backup.snapshot()
                val invalid = File(fixture, "invalid.zip").apply { writeText("truncated archive") }
                assertTrue(runCatching { backup.restore(invalid, changed) }.isFailure)
                assertEquals(changed, backup.snapshot())
                assertArrayEquals(pixels, store.file(photo.relativePath).readBytes())
                assertTrue(runCatching { backup.restore(archive, changed.copy(transactions = emptyList())) }.isFailure)
                assertEquals(changed, backup.snapshot())

                repeat(2) { backup.restore(archive, backup.snapshot()) }
                val restored = backup.snapshot()
                assertEquals(listOf(customer), restored.customers)
                assertEquals(listOf(edited), restored.transactions)
                assertEquals(photo, restored.photos.single().copy(relativePath = photo.relativePath))
                assertArrayEquals(pixels, store.file(restored.photos.single().relativePath).readBytes())
            } finally {
                database.close()
            }
        } finally {
            fixture.deleteRecursively()
        }
    }
}
