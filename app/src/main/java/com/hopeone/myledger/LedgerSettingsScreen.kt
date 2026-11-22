package com.hopeone.myledger

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LedgerSettingsScreen(blue: Color, onBack: () -> Unit) {
    val context = LocalContext.current
    val updates = remember(context) { LedgerUpdates(context.applicationContext) }
    val releaseDate by produceState("확인 중…", updates) {
        value = try {
            updates.publishedAt(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
                ?.atZone(ZoneId.systemDefault())
                ?.format(DateTimeFormatter.ofPattern("uuuu.MM.dd", Locale.ROOT))
                ?: "날짜 확인 불가"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "날짜 확인 불가"
        }
    }
    val deviceSettings = remember(context) { LedgerDeviceSettings(context) }
    var deviceName by remember(context) { mutableStateOf(deviceSettings.device().name) }
    var deviceNameDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<LedgerRelease?>(null) }
    var pendingVersion by rememberSaveable { mutableStateOf<Long?>(null) }
    var permissionPrompt by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = progress != null

    fun runUpdate(status: String, fallback: String, work: suspend () -> Unit) {
        if (progress != null) return
        progress = status
        message = null
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UpdateException) {
                message = error.message
            } catch (_: Exception) {
                message = fallback
            } finally {
                progress = null
            }
        }
    }

    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    suspend fun install(version: Long) {
        if (updates.canInstall()) {
            val intent = try {
                updates.installerIntent(version)
            } catch (error: UpdateException) {
                pendingVersion = null
                throw error
            }
            installer.launch(intent)
        } else {
            permissionPrompt = true
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val version = pendingVersion
        if (version != null && updates.canInstall()) {
            runUpdate("설치 파일을 확인하는 중입니다…", "Android 설치 화면을 열 수 없습니다.") { install(version) }
        } else {
            message = "이 앱의 외부 APK 설치 권한이 필요합니다."
        }
    }

    BackHandler { if (!busy) onBack() }
    Column(
        Modifier.fillMaxSize().background(Color(0xFFEDF0F2)).safeDrawingPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().background(blue).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, enabled = !busy) { Text("뒤로", color = Color.White) }
            Text("설정", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Column(Modifier.padding(16.dp)) {
            Text("Olli Ledger", style = MaterialTheme.typography.titleLarge)
            Text("현재 버전 ${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(top = 4.dp))
            Text("릴리스 날짜 $releaseDate", modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Text("기기 이름", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !busy, onClick = { deviceNameDraft = deviceName }) {
                Text(deviceName)
            }
            Button(enabled = !busy, onClick = {
                runUpdate("업데이트를 확인하는 중입니다…", "업데이트를 확인하지 못했습니다. 연결 상태를 확인해 주세요.") {
                    release = updates.latest()
                    if (release == null) message = "최신 버전입니다"
                }
            }) { Text("업데이트 확인") }
            pendingVersion?.let { version ->
                TextButton(enabled = !busy, onClick = {
                    runUpdate("설치 파일을 확인하는 중입니다…", "Android 설치 화면을 열 수 없습니다.") { install(version) }
                }) { Text("다운로드한 업데이트 설치") }
            }
            (progress ?: message)?.let { Text(it, Modifier.padding(top = 8.dp)) }
        }
    }

    deviceNameDraft?.let { draft ->
        val name = draft.trim()
        val tooLong = name.toByteArray().size > MAX_DEVICE_NAME_BYTES
        AlertDialog(
            onDismissRequest = { deviceNameDraft = null },
            title = { Text("기기 이름") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { deviceNameDraft = it },
                    label = { Text("기기 이름") },
                    singleLine = true,
                    isError = tooLong,
                    supportingText = {
                        if (tooLong) Text("기기 이름을 조금 더 짧게 입력해 주세요.")
                    }
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !tooLong, onClick = {
                    deviceSettings.saveName(name)
                    deviceName = name
                    deviceNameDraft = null
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { deviceNameDraft = null }) { Text("취소") } }
        )
    }

    release?.let { update ->
        AlertDialog(
            onDismissRequest = { release = null },
            title = { Text("새 업데이트가 있습니다") },
            text = { Text("${update.label} (빌드 ${update.versionCode})을 다운로드하고 설치하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    release = null
                    pendingVersion = null
                    runUpdate("업데이트를 다운로드하는 중입니다…", "APK 다운로드에 실패했습니다. 다시 시도해 주세요.") {
                        updates.download(update)
                        pendingVersion = update.versionCode
                        if (updates.canInstall()) {
                            try {
                                install(update.versionCode)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: UpdateException) {
                                throw error
                            } catch (_: Exception) {
                                message = "Android 설치 화면을 열 수 없습니다."
                            }
                        } else {
                            permissionPrompt = true
                        }
                    }
                }) { Text("업데이트") }
            },
            dismissButton = { TextButton(onClick = { release = null }) { Text("나중에") } }
        )
    }

    if (permissionPrompt) {
        AlertDialog(
            onDismissRequest = { permissionPrompt = false },
            title = { Text("APK 설치 권한") },
            text = { Text("Android 설정에서 이 앱의 외부 APK 설치를 허용한 뒤 돌아오면 설치를 계속합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    permissionPrompt = false
                    try {
                        permission.launch(updates.permissionIntent())
                    } catch (_: Exception) {
                        message = "Android 설치 권한 설정을 열 수 없습니다."
                    }
                }) { Text("권한 설정 열기") }
            },
            dismissButton = { TextButton(onClick = { permissionPrompt = false }) { Text("취소") } }
        )
    }
}
