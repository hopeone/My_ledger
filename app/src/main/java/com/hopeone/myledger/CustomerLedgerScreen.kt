package com.hopeone.myledger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hopeone.myledger.data.CustomerEntity
import com.hopeone.myledger.data.LedgerDao
import com.hopeone.myledger.data.TransactionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
                else -> items(history, key = { it.id }) { TransactionRow(it) }
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
            dao = dao,
            onClose = { transactionType = null }
        )
    }
}

// 저장한 거래 시각을 기기의 시간대로 바꿔 날짜와 시간을 표시합니다.
@Composable
private fun TransactionRow(transaction: TransactionEntity) {
    val dateTime = Instant.ofEpochMilli(transaction.date).atZone(ZoneId.systemDefault())
    val sign = when (transaction.type) {
        CREDIT -> "+"
        REPAYMENT -> "−"
        else -> ""
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(transaction.type, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(sign + formatWon(transaction.amount), fontWeight = FontWeight.Bold)
            }
            Text(
                "${dateTime.format(dateFormat)} ${dateTime.format(timeFormat)}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (transaction.memo.isNotBlank()) {
                Text(transaction.memo, Modifier.padding(top = 4.dp))
            }
        }
    }
}

// 날짜와 시간을 각각 선택하고 기존 date 필드에 밀리초로 저장합니다.
@Composable
private fun TransactionDialog(customerId: Long, type: String, dao: LedgerDao, onClose: () -> Unit) {
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
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("메모 (선택)") },
                    enabled = !saving,
                    maxLines = 3
                )
                if (saveFailed) {
                    Text("거래를 저장하지 못했습니다. 다시 시도해 주세요.", color = MaterialTheme.colorScheme.error)
                }
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
                            dao.insertTransaction(
                                TransactionEntity(
                                    customerId = customerId,
                                    type = type,
                                    amount = amount,
                                    date = selectedDate.atTime(selectedTime).atZone(zone).toInstant().toEpochMilli(),
                                    memo = memo.trim()
                                )
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
