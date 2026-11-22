package com.hopeone.myledger

import com.hopeone.myledger.data.MAX_BACKUP_BYTES
import com.hopeone.myledger.data.copyBounded
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
    val md5: String
)

internal class LedgerDrive(private val token: String) {
    suspend fun revisions(): List<DriveRevision> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DriveRevision>()
        var page: String? = null
        do {
            val query = encode("name = '$BACKUP_NAME'")
            val fields = "nextPageToken,files(id,createdTime,size,md5Checksum)"
            val next = page?.let { "&pageToken=${encode(it)}" }.orEmpty()
            val json = request("$DRIVE_FILES?spaces=appDataFolder&q=$query&pageSize=100&fields=$fields$next") {
                checkSuccess()
                readJson()
            }
            val files = json.getJSONArray("files")
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                result.add(DriveRevision(item.getString("id"), Instant.parse(item.getString("createdTime")),
                    item.getLong("size"), item.getString("md5Checksum")))
            }
            page = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null)
        result.sortedWith(compareByDescending<DriveRevision> { it.createdAt }.thenBy { it.id })
    }

    suspend fun download(revision: DriveRevision, destination: File) = withContext(Dispatchers.IO) {
        require(revision.size in 1..MAX_BACKUP_BYTES)
        request("$DRIVE_FILES/${encode(revision.id)}?alt=media") {
            checkSuccess()
            val digest = MessageDigest.getInstance("MD5")
            val size = DigestInputStream(inputStream, digest).use { input ->
                destination.outputStream().use { input.copyBounded(it, revision.size) }
            }
            val md5 = digest.digest().joinToString("") { "%02x".format(it) }
            require(size == revision.size && md5.equals(revision.md5, ignoreCase = true))
        }
    }

    suspend fun upload(archive: File) = withContext(Dispatchers.IO) {
        // Each upload creates a separate revision so simultaneous uploads preserve both versions.
        val metadata = JSONObject()
            .put("name", BACKUP_NAME)
            .put("mimeType", ARCHIVE_MIME)
            .put("parents", JSONArray(listOf("appDataFolder")))
            .toString().toByteArray(Charsets.UTF_8)
        val size = archive.length()
        require(size in 1..MAX_BACKUP_BYTES)
        val session = request("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id") {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", ARCHIVE_MIME)
            setRequestProperty("X-Upload-Content-Length", size.toString())
            setFixedLengthStreamingMode(metadata.size)
            outputStream.use { it.write(metadata) }
            checkSuccess()
            URL(requireNotNull(getHeaderField("Location")))
        }
        require(session.protocol == "https" && session.host == "www.googleapis.com")
        request(session.toString()) {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", ARCHIVE_MIME)
            setFixedLengthStreamingMode(size)
            outputStream.buffered().use { output ->
                archive.inputStream().use { check(it.copyBounded(output, size) == size) }
            }
            checkSuccess()
            check(readJson().getString("id").isNotBlank())
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
        if (status !in 200..299) throw IOException("Drive HTTP $status")
    }

    private fun HttpURLConnection.readJson() =
        inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
