package com.hopeone.myledger

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data object Failed : PhotoLoad
    data class Ready(val image: ImageBitmap) : PhotoLoad
}

@Composable
internal fun ZoomableLedgerPhoto(uri: Uri, description: String, onZoomChanged: (Boolean) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val load by produceState<PhotoLoad>(PhotoLoad.Loading, resolver, uri) {
        value = PhotoLoad.Loading
        value = withContext(Dispatchers.IO) {
            try {
                decodeViewerPhoto(resolver, uri)?.let(PhotoLoad::Ready) ?: PhotoLoad.Failed
            } catch (_: IOException) {
                PhotoLoad.Failed
            } catch (_: SecurityException) {
                PhotoLoad.Failed
            } catch (_: IllegalArgumentException) {
                PhotoLoad.Failed
            } catch (_: OutOfMemoryError) {
                PhotoLoad.Failed
            }
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        when (val photo = load) {
            PhotoLoad.Loading -> CircularProgressIndicator()
            PhotoLoad.Failed -> Text(
                "사진 원본을 찾을 수 없거나 읽을 수 없습니다.",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            is PhotoLoad.Ready -> ZoomableImage(photo.image, description, onZoomChanged)
        }
    }
}

@Composable
private fun ZoomableImage(image: ImageBitmap, description: String, onZoomChanged: (Boolean) -> Unit) {
    var viewport by remember(image) { mutableStateOf(IntSize.Zero) }
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    val zoomCallback by rememberUpdatedState(onZoomChanged)
    DisposableEffect(image) {
        onDispose { zoomCallback(false) }
    }
    val transform = rememberTransformableState { zoom, pan, _ ->
        val nextScale = (scale * zoom).coerceIn(1f, 5f)
        val fit = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val maxX = ((image.width * fit * nextScale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((image.height * fit * nextScale - viewport.height) / 2f).coerceAtLeast(0f)
        val nextOffset = offset * (nextScale / scale) + pan
        offset = Offset(nextOffset.x.coerceIn(-maxX, maxX), nextOffset.y.coerceIn(-maxY, maxY))
        scale = nextScale
        zoomCallback(scale > 1f)
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size ->
                if (viewport != size) {
                    viewport = size
                    if (scale != 1f) {
                        scale = 1f
                        offset = Offset.Zero
                        zoomCallback(false)
                    }
                }
            }
            .transformable(state = transform, canPan = { scale > 1f }, lockRotationOnZoomPan = true)
    ) {
        Image(
            bitmap = image,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        )
    }
}

private fun decodeViewerPhoto(resolver: ContentResolver, uri: Uri): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val source = resolver.openInputStream(uri) ?: return null
    source.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return null

    // Pager may briefly display both outgoing and incoming pages. Limit each decoded bitmap.
    val maxPixels = (Runtime.getRuntime().maxMemory() / 64).coerceIn(256_000L, 4_000_000L)
    val width = options.outWidth.toLong()
    val height = options.outHeight.toLong()
    var sample = 1
    while ((width + sample - 1) / sample * ((height + sample - 1) / sample) > maxPixels) {
        sample *= 2
    }
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    options.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
}
