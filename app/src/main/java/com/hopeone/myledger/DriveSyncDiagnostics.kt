package com.hopeone.myledger

import android.util.Log
import com.google.android.gms.common.api.ApiException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException

internal const val DRIVE_LOG_TAG = "OlliDriveSync"

internal class DriveHttpError(val status: Int, val reason: String?) :
    IOException("Drive HTTP $status${reason?.let { " ($it)" }.orEmpty()}")

internal class SyncProblem(message: String) : IOException(message)

internal class SyncStageFailure(val stage: String, cause: Exception) : IOException(stage, cause)

internal inline fun <T> syncStage(stage: String, work: () -> T): T {
    Log.d(DRIVE_LOG_TAG, "Started: $stage")
    try {
        return work().also { Log.d(DRIVE_LOG_TAG, "Completed: $stage") }
    } catch (cancelled: CancellationException) {
        Log.d(DRIVE_LOG_TAG, "Cancelled: $stage", cancelled)
        throw cancelled
    } catch (failure: SyncStageFailure) {
        throw failure
    } catch (error: Exception) {
        throw SyncStageFailure(stage, error)
    }
}

internal fun syncFailureMessage(error: Exception, fallbackStage: String): String {
    val stage = (error as? SyncStageFailure)?.stage ?: fallbackStage
    val cause = (error as? SyncStageFailure)?.cause ?: error
    val detail = when (cause) {
        is SyncProblem -> cause.message
        is DriveHttpError -> {
            val explanation = when (cause.reason) {
                "accessNotConfigured", "SERVICE_DISABLED" -> "Google Cloud 프로젝트에서 Drive API를 사용할 수 없습니다."
                "storageQuotaExceeded" -> "Google Drive 저장 공간이 부족합니다."
                "rateLimitExceeded", "userRateLimitExceeded" -> "요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."
                else -> when (cause.status) {
                    401 -> "Google 계정 인증이 만료되었습니다. 계정을 다시 연결해 주세요."
                    403 -> "Drive 접근이 거부되었습니다. 계정 권한과 앱의 Google 설정을 확인해 주세요."
                    404 -> "Drive에서 파일이나 업로드 세션을 찾을 수 없습니다."
                    429 -> "요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요."
                    in 500..599 -> "Google Drive 서버가 응답하지 못했습니다. 잠시 후 다시 시도해 주세요."
                    else -> "Google Drive가 요청을 완료하지 못했습니다."
                }
            }
            "$explanation (HTTP ${cause.status})"
        }
        is ApiException -> {
            val explanation = when (cause.statusCode) {
                4 -> "Google 계정 로그인이 필요합니다."
                7 -> "Google 인증 서버에 연결하지 못했습니다."
                10 -> "앱 서명 또는 Google OAuth 설정을 확인해 주세요."
                16 -> "Google 권한 요청이 취소되었습니다."
                else -> "Google 인증 결과를 확인할 수 없습니다."
            }
            "$explanation (Google 코드 ${cause.statusCode})"
        }
        is SocketTimeoutException -> "연결 시간이 초과되었습니다. 네트워크를 확인해 주세요."
        is UnknownHostException -> "Google 서버 주소를 찾을 수 없습니다. 네트워크를 확인해 주세요."
        is CancellationException -> "Google 요청이 취소되었습니다. 다시 시도해 주세요."
        else -> when (stage) {
            "장부 백업 파일 생성" -> "장부 또는 첨부 사진을 백업 파일로 저장할 수 없습니다. 저장 공간과 사진 파일을 확인해 주세요."
            "백업 검증 및 복원" -> "백업을 검증하거나 장부에 저장할 수 없습니다. 기존 장부는 유지됩니다."
            else -> "요청을 완료할 수 없습니다. 다시 시도해 주세요."
        }
    }
    return "$stage 실패 · $detail"
}
