package com.hopeone.myledger

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.hopeone.myledger.data.LedgerBackup
import com.hopeone.myledger.data.LedgerSnapshot
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private data class SyncReview(
    val email: String,
    val drive: LedgerDrive,
    val revisions: List<DriveRevision>,
    val local: LedgerSnapshot
)

private sealed interface SyncAction {
    data object Backup : SyncAction
    data class Restore(val revision: DriveRevision) : SyncAction
}

private val syncDate = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")

private fun syncTime(time: Instant): String = syncDate.format(time.atZone(ZoneId.systemDefault()))

@Composable
internal fun LedgerSyncControls(backup: LedgerBackup) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("olli_drive_sync", Context.MODE_PRIVATE) }
    var email by rememberSaveable { mutableStateOf(prefs.getString("email", null)) }
    var lastSync by remember { mutableLongStateOf(email?.let { prefs.getLong("last:$it", 0L) } ?: 0L) }
    var open by rememberSaveable { mutableStateOf(false) }
    var pendingEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<SyncReview?>(null) }
    var action by remember { mutableStateOf<SyncAction?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val authorization = remember(context) { Identity.getAuthorizationClient(context) }

    suspend fun loadReview(account: String, result: AuthorizationResult) {
        check(!result.hasResolution() && DRIVE_SCOPE in result.grantedScopes.orEmpty())
        val drive = LedgerDrive(requireNotNull(result.accessToken).also { check(it.isNotBlank()) })
        review = SyncReview(account, drive, drive.revisions(), backup.snapshot())
        prefs.edit().putString("email", account).apply()
        email = account
        lastSync = prefs.getLong("last:$account", 0L)
    }

    fun runSync(errorMessage: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        status = null
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                review = null
                action = null
                failure = errorMessage
            } finally {
                busy = false
            }
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val account = pendingEmail
        pendingEmail = null
        if (result.resultCode == Activity.RESULT_OK && account != null) {
            runSync("Google Drive 연결에 실패했습니다. 계정과 앱 권한을 확인해 주세요.") {
                loadReview(account, authorization.getAuthorizationResultFromIntent(result.data))
            }
        }
    }

    fun authorize(account: String) = runSync("Google Drive 연결에 실패했습니다. 계정과 앱 권한을 확인해 주세요.") {
        review = null
        val request = AuthorizationRequest.builder()
            .setAccount(Account(account, "com.google"))
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .setOptOutIncludingGrantedScopes(true)
            .build()
        val result = authorization.authorize(request).await()
        if (result.hasResolution()) {
            val sender = requireNotNull(result.pendingIntent).intentSender
            pendingEmail = account
            try {
                consent.launch(IntentSenderRequest.Builder(sender).build())
            } catch (error: Exception) {
                pendingEmail = null
                throw error
            }
        } else {
            loadReview(account, result)
        }
    }

    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.let(::authorize)
        }
    }

    fun chooseAccount() {
        try {
            accountPicker.launch(AccountManager.newChooseAccountIntent(
                email?.let { Account(it, "com.google") }, null, arrayOf("com.google"),
                null, null, null, null
            ))
        } catch (_: Exception) {
            failure = "이 기기에서 Google 계정 선택 화면을 열 수 없습니다."
        }
    }

    Column {
        TextButton(onClick = {
            open = true
            review = null
            action = null
            status = null
            failure = null
            email?.let(::authorize)
        }) { Text("Google Drive 동기화") }
        Text(
            if (lastSync == 0L) "마지막 동기화 없음" else "마지막 동기화 ${syncTime(Instant.ofEpochMilli(lastSync))}",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (!open) return
    val current = review
    val selected = action
    AlertDialog(
        onDismissRequest = { if (!busy && pendingEmail == null) open = false },
        title = { Text("Google Drive 동기화") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                (current?.email ?: email)?.let { Text(it) }
                if (busy) Text("처리 중입니다…")
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                status?.let { Text(it) }
                if (current == null && !busy && pendingEmail == null) {
                    email?.let { account -> TextButton(onClick = { authorize(account) }) { Text("다시 확인") } }
                    TextButton(onClick = ::chooseAccount) { Text("Google 계정 선택") }
                }
                if (current != null && selected == null) {
                    Text("이 기기 · 고객 ${current.local.customers.size}명 · 거래 ${current.local.transactions.size}건")
                    Button(onClick = { action = SyncAction.Backup }) { Text("이 기기의 장부 백업") }
                    if (current.revisions.isEmpty()) Text("Drive에 저장된 백업이 없습니다.")
                    for (revision in current.revisions) {
                        TextButton(onClick = { action = SyncAction.Restore(revision) }) {
                            Text("${syncTime(revision.createdAt)} 백업 복원")
                        }
                    }
                    TextButton(onClick = ::chooseAccount) { Text("다른 Google 계정 선택") }
                }
                when (selected) {
                    SyncAction.Backup -> Text(
                        if (current?.revisions.isNullOrEmpty()) "이 기기의 전체 장부를 Drive에 저장합니다."
                        else "다른 기기의 장부와 내용이 다를 수 있습니다. 이 기기의 전체 장부를 새 백업으로 저장합니다. 기존 백업도 선택할 수 있습니다."
                    )
                    is SyncAction.Restore -> Text(
                        "${syncTime(selected.revision.createdAt)} 백업을 검증한 뒤 이 기기의 장부 전체를 해당 버전으로 바꿉니다. 이 기기의 변경사항을 보관하려면 먼저 백업해 주세요."
                    )
                    null -> Unit
                }
            }
        },
        confirmButton = {
            if (current != null && selected != null) {
                TextButton(enabled = !busy, onClick = {
                    runSync("동기화를 완료하지 못했습니다. 장부나 Drive 백업이 변경되었는지 확인한 뒤 다시 시도해 주세요.") {
                        val archive = withContext(Dispatchers.IO) { File.createTempFile("olli-", ".zip", context.cacheDir) }
                        try {
                            when (selected) {
                                SyncAction.Backup -> {
                                    backup.write(archive, current.local)
                                    check(current.drive.revisions() == current.revisions)
                                    current.drive.upload(archive)
                                }
                                is SyncAction.Restore -> {
                                    current.drive.download(selected.revision, archive)
                                    check(current.drive.revisions() == current.revisions)
                                    backup.restore(archive, current.local)
                                }
                            }
                            lastSync = System.currentTimeMillis()
                            prefs.edit().putLong("last:${current.email}", lastSync).apply()
                            review = null
                            action = null
                            status = if (selected == SyncAction.Backup) "Drive 백업을 저장했습니다." else "장부를 복원했습니다."
                        } finally {
                            archive.delete()
                        }
                    }
                }) { Text(if (selected == SyncAction.Backup) "백업 확인" else "복원 확인") }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy && pendingEmail == null, onClick = {
                if (action != null) action = null else open = false
            }) { Text(if (action != null) "취소" else "닫기") }
        }
    )
}
