package com.hopeone.myledger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
