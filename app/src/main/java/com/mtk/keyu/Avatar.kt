package com.mtk.keyu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 真实头像二级缓存加载器（内存 + 磁盘），支持：
 * - /api/avatars/... 相对路径（自动附带鉴权）
 * - https:// 外链图片
 * - emoji:🍀 表情头像
 * - 无头像时极简单色首字兜底（绝不五彩斑斓）
 */
object AvatarLoader {
    private val mem = ConcurrentHashMap<String, Bitmap>()

    fun isEmoji(url: String?): Boolean = !url.isNullOrBlank() && url.startsWith("emoji:")
    fun emojiOf(url: String): String = url.removePrefix("emoji:").trim()

    fun cached(url: String?): Bitmap? = if (url.isNullOrBlank() || isEmoji(url)) null else mem[url]

    private fun cacheFile(ctx: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }.take(24)
        val dir = File(ctx.cacheDir, "avatars").apply { mkdirs() }
        return File(dir, "$hex.img")
    }

    private fun decodeDownsampled(bytes: ByteArray, maxPx: Int = 256): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxPx && bounds.outHeight / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    suspend fun load(ctx: Context, api: Api, url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank() || isEmoji(url)) return@withContext null
        mem[url]?.let { return@withContext it }
        val file = cacheFile(ctx, url)
        if (file.exists() && file.length() > 32) {
            decodeDownsampled(file.readBytes())?.let {
                mem[url] = it
                return@withContext it
            }
        }
        val bytes = runCatching { api.avatarBytes(url) }.getOrNull() ?: return@withContext null
        val bmp = decodeDownsampled(bytes) ?: return@withContext null
        runCatching { file.writeBytes(bytes) }
        mem[url] = bmp
        bmp
    }
}

/**
 * 全站统一真实头像组件（智能体与用户共用）：
 * 优先渲染真实图片头像；其次 emoji: 头像；无头像时采用克制单色极客风格，绝不使用彩虹杂色。
 */
@Composable
fun Avatar(
    name: String,
    avatarUrl: String?,
    size: Dp = 36.dp,
    darkSurface: Boolean = false,
    showOnlineDot: Boolean = false,
    onlineDotBorder: Color = if (darkSurface) Color(0xFF171920) else Color.White,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val url = avatarUrl.orEmpty().trim()
    val isEmoji = AvatarLoader.isEmoji(url)
    var bmp by remember(url) { mutableStateOf(AvatarLoader.cached(url)) }

    LaunchedEffect(url) {
        if (url.isNotEmpty() && !isEmoji && bmp == null) {
            val api = Api(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE))
            AvatarLoader.load(ctx, api, url)?.let { bmp = it }
        }
    }

    val fallbackBg = if (darkSurface) Color(0xFF232732) else Color(0xFFEEEFF3)
    val fallbackFg = if (darkSurface) Color(0xFFE6E9F0) else Brand.Ink
    val ringColor = if (darkSurface) Color(0x26FFFFFF) else Color(0x14000000)

    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(fallbackBg)
                .border(0.6.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            when {
                bmp != null -> {
                    Image(
                        bitmap = bmp!!.asImageBitmap(),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                isEmoji -> {
                    Text(
                        AvatarLoader.emojiOf(url),
                        fontSize = (size.value * 0.50f).sp
                    )
                }
                else -> {
                    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
                    Text(
                        letter,
                        color = fallbackFg,
                        fontSize = (size.value * 0.42f).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        if (showOnlineDot) {
            val dotSize = (size.value * 0.25f).coerceIn(7.5f, 10.5f).dp
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(Color(0xFF28C76F))
                    .border(1.5.dp, onlineDotBorder, CircleShape)
            )
        }
    }
}
