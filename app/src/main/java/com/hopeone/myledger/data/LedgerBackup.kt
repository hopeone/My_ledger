package com.hopeone.myledger.data

import android.graphics.BitmapFactory
import androidx.room.withTransaction
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_BACKUP_BYTES = 512L * 1024 * 1024
private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
private const val MAX_PHOTO_BYTES = 24L * 1024 * 1024
private const val MANIFEST = "ledger.json"
private const val FORMAT = "com.hopeone.myledger.backup"

internal data class LedgerSnapshot(
    val customers: List<CustomerEntity>,
    val transactions: List<TransactionEntity>,
    val photos: List<TransactionPhotoEntity>
)

internal class LedgerBackup(
    private val database: LedgerDatabase,
    private val photoStore: TransactionPhotoStore
) {
    private val dao = database.ledgerDao()

    suspend fun snapshot(): LedgerSnapshot = database.withTransaction {
        LedgerSnapshot(dao.snapshotCustomers(), dao.snapshotTransactions(), dao.snapshotPhotos())
    }

    suspend fun write(destination: File, expected: LedgerSnapshot) = photoStore.changePhotos {
        val current = snapshot()
        check(current == expected)
        val manifest = current.toJson().toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES)
        var remaining = MAX_BACKUP_BYTES - manifest.size
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest)
            zip.closeEntry()
            for (photo in current.photos) {
                val source = photoStore.file(photo.relativePath)
                require(source.length() in 1..minOf(remaining, MAX_PHOTO_BYTES))
                zip.putNextEntry(ZipEntry(photoEntry(photo.id)))
                remaining -= source.inputStream().use { it.copyBounded(zip, minOf(remaining, MAX_PHOTO_BYTES)) }
                zip.closeEntry()
            }
        }
        require(destination.length() <= MAX_BACKUP_BYTES)
    }

    suspend fun restore(archive: File, expected: LedgerSnapshot) = photoStore.changePhotos {
        require(archive.length() in 1..MAX_BACKUP_BYTES)
        val stagedFiles = mutableListOf<File>()
        var committed = false
        try {
            val (backup, photos) = ZipFile(archive).use { zip ->
                val entries = zip.entries().toList()
                val manifest = entries.single { it.name == MANIFEST }
                val manifestBytes = java.io.ByteArrayOutputStream()
                zip.copyVerified(manifest, manifestBytes, MAX_MANIFEST_BYTES)
                val backup = parseSnapshot(JSONObject(manifestBytes.toString("UTF-8")))
                val attachments = backup.photos.associateBy { photoEntry(it.id) }
                require(entries.size == attachments.size + 1)
                val photos = mutableListOf<TransactionPhotoEntity>()
                var remaining = MAX_BACKUP_BYTES - manifest.size
                for (entry in entries) {
                    if (entry.name == MANIFEST) continue
                    val photo = attachments.getValue(entry.name)
                    photoStore.preparePhotoDirectory()
                    val path = "${UUID.randomUUID()}.jpg"
                    val staged = photoStore.file(path)
                    stagedFiles.add(staged)
                    staged.outputStream().use { output ->
                        remaining -= zip.copyVerified(entry, output, minOf(remaining, MAX_PHOTO_BYTES))
                        output.fd.sync()
                    }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(staged.path, options)
                    require(options.outMimeType == "image/jpeg" &&
                        options.outWidth in 1..3000 && options.outHeight in 1..3000)
                    photos.add(photo.copy(relativePath = path))
                }
                require(photos.map { it.id }.toSet().size == attachments.size)
                backup to photos
            }
            database.withTransaction {
                check(snapshot() == expected)
                dao.clearCustomersForRestore()
                dao.restoreCustomers(backup.customers)
                dao.restoreTransactions(backup.transactions)
                dao.insertPhotos(photos)
            }
            committed = true
        } finally {
            if (!committed) stagedFiles.forEach { it.delete() }
        }
    }

    private fun LedgerSnapshot.toJson() = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("customers", JSONArray(customers.map {
            JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone)
                .put("category", it.category).put("memo", it.memo)
        }))
        .put("transactions", JSONArray(transactions.map {
            JSONObject().put("id", it.id).put("customerId", it.customerId).put("type", it.type)
                .put("amount", it.amount).put("date", it.date).put("memo", it.memo)
        }))
        .put("photos", JSONArray(photos.map {
            JSONObject().put("id", it.id).put("transactionId", it.transactionId)
        }))

    private fun parseSnapshot(json: JSONObject): LedgerSnapshot {
        require(json.string("format") == FORMAT && json.integer("version") == 1L)
        val customers = json.readObjects("customers") {
            CustomerEntity(integer("id"), string("name"), string("phone"), string("category"), string("memo"))
        }
        val customerIds = customers.map { it.id }.toSet()
        require(customerIds.size == customers.size && customerIds.all { it > 0 })
        val transactions = json.readObjects("transactions") {
            TransactionEntity(integer("id"), integer("customerId"), string("type"),
                integer("amount"), integer("date"), string("memo"))
        }
        val transactionIds = transactions.map { it.id }.toSet()
        require(transactionIds.size == transactions.size && transactions.all {
            it.id > 0 && it.customerId in customerIds && it.amount > 0 &&
                (it.type == "외상" || it.type == "상환")
        })
        val photos = json.readObjects("photos") {
            TransactionPhotoEntity(integer("id"), integer("transactionId"), "")
        }
        require(photos.map { it.id }.toSet().size == photos.size &&
            photos.all { it.id > 0 && it.transactionId in transactionIds } &&
            photos.groupingBy { it.transactionId }.eachCount().values.all { it <= MAX_TRANSACTION_PHOTOS })
        return LedgerSnapshot(customers, transactions, photos)
    }

    private fun JSONObject.string(key: String): String = get(key) as String

    private fun JSONObject.integer(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Expected an integer")
    }

    private fun <T> JSONObject.readObjects(key: String, parse: JSONObject.() -> T): List<T> {
        val array = getJSONArray(key)
        return List(array.length()) { array.getJSONObject(it).parse() }
    }

    private fun photoEntry(id: Long) = "photos/$id.jpg"

    private fun ZipFile.copyVerified(entry: ZipEntry, output: OutputStream, limit: Long): Long {
        require(!entry.isDirectory && entry.size in 1..limit)
        return CheckedInputStream(getInputStream(entry), CRC32()).use { input ->
            val count = input.copyBounded(output, limit)
            require(count == entry.size && input.checksum.value == entry.crc)
            count
        }
    }
}

internal fun InputStream.copyBounded(output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read == -1) return total
        total += read
        require(total <= limit)
        output.write(buffer, 0, read)
    }
}
