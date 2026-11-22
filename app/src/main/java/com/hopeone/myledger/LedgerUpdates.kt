package com.hopeone.myledger

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val REPOSITORY = "hopeone/My_ledger"
private const val APK_NAME = "olli_ledger.apk"
private const val MAX_APK_BYTES = 200L * 1024 * 1024
private val versionLine = Regex("""(?im)^\s*versionCode\s*:\s*([0-9]+)[ \t]*\r?$""")
private val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

internal fun releaseVersionCode(tag: String, notes: String): Long? {
    val matches = versionLine.findAll(notes).toList()
    if (matches.isEmpty() && tag == "v1.0.3") return 3L
    return matches.singleOrNull()?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
}

internal data class LedgerRelease(
    val versionCode: Long,
    val label: String,
    val url: URL,
    val size: Long,
    val sha256: String?
)

internal class UpdateException(message: String) : IOException(message)

class LedgerUpdateFileProvider : FileProvider()

internal class LedgerUpdates(private val context: Context) {
    private val packages = context.packageManager
    private val updateDirectory = File(context.cacheDir, "updates")
    private val apk = File(updateDirectory, APK_NAME)

    private val signingFlags: Int
        @Suppress("DEPRECATION")
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    suspend fun publishedAt(versionName: String, versionCode: Long): Instant? = withContext(Dispatchers.IO) {
        val version = versionName.removePrefix("v")
        for (tag in listOf("v$version", version)) {
            ensureActive()
            val encodedTag = URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
            val connection = open(URL("https://api.github.com/repos/$REPOSITORY/releases/tags/$encodedTag"))
            try {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                when (connection.responseCode) {
                    404 -> continue
                    200 -> {
                        val bytes = connection.inputStream.use { it.readNBytesCompat(1024 * 1024) }
                        val release = JSONObject(bytes.toString(Charsets.UTF_8))
                        if (release.optString("tag_name") != tag || release.optBoolean("draft")) continue
                        val releaseCode = releaseVersionCode(tag, release.optString("body"))
                        if (releaseCode != null && releaseCode != versionCode) continue
                        val publishedAt = release.opt("published_at") as? String ?: continue
                        return@withContext Instant.parse(publishedAt)
                    }
                    else -> return@withContext null
                }
            } finally {
                connection.disconnect()
            }
        }
        null
    }

    suspend fun latest(): LedgerRelease? = withContext(Dispatchers.IO) {
        val installed = PackageInfoCompat.getLongVersionCode(installedPackage())
        val connection = open(URL("https://api.github.com/repos/$REPOSITORY/releases/latest"))
        val release = try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (connection.responseCode != 200) throw IOException("Release HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { it.readNBytesCompat(1024 * 1024) }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
        val tag = release.getString("tag_name")
        val version = releaseVersionCode(tag, release.optString("body"))
            ?: throw UpdateException("릴리스에서 versionCode를 확인할 수 없습니다.")
        if (version <= installed) return@withContext null
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .singleOrNull { it.optString("name") == APK_NAME }
            ?: throw UpdateException("릴리스에 $APK_NAME 파일이 없습니다.")
        val url = URL(asset.getString("browser_download_url"))
        if (url.protocol != "https" || url.host != "github.com" ||
            !url.path.startsWith("/$REPOSITORY/releases/download/") ||
            !url.path.endsWith("/$APK_NAME")
        ) throw UpdateException("APK 다운로드 주소를 확인할 수 없습니다.")
        val size = asset.getLong("size")
        if (size !in 1..MAX_APK_BYTES) throw UpdateException("APK 파일 크기를 확인할 수 없습니다.")
        val digest = asset.optString("digest").takeUnless { it.isBlank() || it == "null" }
        val sha256 = digest?.removePrefix("sha256:")
        if (sha256 != null && !Regex("[0-9a-fA-F]{64}").matches(sha256)) {
            throw UpdateException("APK 체크섬을 확인할 수 없습니다.")
        }
        LedgerRelease(version, tag, url, size, sha256)
    }

    suspend fun download(release: LedgerRelease) = withContext(Dispatchers.IO) {
        if (!updateDirectory.isDirectory && !updateDirectory.mkdirs()) throw IOException("No update directory")
        val part = File.createTempFile("olli-download-", ".apk", updateDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = openDownload(release.url)
            try {
                connection.inputStream.use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var received = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > release.size) throw IOException("APK too large")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        if (received != release.size) throw IOException("Incomplete APK")
                    }
                }
            } finally {
                connection.disconnect()
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !sha256.equals(release.sha256, ignoreCase = true)) {
                throw UpdateException("다운로드한 APK의 체크섬이 일치하지 않습니다.")
            }
            validateApk(part, release.versionCode)
            ensureActive()
            if (!part.renameTo(apk)) throw IOException("Cannot save APK")
        } finally {
            part.delete()
        }
    }

    fun canInstall() = packages.canRequestPackageInstalls()

    suspend fun installerIntent(versionCode: Long): Intent = withContext(Dispatchers.IO) {
        validateApk(apk, versionCode)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        @Suppress("DEPRECATION")
        Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun permissionIntent() = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", context.packageName, null)
    )

    @Suppress("DEPRECATION")
    private fun installedPackage(flags: Int = 0): PackageInfo = packages.getPackageInfo(context.packageName, flags)

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, expectedVersion: Long) {
        val installed = installedPackage(signingFlags)
        // Android can parse the archive from a temporary path before it is exposed via FileProvider.
        val archive = packages.getPackageArchiveInfo(file.path, signingFlags)
            ?: throw UpdateException("다운로드한 APK를 읽을 수 없습니다. 다시 다운로드해 주세요.")
        if (archive.packageName != context.packageName) throw UpdateException("APK의 앱 ID가 다릅니다.")
        val version = PackageInfoCompat.getLongVersionCode(archive)
        if (version != expectedVersion || version <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw UpdateException("APK 버전이 릴리스 정보와 일치하지 않거나 이미 설치된 버전입니다.")
        }
        fun currentSigners(info: PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                .orEmpty().toSet()
        val signers = currentSigners(installed)
        if (signers.isEmpty() || currentSigners(archive) != signers) {
            throw UpdateException("APK 서명이 설치된 앱과 달라 업데이트할 수 없습니다.")
        }
    }

    private fun openDownload(initial: URL): HttpURLConnection {
        var url = initial
        repeat(5) {
            if (url.protocol != "https" || url.host !in downloadHosts || url.userInfo != null ||
                url.port !in listOf(-1, 443)
            ) throw IOException("Unexpected download host")
            val connection = open(url)
            try {
                when (val status = connection.responseCode) {
                    200 -> return connection
                    301, 302, 303, 307, 308 -> url = URL(url, checkNotNull(connection.getHeaderField("Location")))
                    else -> throw IOException("APK HTTP $status")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many redirects")
    }

    private fun open(url: URL) = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Olli-Ledger-Android")
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > limit) throw IOException("Release response too large")
            output.write(buffer, 0, count)
        }
    }
}
