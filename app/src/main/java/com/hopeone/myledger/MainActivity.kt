
package com.hopeone.myledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.hopeone.myledger.data.CustomerEntity
import com.hopeone.myledger.data.LedgerBackup
import com.hopeone.myledger.data.LedgerDatabase
import com.hopeone.myledger.data.TransactionPhotoStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// 앱의 첫 번째 화면을 실행하는 클래스
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Jetpack Compose로 화면을 구성합니다.
        setContent {
            LedgerApp()
        }
    }
}

// 외상장부 메인 화면
@Composable
fun LedgerApp() {

    val blue = Color(0xFF2196F3)

    // 현재 앱의 Context를 가져옵니다.
    val context = LocalContext.current

    // Room 데이터베이스 객체를 가져옵니다.
    val database = remember {
        LedgerDatabase.getDatabase(context)
    }

    // 고객 정보를 저장하고 조회하는 DAO 객체입니다.
    val dao = remember(database) {
        database.ledgerDao()
    }
    val photoStore = remember(database) { TransactionPhotoStore(context, database) }
    val backup = remember(database, photoStore) { LedgerBackup(database, photoStore) }
    LaunchedEffect(photoStore) {
        try {
            photoStore.removeOrphanFiles()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("LedgerApp", "Could not clean up unused transaction photos", error)
        }
    }

    // 데이터베이스의 고객 목록을 실시간으로 받아옵니다.
    // 고객이 추가되면 Room Flow가 새 목록을 전달합니다.
    val customers by remember(dao) { dao.getAllCustomers() }
        .collectAsState(initial = emptyList())
    val listState = rememberLazyListState()

    // 화면을 다시 만들 때도 선택한 고객을 ID로 찾아 장부를 복원합니다.
    var selectedCustomerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // 데이터베이스 저장 작업에 사용할 코루틴입니다.
    val scope = rememberCoroutineScope()

    // 고객 추가 창 표시 여부
    var showDialog by remember {
        mutableStateOf(false)
    }

    // 고객 이름 입력값
    var customerName by remember {
        mutableStateOf("")
    }

    if (showSettings) {
        LedgerSettingsScreen(blue, onBack = { showSettings = false })
        return
    }

    val selectedCustomer = customers.firstOrNull { it.id == selectedCustomerId }
    if (selectedCustomer != null) {
        CustomerLedgerScreen(
            customer = selectedCustomer,
            dao = dao,
            photoStore = photoStore,
            blue = blue,
            onBack = { selectedCustomerId = null }
        )
        return
    }

    // 화면 전체를 세로로 배치합니다.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEDF0F2))
            .safeDrawingPadding()
    ) {

        // 상단 파란색 제목 영역
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(blue)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "거래장부",
                modifier = Modifier.weight(1f),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { showSettings = true }) { Text("설정", color = Color.White) }
        }

        Box(Modifier.padding(horizontal = 12.dp)) { LedgerSyncControls(backup) }

        // SQLite에서 불러온 고객 목록
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f)
        ) {
            items(
                items = customers,
                key = { it.id }
            ) { customer ->

                // 고객별 외상 잔액을 Room에서 조회합니다.
                val balance by remember(customer.id) {
                    dao.getCustomerBalance(customer.id)
                }.collectAsState(initial = 0L)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 5.dp)
                        .background(Color.White)
                        .clickable { selectedCustomerId = customer.id }
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        text = customer.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // 외상 잔액은 빨간색, 선불 잔액은 초록색
                    Text(
                        text = ledgerBalanceText(balance),
                        color = ledgerBalanceColor(balance),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 하단 고객 추가 버튼
        Button(
            onClick = {
                customerName = ""
                showDialog = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .height(55.dp)
        ) {
            Text("고객 추가", fontSize = 20.sp)
        }
    }

    // 고객 추가 입력창
    if (showDialog) {

        AlertDialog(
            onDismissRequest = {
                showDialog = false
            },
            title = {
                Text("고객 추가")
            },
            text = {
                OutlinedTextField(
                    value = customerName,
                    onValueChange = {
                        customerName = it
                    },
                    label = {
                        Text("고객 이름")
                    },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = customerName.trim()

                        if (name.isNotEmpty()) {

                            // 코루틴에서 고객을 SQLite에 저장합니다.
                            scope.launch {
                                dao.insertCustomer(
                                    CustomerEntity(name = name)
                                )

                                // 저장 완료 후 입력창을 닫습니다.
                                showDialog = false
                                customerName = ""
                            }
                        }
                    }
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                    }
                ) {
                    Text("취소")
                }
            }
        )
    }
}
