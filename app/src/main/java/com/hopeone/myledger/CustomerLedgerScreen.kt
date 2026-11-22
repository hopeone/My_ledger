package com.hopeone.myledger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hopeone.myledger.data.CustomerEntity
import com.hopeone.myledger.data.LedgerDao
import com.hopeone.myledger.data.TransactionEntity
import com.hopeone.myledger.data.TransactionPhotoStore
import com.hopeone.myledger.data.MAX_TRANSACTION_PHOTOS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.unit.sp

// 거래 유형은 잔액을 계산하는 LedgerDao의 값과 동일하게 저장합니다.
private const val CREDIT = "외상"
private const val REPAYMENT = "상환"
private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.KOREA)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)

// 선택한 고객의 거래와 잔액을 Room에서 구독해 저장 직후 화면에 반영합니다.
@Composable
internal fun CustomerLedgerScreen(
    customer: CustomerEntity,
    dao: LedgerDao,
    photoStore: TransactionPhotoStore,
    blue: Color,
    onBack: () -> Unit
) {
    val transactions by remember(dao, customer.id) {
        dao.getCustomerTransactions(customer.id)
    }.collectAsState(initial = null)
    val balance by remember(dao, customer.id) {
        dao.getCustomerBalance(customer.id)
    }.collectAsState(initial = null)
    var transactionType by rememberSaveable(customer.id) { mutableStateOf<String?>(null) }

    var transactionToDelete by remember(customer.id) { mutableStateOf<TransactionEntity?>(null) }
    val photos by remember(dao, customer.id) {
        dao.getCustomerPhotos(customer.id)
    }.collectAsState(initial = emptyList())
    val photosByTransaction = remember(photos) { photos.groupBy { it.transactionId } }
    var attachmentTransactionId by rememberSaveable(customer.id) { mutableStateOf<Long?>(null) }
    var initialPhotoIndex by rememberSaveable(customer.id) { mutableStateOf<Int?>(null) }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEDF0F2))
            .safeDrawingPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(blue).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("뒤로", color = Color.White) }
            Text(
                text = customer.name,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("현재 잔액", style = MaterialTheme.typography.titleMedium)
            balance?.let {
                Text(
                    text = ledgerBalanceText(it),
                    color = ledgerBalanceColor(it),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val history = transactions
            when {
                history == null -> item { Text("거래 내역을 불러오는 중입니다.") }
                history.isEmpty() -> item { Text("아직 거래 내역이 없습니다.") }
                else -> items(history, key = { it.id }) { transaction ->
                    TransactionRow(
                        transaction,
                        onDelete = { transactionToDelete = transaction },
                        attachments = {
                            val attachments = photosByTransaction[transaction.id].orEmpty()
                            if (attachments.isNotEmpty()) {
                                TransactionPhotoThumbnails(attachments, photoStore) { index ->
                                    initialPhotoIndex = index
                                    attachmentTransactionId = transaction.id
                                }
                            }
                            TextButton(onClick = {
                                initialPhotoIndex = null
                                attachmentTransactionId = transaction.id
                            }, enabled = attachments.size < MAX_TRANSACTION_PHOTOS) {
                                Text("사진 추가 (${attachments.size}/$MAX_TRANSACTION_PHOTOS)")
                            }
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { transactionType = REPAYMENT }, modifier = Modifier.weight(1f)) {
                Text("상환")
            }
            Button(onClick = { transactionType = CREDIT }, modifier = Modifier.weight(1f)) {
                Text("거래추가")
            }
        }
    }

    transactionType?.let { type ->
        TransactionDialog(
            customerId = customer.id,
            type = type,
            photoStore = photoStore,
            onClose = { transactionType = null }
        )
    }

    transactionToDelete?.let { transaction ->
        DeleteTransactionDialog(transaction, photoStore, onClose = { transactionToDelete = null })
    }
    attachmentTransactionId?.let { transactionId ->
        if (transactions?.any { it.id == transactionId } == true) {
            TransactionAttachments(
                transactionId = transactionId,
                photos = photosByTransaction[transactionId].orEmpty(),
                initialIndex = initialPhotoIndex,
                store = photoStore,
                onClose = { attachmentTransactionId = null }
            )
        }
    }
}

// 저장한 거래 시각을 기기의 시간대로 바꿔 날짜와 시간을 표시합니다.
@Composable
private fun TransactionRow(
    transaction: TransactionEntity,
    onDelete: () -> Unit,
    attachments: @Composable () -> Unit
) {
    val dateTime = Instant.ofEpochMilli(transaction.date).atZone(ZoneId.systemDefault())
    val sign = when (transaction.type) {
        CREDIT -> "+"
        REPAYMENT -> "−"
        else -> ""
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f).padding(vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 거래 종류 표시
                    Text(
                        text = if (transaction.type == CREDIT) "거래 추가" else "상환",
                        modifier = Modifier.weight(1f),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // 거래 금액 표시
                    Text(
                        text = sign + formatWon(transaction.amount),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                } // 거래 종류와 금액 Row 종료

                // 거래 날짜 및 시간
                Text(
                    text = "${dateTime.format(dateFormat)} ${dateTime.format(timeFormat)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 20.sp
                )

                // 주문자 정보 표시
                if (transaction.memo.isNotBlank()) {
                    Text(
                        text = "주문: ${transaction.memo}",
                        modifier = Modifier.padding(top = 4.dp),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } // Column 종료

            // 거래 삭제 버튼
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_transaction),
                    contentDescription = "거래 삭제",
                    modifier = Modifier.size(20.dp)
                )
            }
        } // 바깥 Row 종료
    } // Card 종료
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            attachments()
        }
    }


@Composable
private fun DeleteTransactionDialog(
    transaction: TransactionEntity,
    photoStore: TransactionPhotoStore,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!deleting) onClose() },
        title = { Text("거래내역 삭제") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("거래 금액: ${formatWon(transaction.amount)}")
                Text("이 거래를 삭제하면 되돌릴 수 없습니다.")
                Text("첨부된 사진도 함께 삭제됩니다.")
                if (deleteFailed) {
                    Text("거래를 삭제하지 못했습니다. 다시 시도해 주세요.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (deleting) return@TextButton
                    deleting = true
                    deleteFailed = false
                    scope.launch {
                        try {
                            // Room Flow가 거래 목록과 잔액을 다시 조회합니다.
                            photoStore.deleteTransaction(transaction)
                            onClose()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            deleteFailed = true
                        } finally {
                            deleting = false
                        }
                    }
                },
                enabled = !deleting
            ) {
                Text(if (deleting) "삭제 중" else "삭제", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !deleting) { Text("취소") }
        }
    )
}

// 날짜와 시간을 각각 선택하고 기존 date 필드에 밀리초로 저장합니다.
@Composable
private fun TransactionDialog(
    customerId: Long,
    type: String,
    photoStore: TransactionPhotoStore,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val now = remember(zone) { Instant.now().atZone(zone) }
    var date by rememberSaveable { mutableStateOf(now.toLocalDate().toEpochDay()) }
    var time by rememberSaveable { mutableStateOf(now.toLocalTime().toSecondOfDay() / 60) }
    var amountInput by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var photoUris by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var selectionTooLarge by remember { mutableStateOf(false) }
    val amount = amountInput.trim().toLongOrNull()?.takeIf { it > 0 }
    val invalidAmount = amountInput.isNotEmpty() && amount == null
    val selectedDate = LocalDate.ofEpochDay(date)
    val selectedTime = LocalTime.of(time / 60, time % 60)

    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text("${type} 등록") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = amountInput,
                    onValueChange = { amountInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("거래 금액 (원)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !saving,
                    singleLine = true,
                    isError = invalidAmount,
                    supportingText = if (invalidAmount) {
                        { Text("1원 이상의 정수 금액을 입력해 주세요.") }
                    } else {
                        null
                    }
                )
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, day ->
                                date = LocalDate.of(year, month + 1, day).toEpochDay()
                            },
                            selectedDate.year,
                            selectedDate.monthValue - 1,
                            selectedDate.dayOfMonth
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving
                ) {
                    Text("날짜 ${selectedDate.format(dateFormat)}")
                }
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> time = hour * 60 + minute },
                            selectedTime.hour,
                            selectedTime.minute,
                            true
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving
                ) {
                    Text("시간 ${selectedTime.format(timeFormat)}")
                }
                if (saveFailed) {
                    Text("거래를 저장하지 못했습니다. 다시 시도해 주세요.", color = MaterialTheme.colorScheme.error)
                }
                LedgerPhotoPickerButton(
                    remaining = MAX_TRANSACTION_PHOTOS - photoUris.size,
                    enabled = !saving,
                    label = "사진 추가 (${photoUris.size}/$MAX_TRANSACTION_PHOTOS)"
                ) { uris ->
                    val selection = (photoUris + uris.map(Uri::toString)).distinct()
                    selectionTooLarge = selection.size > MAX_TRANSACTION_PHOTOS
                    if (!selectionTooLarge) photoUris = ArrayList(selection)
                }
                if (selectionTooLarge) {
                    Text("한 거래에 최대 10장까지 선택할 수 있습니다.", color = MaterialTheme.colorScheme.error)
                }
                if (photoUris.isNotEmpty()) {
                    TextButton(onClick = {
                        photoUris = arrayListOf()
                        selectionTooLarge = false
                    }, enabled = !saving) { Text("사진 선택 지우기") }
                }
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("주문자", fontSize = 18.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp),
                    enabled = !saving,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 연속 탭으로 같은 거래가 두 번 저장되지 않도록 버튼을 잠급니다.
                    if (saving || amount == null) return@TextButton
                    saving = true
                    saveFailed = false
                    scope.launch {
                        try {
                            photoStore.saveTransaction(
                                TransactionEntity(
                                    customerId = customerId,
                                    type = type,
                                    amount = amount,
                                    date = selectedDate.atTime(selectedTime).atZone(zone).toInstant().toEpochMilli(),
                                    memo = memo.trim()
                                ),
                                photoUris.map(Uri::parse)
                            )
                            onClose()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            saveFailed = true
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving && amount != null
            ) {
                Text(if (saving) "저장 중" else "저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !saving) { Text("취소") }
        }
    )
}

private fun formatWon(amount: Long): String = String.format(Locale.KOREA, "%,d원", amount)

// 상환액이 외상보다 많으면 기존 고객 목록과 동일하게 선불로 표시합니다.
internal fun ledgerBalanceText(balance: Long): String = when {
    balance > 0 -> "${formatWon(balance)} 미결제"
    balance < 0 -> "${formatWon(balance).removePrefix("-")} 선불"
    else -> "0원"
}

internal fun ledgerBalanceColor(balance: Long): Color = when {
    balance > 0 -> Color.Red
    balance < 0 -> Color(0xFF226633)
    else -> Color.Gray
}
