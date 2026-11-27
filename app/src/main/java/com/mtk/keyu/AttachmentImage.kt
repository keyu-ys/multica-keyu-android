package com.mtk.keyu

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

private val thumbCache = mutableMapOf<String, ImageBitmap>()
private val fullCache = mutableMapOf<String, ImageBitmap>()

fun Att.isImage() = type.startsWith("image/") || Regex("\\.(png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

private fun decodeSampled(bytes: ByteArray, maxWidth: Int): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxWidth) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)!!.asImageBitmap()
}

private fun decodeSampledFile(file: File, maxWidth: Int): ImageBitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxWidth) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
}

/** 消息里的图片附件：带登录态加载，内存缓存，点击可全屏双指缩放查看原图。 */
@Composable
fun AttachmentImage(api: Api, att: Att) {
    var bmp by remember(att.id) { mutableStateOf(thumbCache[att.id]) }
    var failed by remember(att.id) { mutableStateOf(false) }
    var retryTick by remember(att.id) { mutableIntStateOf(0) }
    var viewerOpen by remember(att.id) { mutableStateOf(false) }

    LaunchedEffect(att.id, retryTick) {
        if (bmp == null) {
            failed = false
            io { decodeSampled(api.attachmentBytes(att.id, preferFull = false), 1080) }
                .onSuccess { thumbCache[att.id] = it; bmp = it }
                .onFailure { failed = true }
        }
    }

    val shape = RoundedCornerShape(12.dp)
    when {
        bmp != null -> {
            Image(
                bmp!!, att.name,
                Modifier
                    .widthIn(max = 240.dp)
                    .heightIn(max = 320.dp)
                    .clip(shape)
                    .border(0.6.dp, Brand.Border, shape)
                    .clickable { viewerOpen = true },
                contentScale = ContentScale.Fit
            )
        }
        failed -> {
            Row(
                Modifier
                    .clip(shape)
                    .background(Brand.Surface)
                    .border(0.6.dp, Brand.Border, shape)
                    .clickable { retryTick++ }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("图片加载失败 · 点击重试", fontSize = 12.sp, color = Brand.Muted)
            }
        }
        else -> {
            Box(
                Modifier.size(150.dp, 108.dp).clip(shape).background(Brand.Surface),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Brand.Lavender)
            }
        }
    }

    if (viewerOpen) {
        FullscreenImageDialog(api = api, att = att, fallback = bmp, onDismiss = { viewerOpen = false })
    }
}

/** 发送框里本地待发图片（Uri）的全屏高清预览弹窗，支持双指缩放与双击放大。 */
@Composable
fun LocalImagePreviewDialog(uri: Uri, title: String, fallback: ImageBitmap?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var fullBmp by remember(uri) { mutableStateOf(fallback) }

    LaunchedEffect(uri) {
        io {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { decodeSampled(it, 2160) }
        }.onSuccess { if (it != null) fullBmp = it }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ZoomableImageOverlay(bitmap = fullBmp, title = title, onDismiss = onDismiss)
    }
}

/** 悬浮窗内部使用的全屏高清图片预览层（不弹 Dialog，避免悬浮窗 Context 无 Activity Token 崩溃）。 */
@Composable
fun LocalFileImageOverlay(file: File, title: String, onDismiss: () -> Unit) {
    var fullBmp by remember(file.absolutePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file.absolutePath) {
        io { decodeSampledFile(file, 2160) }.onSuccess { fullBmp = it }
    }

    ZoomableImageOverlay(bitmap = fullBmp, title = title, onDismiss = onDismiss)
}

@Composable
private fun FullscreenImageDialog(api: Api, att: Att, fallback: ImageBitmap?, onDismiss: () -> Unit) {
    var fullBmp by remember(att.id) { mutableStateOf(fullCache[att.id] ?: fallback) }

    LaunchedEffect(att.id) {
        if (fullCache[att.id] == null) {
            io { decodeSampled(api.attachmentBytes(att.id, preferFull = true), 2160) }
                .onSuccess { fullCache[att.id] = it; fullBmp = it }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ZoomableImageOverlay(bitmap = fullBmp, title = att.name, onDismiss = onDismiss)
    }
}

@Composable
private fun ZoomableImageOverlay(bitmap: ImageBitmap?, title: String, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF208090C))
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.2f) { scale = 1f; offset = Offset.Zero } else scale = 2.4f
                    },
                    onTap = { if (scale <= 1.05f) onDismiss() }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 4.5f)
                    scale = next
                    offset = if (next > 1f) offset + pan else Offset.Zero
                }
            }
    ) {
        if (bitmap != null) {
            Image(
                bitmap, title,
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(26.dp),
                strokeWidth = 2.2.dp,
                color = Color.White
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = Color(0xFFD5DAE6),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x66000000))
            ) {
                Icon(Icons.Default.Close, "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
