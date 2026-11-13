package com.hopeone.myledger

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hopeone.myledger.data.TransactionPhotoEntity
import com.hopeone.myledger.data.TransactionPhotoStore
import com.hopeone.myledger.data.MAX_TRANSACTION_PHOTOS
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TransactionPhotoThumbnails(
    photos: List<TransactionPhotoEntity>,
    store: TransactionPhotoStore,
    onOpen: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
            LedgerPhoto(
                file = store.file(photo.relativePath),
                description = "첨부 사진 ${index + 1}",
                thumbnail = true,
                modifier = Modifier.size(64.dp).clickable { onOpen(index) }
            )
        }
    }
}

@Composable
internal fun LedgerPhotoPickerButton(
    remaining: Int,
    enabled: Boolean,
    label: String,
    onPicked: (List<Uri>) -> Unit
) {
    val singlePicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) onPicked(listOf(uri))
    }
    val multiplePicker = rememberLauncherForActivityResult(PickMultipleVisualMedia(MAX_TRANSACTION_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) onPicked(uris.distinct())
    }
    TextButton(onClick = {
        if (remaining == 1) {
            singlePicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        } else {
            multiplePicker.launch(
                PickVisualMediaRequest.Builder().setMediaType(PickVisualMedia.ImageOnly).setMaxItems(remaining).build()
            )
        }
    }, enabled = enabled && remaining > 0) { Text(label) }
}

@Composable
internal fun TransactionAttachments(
    transactionId: Long,
    photos: List<TransactionPhotoEntity>,
    initialIndex: Int?,
    store: TransactionPhotoStore,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = initialIndex ?: 0) { photos.size }
    var pendingDelete by remember { mutableStateOf<TransactionPhotoEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                TextButton(onClick = onClose, enabled = !busy) { Text("닫기") }
                if (photos.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("첨부된 사진이 없습니다.")
                    }
                } else {
                    HorizontalPager(
                        state = pager,
                        key = { photos[it].id },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) { index ->
                        LedgerPhoto(
                            file = store.file(photos[index].relativePath),
                            description = "첨부 사진 ${index + 1}",
                            thumbnail = false,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    val current = pager.currentPage.coerceIn(0, photos.lastIndex)
                    Text("${current + 1} / ${photos.size}", Modifier.align(Alignment.CenterHorizontally))
                    TextButton(
                        onClick = { pendingDelete = photos[current] },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("이 사진 삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
                val remaining = MAX_TRANSACTION_PHOTOS - photos.size
                LedgerPhotoPickerButton(remaining, !busy, "사진 추가 (${photos.size}/$MAX_TRANSACTION_PHOTOS)") { uris ->
                    if (uris.size > remaining) {
                        error = "사진을 최대 ${remaining}장 더 추가할 수 있습니다."
                    } else {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                store.addPhotos(transactionId, uris)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "사진을 추가하지 못했습니다. 다시 시도해 주세요."
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
                if (busy) Text("사진 처리 중")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    pendingDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("첨부 사진 삭제") },
            text = { Text("이 사진을 삭제하면 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            store.deletePhoto(photo)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "사진을 삭제하지 못했습니다. 다시 시도해 주세요."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("취소") } }
        )
    }
}

@Composable
private fun LedgerPhoto(file: File, description: String, thumbnail: Boolean, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, file, thumbnail) {
        value = null
        value = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options()
            if (thumbnail) {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.path, options)
                options.inSampleSize = Integer.highestOneBit((maxOf(options.outWidth, options.outHeight) / 160).coerceAtLeast(1))
                options.inJustDecodeBounds = false
            }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, description, Modifier.fillMaxSize(), contentScale = if (thumbnail) ContentScale.Crop else ContentScale.Fit)
        } else {
            Text(if (thumbnail) "사진" else "사진을 불러오는 중이거나 파일을 찾을 수 없습니다.")
        }
    }
}
