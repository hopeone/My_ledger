package com.hopeone.myledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal const val MAX_TRANSACTION_PHOTOS = 10
private const val MAX_IMAGE_EDGE = 3000

internal class TransactionPhotoStore(context: Context, private val database: LedgerDatabase) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.filesDir, "transaction_photos")
    private val dao = database.ledgerDao()

    fun file(relativePath: String): File = File(directory, relativePath)

    suspend fun saveTransaction(transaction: TransactionEntity, uris: List<Uri>) {
        if (uris.isEmpty()) {
            dao.insertTransaction(transaction)
            return
        }
        require(uris.size <= MAX_TRANSACTION_PHOTOS)
        changePhotos {
            copyThenSave(uris) { paths ->
                database.withTransaction {
                    val transactionId = dao.insertTransaction(transaction)
                    insertPhotos(transactionId, paths)
                }
            }
        }
    }

    suspend fun addPhotos(transactionId: Long, uris: List<Uri>) = changePhotos {
        // 사진 선택기가 개수 제한을 지원하지 않는 기기에서도 저장 단계에서 확인합니다.
        require(uris.size <= MAX_TRANSACTION_PHOTOS - dao.countPhotos(transactionId))
        copyThenSave(uris) { paths ->
            database.withTransaction { insertPhotos(transactionId, paths) }
        }
    }

    suspend fun deletePhoto(photo: TransactionPhotoEntity) = changePhotos {
        if (dao.deletePhoto(photo.id, photo.transactionId) == 1) {
            file(photo.relativePath).delete()
        }
    }

    suspend fun deleteTransaction(transaction: TransactionEntity) = changePhotos {
        val paths = database.withTransaction {
            val photos = dao.getPhotoPaths(transaction.id)
            if (dao.deleteTransactionById(transaction.id, transaction.customerId) == 1) {
                photos
            } else {
                emptyList()
            }
        }
        // DB 삭제가 완료된 뒤에만 파일을 지웁니다. 실패한 파일은 다음 시작 때 정리합니다.
        paths.forEach { file(it).delete() }
    }

    suspend fun removeOrphanFiles() = changePhotos {
        val referenced = dao.getAllPhotoPaths().toHashSet()
        directory.listFiles()?.forEach { photo ->
            if (photo.isFile && photo.name !in referenced) photo.delete()
        }
    }

    private suspend fun insertPhotos(transactionId: Long, paths: List<String>) {
        dao.insertPhotos(paths.map { TransactionPhotoEntity(transactionId = transactionId, relativePath = it) })
    }

    private suspend fun copyThenSave(uris: List<Uri>, save: suspend (List<String>) -> Unit) {
        val paths = mutableListOf<String>()
        var committed = false
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create photo directory")
            for (uri in uris) {
                val path = "${UUID.randomUUID()}.jpg"
                paths.add(path)
                val bitmap = decodePhoto(uri)
                try {
                    file(path).outputStream().use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                            throw IOException("Cannot encode photo")
                        }
                        output.fd.sync()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            // 모든 파일 복사가 끝난 다음에만 DB에 경로를 기록합니다.
            save(paths)
            committed = true
        } finally {
            if (!committed) paths.forEach { file(it).delete() }
        }
    }

    private fun decodePhoto(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(width, height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            }
        }

        // Android 8에서도 전체 원본을 메모리에 올리지 않고 EXIF 방향을 반영합니다.
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Cannot read photo size")
        val edge = maxOf(options.outWidth, options.outHeight)
        options.inSampleSize = Integer.highestOneBit(((edge - 1) / MAX_IMAGE_EDGE).coerceAtLeast(1))
        if (edge / options.inSampleSize > 4500) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot decode photo")
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        matrix.postScale(scale, scale)
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }

    private suspend fun <T> changePhotos(block: suspend () -> T): T = photoLock.withLock {
        // 화면을 닫아도 시작한 파일과 DB 변경을 마무리한 뒤 잠금을 풉니다.
        withContext(Dispatchers.IO + NonCancellable) { block() }
    }

    private companion object {
        val photoLock = Mutex()
    }
}
