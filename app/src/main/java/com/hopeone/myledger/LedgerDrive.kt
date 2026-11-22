package com.hopeone.myledger

import android.util.Log
import com.hopeone.myledger.data.MAX_BACKUP_BYTES
import com.hopeone.myledger.data.copyBounded
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
private const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files"
private const val ARCHIVE_MIME = "application/zip"
private const val BACKUP_NAME = "olli-ledger-v1.zip"

internal data class DriveRevision(
    val id: String,
    val createdAt: Instant,
    val size: Long,
    val md5: String,
    val device: BackupDevice?
)

internal class LedgerDrive(private val token: String) {
    private var includeDeviceProperties = true

    suspend fun revisions(): List<DriveRevision> = withContext(Dispatchers.IO) {
        try {
            readRevisions(includeDeviceProperties)
        } catch (error: DriveHttpError) {
            if (!includeDeviceProperties || error.status !in listOf(400, 403)) throw error
            Log.w(DRIVE_LOG_TAG, "Retrying Drive list with the v1.6 field selection", error)
            readRevisions(false).also { includeDeviceProperties = false }
        }
    }

    private fun readRevisions(includeDevice: Boolean): List<DriveRevision> {
        val result = mutableListOf<DriveRevision>()
        val query = encode("name = '$BACKUP_NAME'")
        val deviceField = if (includeDevice) ",appProperties" else ""
        val fields = "nextPageToken,files(id,createdTime,size,md5Checksum$deviceField)"
        var page: String? = null
        do {
            val next = page?.let { "&pageToken=${encode(it)}" }.orEmpty()
            val json = request("$DRIVE_FILES?spaces=appDataFolder&q=$query&pageSize=100&fields=$fields$next") {
                checkSuccess()
                readJson()
            }
            val files = if (json.has("files")) json.getJSONArray("files") else JSONArray()
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val properties = item.optJSONObject("appProperties")
                val name = (properties?.opt(DEVICE_NAME_KEY) as? String)?.takeIf { it.isNotBlank() }
                val model = (properties?.opt(DEVICE_MODEL_KEY) as? String)?.takeIf { it.isNotBlank() }
                result.add(DriveRevision(item.getString("id"), Instant.parse(item.getString("createdTime")),
                    item.getLong("size"), item.getString("md5Checksum"),
                    if (name != null && model != null) BackupDevice(name, model) else null))
            }
            page = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null)
        return result.sortedWith(compareByDescending<DriveRevision> { it.createdAt }.thenBy { it.id })
    }

    suspend fun download(revision: DriveRevision, destination: File) = withContext(Dispatchers.IO) {
        if (revision.size !in 1..MAX_BACKUP_BYTES) throw SyncProblem("지원하는 백업 파일 크기를 벗어났습니다.")
        request("$DRIVE_FILES/${encode(revision.id)}?alt=media") {
            checkSuccess()
            val digest = MessageDigest.getInstance("MD5")
            val size = DigestInputStream(inputStream, digest).use { input ->
                destination.outputStream().use { input.copyBounded(it, revision.size) }
            }
            val md5 = digest.digest().joinToString("") { "%02x".format(it) }
            if (size != revision.size || !md5.equals(revision.md5, ignoreCase = true)) {
                throw SyncProblem("내려받은 파일의 크기 또는 체크섬이 일치하지 않습니다.")
            }
        }
    }

    suspend fun upload(archive: File, device: BackupDevice) = withContext(Dispatchers.IO) {
        // Each upload creates a separate revision so simultaneous uploads preserve both versions.
        val metadata = JSONObject()
            .put("name", BACKUP_NAME)
            .put("mimeType", ARCHIVE_MIME)
            .put("parents", JSONArray(listOf("appDataFolder")))
            // Drive supplies createdTime for the same file. Keep the ZIP format unchanged.
            .put("appProperties", JSONObject()
                .put(DEVICE_NAME_KEY, device.name)
                .put(DEVICE_MODEL_KEY, device.model))
            .toString().toByteArray(Charsets.UTF_8)
        val size = archive.length()
        require(size in 1..MAX_BACKUP_BYTES)
        val session = syncStage("Drive 업로드 세션 생성") {
            request("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id") {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("X-Upload-Content-Type", ARCHIVE_MIME)
                setRequestProperty("X-Upload-Content-Length", size.toString())
                setFixedLengthStreamingMode(metadata.size)
                outputStream.use { it.write(metadata) }
                checkSuccess()
                val location = getHeaderField("Location") ?: throw SyncProblem("Drive 응답에 업로드 주소가 없습니다.")
                val url = try {
                    URL(location)
                } catch (_: java.net.MalformedURLException) {
                    throw SyncProblem("Drive에서 올바르지 않은 업로드 주소를 반환했습니다.")
                }
                if (url.protocol != "https" || url.host != "www.googleapis.com" ||
                    url.userInfo != null || url.port !in listOf(-1, 443)
                ) throw SyncProblem("Drive에서 예상하지 않은 업로드 주소를 반환했습니다.")
                url
            }
        }
        syncStage("Drive 백업 파일 전송") {
            request(session.toString()) {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("Content-Type", ARCHIVE_MIME)
                setFixedLengthStreamingMode(size)
                outputStream.buffered().use { output ->
                    archive.inputStream().use { check(it.copyBounded(output, size) == size) }
                }
                checkSuccess()
                if (readJson().optString("id").isBlank()) {
                    throw SyncProblem("Drive 응답에서 업로드 완료를 확인할 수 없습니다.")
                }
            }
        }
    }

    private inline fun <T> request(url: String, work: HttpURLConnection.() -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            return connection.work()
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.checkSuccess() {
        val status = responseCode
        if (status !in 200..299) {
            // Read only structured reason codes. Never log response bodies or session URLs.
            val reason = runCatching {
                val bytes = ByteArrayOutputStream()
                errorStream?.use { it.copyBounded(bytes, 8192L) }
                val error = JSONObject(bytes.toString("UTF-8")).optJSONObject("error")
                val serviceReason = error?.optJSONArray("details")?.optJSONObject(0)?.optString("reason")
                val apiReason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
                (serviceReason?.takeIf { it.isNotBlank() } ?: apiReason)
                    ?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) }
            }.getOrNull()
            throw DriveHttpError(status, reason)
        }
    }

    private fun HttpURLConnection.readJson() =
        inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
