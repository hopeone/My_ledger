package com.hopeone.myledger

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hopeone.myledger.data.CustomerEntity
import com.hopeone.myledger.data.LedgerDatabase
import com.hopeone.myledger.data.TransactionEntity
import com.hopeone.myledger.data.TransactionPhotoEntity
import com.hopeone.myledger.data.TransactionPhotoStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionPhotoLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun viewerLoadsStoredImagePansWhileZoomedAndReportsMissingNextPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureContext = object : ContextWrapper(context) {
            override fun getFilesDir() = context.cacheDir
        }
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val store = TransactionPhotoStore(fixtureContext, database)
        val first = TransactionPhotoEntity(1, 1, "${UUID.randomUUID()}.jpg")
        val missing = TransactionPhotoEntity(2, 1, "${UUID.randomUUID()}.jpg")
        val source = store.file(first.relativePath)
        try {
            store.preparePhotoDirectory()
            val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
            try {
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                bitmap.recycle()
            }
            compose.setContent {
                TransactionAttachments(1, listOf(first, missing), 0, store, onClose = {})
            }
            val description = "첨부 사진 1"
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
            }
            val photo = compose.onNodeWithContentDescription(description)
            photo.performTouchInput {
                val near = Offset(width * 0.04f, 0f)
                val far = Offset(width * 0.32f, 0f)
                pinch(center - near, center - far, center + near, center + far)
            }
            photo.performTouchInput { swipeLeft() }
            compose.onNodeWithText("1 / 2").assertExists()
            photo.performTouchInput {
                val near = Offset(width * 0.02f, 0f)
                val far = Offset(width * 0.36f, 0f)
                pinch(center - far, center - near, center + far, center + near)
            }
            photo.performTouchInput { swipeLeft() }
            compose.onNodeWithText("2 / 2").assertExists()
            val error = "사진 원본을 찾을 수 없거나 읽을 수 없습니다."
            compose.waitUntil(10_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            source.delete()
            database.close()
        }
    }

    @Test
    fun attachmentsFollowMultilineMemoAndOpenIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val dao = database.ledgerDao()
            val customer = CustomerEntity(id = 1, name = "거래처")
            val memo = "첫 줄 주문자\n둘째 줄 주문자\n셋째 줄 주문자"
            runBlocking {
                dao.insertCustomer(customer)
                dao.insertTransaction(TransactionEntity(1, customer.id, "외상", 15000, memo = memo))
                // A missing file uses the same fixed thumbnail slot as a loaded image.
                dao.insertPhotos(listOf(TransactionPhotoEntity(1, 1, "${UUID.randomUUID()}.jpg")))
            }
            val store = TransactionPhotoStore(context, database)
            compose.setContent {
                CustomerLedgerScreen(customer, dao, store, Color.Blue, onBack = {})
            }

            val addLabel = "사진 추가 (1/10)"
            compose.waitUntil(10_000) { compose.onAllNodesWithText(addLabel).fetchSemanticsNodes().isNotEmpty() }
            val photoButton = compose.onNodeWithText(addLabel)
            val memoBounds = compose.onNodeWithText("주문: $memo", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val thumbnailBounds = compose.onNodeWithText("사진", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val addBounds = photoButton.fetchSemanticsNode().boundsInRoot
            assertTrue("The thumbnail must follow the memo", memoBounds.bottom <= thumbnailBounds.top)
            assertTrue("The add button must follow the thumbnail", thumbnailBounds.bottom <= addBounds.top)

            photoButton.performClick()
            compose.onNodeWithText("닫기").assertExists()
            compose.onNodeWithText("거래내역 수정").assertDoesNotExist()
        } finally {
            database.close()
        }
    }
}
