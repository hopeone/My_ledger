
package com.hopeone.myledger.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// SQLite 데이터베이스에 작업을 요청하는 인터페이스입니다.
// Room이 실제 실행 코드를 자동으로 생성합니다.
@Dao
interface LedgerDao {
    @Query("SELECT * FROM customers ORDER BY id")
    suspend fun snapshotCustomers(): List<CustomerEntity>

    @Query("SELECT * FROM transactions ORDER BY id")
    suspend fun snapshotTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM transaction_photos ORDER BY id")
    suspend fun snapshotPhotos(): List<TransactionPhotoEntity>

    @Query("DELETE FROM customers")
    suspend fun clearCustomersForRestore()

    @Insert
    suspend fun restoreCustomers(customers: List<CustomerEntity>)

    @Insert
    suspend fun restoreTransactions(transactions: List<TransactionEntity>)

    // 모든 고객을 이름순으로 가져옵니다.
    // Flow는 데이터가 변경되면 화면에 새 목록을 전달합니다.
    @Query("SELECT * FROM customers ORDER BY name ASC")
    fun getAllCustomers(): Flow<List<CustomerEntity>>

    // 고객 ID로 특정 고객을 조회합니다.
    @Query("SELECT * FROM customers WHERE id = :customerId")
    fun getCustomer(customerId: Long): Flow<CustomerEntity?>

    // 새 고객을 저장하고 생성된 ID를 반환합니다.
    @Insert
    suspend fun insertCustomer(customer: CustomerEntity): Long

    // 기존 고객 정보를 수정합니다.
    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    // 고객을 삭제합니다.
    // 연결된 거래 내역도 함께 삭제되므로 주의해야 합니다.
    @Delete
    suspend fun deleteCustomer(customer: CustomerEntity)

    // 특정 고객의 거래 내역을 최신순으로 가져옵니다.
    @Query(
        """
        SELECT * FROM transactions
        WHERE customerId = :customerId
        ORDER BY date DESC, id DESC
        """
    )
    fun getCustomerTransactions(
        customerId: Long
    ): Flow<List<TransactionEntity>>

    // 새로운 외상 또는 상환 거래를 저장합니다.
    @Insert
    suspend fun insertTransaction(
        transaction: TransactionEntity
    ): Long

    // 기존 거래 내용을 수정합니다.
    @Update
    suspend fun updateTransaction(
        transaction: TransactionEntity
    ): Int

    // 거래 내역 한 건을 삭제합니다.
    @Delete
    suspend fun deleteTransaction(
        transaction: TransactionEntity
    )

    // 선택한 고객에게 속한 거래 ID 한 건만 삭제합니다.
    @Query("DELETE FROM transactions WHERE id = :transactionId AND customerId = :customerId")
    suspend fun deleteTransactionById(transactionId: Long, customerId: Long): Int

    @Query(
        """
        SELECT photo.* FROM transaction_photos AS photo
        JOIN transactions AS entry ON entry.id = photo.transactionId
        WHERE entry.customerId = :customerId
        ORDER BY photo.id
        """
    )
    fun getCustomerPhotos(customerId: Long): Flow<List<TransactionPhotoEntity>>

    @Query("SELECT relativePath FROM transaction_photos WHERE transactionId = :transactionId")
    suspend fun getPhotoPaths(transactionId: Long): List<String>

    @Query("SELECT relativePath FROM transaction_photos")
    suspend fun getAllPhotoPaths(): List<String>

    @Query("SELECT photo.relativePath FROM transaction_photos photo JOIN transactions entry ON entry.id = photo.transactionId WHERE entry.customerId = :customerId")
    suspend fun getCustomerPhotoPaths(customerId: Long): List<String>

    @Query("SELECT COUNT(*) FROM transaction_photos WHERE transactionId = :transactionId")
    suspend fun countPhotos(transactionId: Long): Int

    @Insert
    suspend fun insertPhotos(photos: List<TransactionPhotoEntity>)

    @Query("DELETE FROM transaction_photos WHERE id = :photoId AND transactionId = :transactionId")
    suspend fun deletePhoto(photoId: Long, transactionId: Long): Int

    // 고객별 잔액을 계산합니다.
    // 외상은 더하고 상환은 빼는 방식입니다.
    @Query(
        """
        SELECT COALESCE(
            SUM(
                CASE
                    WHEN type = '외상' THEN amount
                    WHEN type = '상환' THEN -amount
                    ELSE 0
                END
            ), 0
        )
        FROM transactions
        WHERE customerId = :customerId
        """
    )
    fun getCustomerBalance(customerId: Long): Flow<Long>
}
