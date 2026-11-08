
package com.hopeone.myledger.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// 고객별 외상 및 상환 거래를 저장하는 테이블입니다.
@Entity(
    tableName = "transactions",

    // 거래 내역을 고객 ID와 연결합니다.
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],

            // 고객을 삭제하면 해당 고객의 거래도 삭제됩니다.
            onDelete = ForeignKey.CASCADE
        )
    ],

    // 고객별 거래를 빠르게 검색하기 위한 인덱스입니다.
    indices = [Index(value = ["customerId"])]
)
data class TransactionEntity(

    // 거래마다 자동으로 생성되는 고유 번호
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 어떤 고객의 거래인지 구분하는 ID
    val customerId: Long,

    // 거래 유형: 외상 또는 상환
    val type: String,

    // 거래 금액 (원 단위)
    val amount: Long,

    // 거래 날짜와 시간 (Unix 밀리초)
    val date: Long = System.currentTimeMillis(),

    // 거래 내용
    val memo: String = ""
)
