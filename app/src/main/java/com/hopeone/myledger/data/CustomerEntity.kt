
package com.hopeone.myledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// 고객 정보를 SQLite 데이터베이스에 저장하는 클래스입니다.
// tableName은 실제 데이터베이스에 생성될 테이블 이름입니다.
@Entity(tableName = "customers")
data class CustomerEntity(

    // 고객마다 자동으로 증가하는 고유 번호를 부여합니다.
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 고객 이름
    val name: String,

    // 전화번호 (입력하지 않아도 됩니다)
    val phone: String = "",

    // 고객 분류
    val category: String = "",

    // 고객별 참고 메모
    val memo: String = ""
)
