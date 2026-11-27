package com.mtk.keyu

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 富内容里的一张图 / 一段视频：url 可以是站内附件地址（/api/attachments/<id>/download）或外链。 */
data class MediaRef(val url: String, val name: String)

private val IMAGE_EXT = Regex("\\.(png|jpe?g|gif|webp|bmp|heic|heif|avif)(\\?.*)?$", RegexOption.IGNORE_CASE)
private val VIDEO_EXT = Regex("\\.(mp4|mov|m4v|webm|3gp|mkv)(\\?.*)?$", RegexOption.IGNORE_CASE)

fun looksLikeImage(nameOrUrl: String) = IMAGE_EXT.containsMatchIn(nameOrUrl.substringBefore('#'))
fun looksLikeVideo(nameOrUrl: String) = VIDEO_EXT.containsMatchIn(nameOrUrl.substringBefore('#'))
fun Att.isVideo() = type.startsWith("video/") || looksLikeVideo(name)
fun Att.toMediaRef() = MediaRef("/api/attachments/$id/download", name)

private val galleryThumbs = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 80
}
private val galleryFull = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 6
}
private val videoPosters = mutableMapOf<String, ImageBitmap?>()

private fun decodeBytes(bytes: ByteArray, maxSide: Int): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap() ?: error("无法解析图片")
}

/** 带缓存的图片加载状态。 */
@Composable
private fun rememberMediaBitmap(api: Api, url: String, full: Boolean): Pair<ImageBitmap?, Boolean> {
    val cache = if (full) galleryFull else galleryThumbs
    var bmp by remember(url, full) { mutableStateOf(cache[url] ?: if (full) galleryThumbs[url] else null) }
    var failed by remember(url, full) { mutableStateOf(false) }
    LaunchedEffect(url, full) {
        if (cache[url] == null) {
            io { decodeBytes(api.mediaBytes(url, preferFull = full), if (full) 2400 else 900) }
                .onSuccess { cache[url] = it; bmp = it }
                .onFailure { if (bmp == null) failed = true }
        }
    }
    return bmp to failed
}

/**
 * 消息里的图片组（仿 Codex / 微信）：1 张按原比例大图，2 张并排，4 张 2×2，其余 3 列九宫格，
 * 超过 9 张在最后一格显示 +N；点开进入可左右滑动、双指缩放的全屏浏览。
 */
@Composable
fun MediaGallery(api: Api, items: List<MediaRef>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    var viewerStart by remember(items) { mutableStateOf<Int?>(null) }
    val shape = RoundedCornerShape(10.dp)
    if (items.size == 1) {
        GalleryCell(api, items[0], Modifier.widthIn(max = 260.dp).heightIn(min = 60.dp, max = 340.dp), shape, single = true) { viewerStart = 0 }
    } else {
        val cols = when (items.size) { 2, 4 -> 2; else -> 3 }
        val cell: Dp = if (cols == 2) 128.dp else 88.dp
        val shown = items.take(9)
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.chunked(cols).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { c, ref ->
                        val idx = r * cols + c
                        Box {
                            GalleryCell(api, ref, Modifier.size(cell), shape, single = false) { viewerStart = idx }
                            if (idx == 8 && items.size > 9) {
                                Box(
                                    Modifier.matchParentSize().clip(shape).background(Color(0x88000000)),
                                    contentAlignment = Alignment.Center
                                ) { Text("+${items.size - 9}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
        }
    }
    viewerStart?.let { start ->
        GalleryViewer(api, items, start) { viewerStart = null }
    }
}

@Composable
private fun GalleryCell(api: Api, ref: MediaRef, modifier: Modifier, shape: RoundedCornerShape, single: Boolean, onClick: () -> Unit) {
    val (bmp, failed) = rememberMediaBitmap(api, ref.url, full = false)
    when {
        bmp != null -> Image(
            bmp, ref.name,
            modifier
                .then(if (single) Modifier.aspectRatio((bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.4f, 3f), matchHeightConstraintsFirst = false) else Modifier)
                .clip(shape)
                .border(0.6.dp, Brand.Border, shape)
                .clickable(onClick = onClick),
            contentScale = if (single) ContentScale.Fit else ContentScale.Crop
        )
        failed -> Box(
            (if (single) Modifier.size(180.dp, 110.dp) else modifier).clip(shape).background(Brand.Surface).border(0.6.dp, Brand.Border, shape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Text("图片加载失败", fontSize = 11.5.sp, color = Brand.Muted) }
        else -> Box(
            (if (single) Modifier.size(180.dp, 120.dp) else modifier).clip(shape).background(Brand.Surface),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Brand.Lavender) }
    }
}

/** 全屏图片浏览：左右滑动切换、双指缩放（放大后单指拖动）、双击放大/还原、单击退出。 */
@Composable
fun GalleryViewer(api: Api, items: List<MediaRef>, start: Int, onDismiss: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF508090C))) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomPage(api, items[page], onZoomChange = { if (page == pager.currentPage) zoomed = it }, onTap = onDismiss)
            }
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                    .statusBarsPadding().padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    if (items.size > 1) Text("${pager.currentPage + 1} / ${items.size}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(items[pager.currentPage].name, color = Color(0xFFBFC5D2), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x66000000))) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ZoomPage(api: Api, ref: MediaRef, onZoomChange: (Boolean) -> Unit, onTap: () -> Unit) {
    val (bmp, failed) = rememberMediaBitmap(api, ref.url, full = true)
    var scale by remember(ref.url) { mutableFloatStateOf(1f) }
    var offset by remember(ref.url) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1.01f) { onZoomChange(scale > 1.01f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(ref.url) {
                detectTapGestures(
                    onDoubleTap = { if (scale > 1.2f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f },
                    onTap = { if (scale <= 1.05f) onTap() }
                )
            }
            .pointerInput(ref.url) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val multi = ev.changes.count { it.pressed } > 1
                        if (multi || scale > 1.01f) {
                            scale = (scale * ev.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (scale > 1.01f) offset + ev.calculatePan() else Offset.Zero
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            bmp != null -> Image(
                bmp, ref.name,
                Modifier.fillMaxSize().padding(8.dp).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit
            )
            failed -> Text("图片加载失败", color = Color(0xFFBFC5D2), fontSize = 13.sp)
            else -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.2.dp, color = Color.White)
        }
    }
}

/** 视频卡片：首帧封面 + 播放键 + 时长，点开全屏播放（系统播放器内核，支持拖动进度）。 */
@Composable
fun VideoCard(api: Api, ref: MediaRef, modifier: Modifier = Modifier) {
    var poster by remember(ref.url) { mutableStateOf(videoPosters[ref.url]) }
    var duration by remember(ref.url) { mutableStateOf("") }
    var playing by remember(ref.url) { mutableStateOf(false) }
    LaunchedEffect(ref.url) {
        if (!videoPosters.containsKey(ref.url)) {
            io {
                val (u, h) = api.resolveMedia(ref.url)
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(u, h)
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val frame = r.getFrameAtTime(0)?.let { bm ->
                        val scale = 720f / maxOf(bm.width, bm.height).coerceAtLeast(1)
                        (if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bm, (bm.width * scale).toInt(), (bm.height * scale).toInt(), true) else bm).asImageBitmap()
                    }
                    frame to ms
                } finally { runCatching { r.release() } }
            }.onSuccess { (f, ms) ->
                videoPosters[ref.url] = f; poster = f
                if (ms > 0) duration = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
            }.onFailure { videoPosters[ref.url] = null }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val ratio = poster?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.56f, 1.9f) } ?: (16f / 9f)
    Box(
        modifier
            .then(if (ratio < 1f) Modifier.height(240.dp).aspectRatio(ratio, matchHeightConstraintsFirst = true) else Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(ratio))
            .clip(shape)
            .background(Color(0xFF15171C))
            .clickable { playing = true }
    ) {
        poster?.let { Image(it, ref.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(
            Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(Color(0x99000000)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(30.dp)) }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(ref.name, color = Color.White, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (duration.isNotEmpty()) Text(duration, color = Color.White, fontSize = 11.5.sp)
        }
    }
    if (playing) VideoPlayerDialog(api, ref) { playing = false }
}

@Composable
fun VideoPlayerDialog(api: Api, ref: MediaRef, onDismiss: () -> Unit) {
    var source by remember(ref.url) { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    var ready by remember(ref.url) { mutableStateOf(false) }
    var error by remember(ref.url) { mutableStateOf<String?>(null) }
    LaunchedEffect(ref.url) { io { api.resolveMedia(ref.url) }.onSuccess { source = it }.onFailure { error = "视频地址获取失败" } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            source?.let { (u, h) ->
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            val mc = MediaController(c)
                            mc.setAnchorView(this)
                            setMediaController(mc)
                            setOnPreparedListener { mp -> ready = true; mp.isLooping = false; start(); mc.show(2500) }
                            setOnErrorListener { _, _, _ -> error = "视频无法播放"; true }
                            setVideoURI(Uri.parse(u), h)
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                )
            }
            if (!ready && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.4.dp, color = Color.White)
            error?.let { Text(it, color = Color(0xFFBFC5D2), fontSize = 13.sp, modifier = Modifier.align(Alignment.Center)) }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(ref.name, color = Color(0xFFD5DAE6), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x66000000))) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
