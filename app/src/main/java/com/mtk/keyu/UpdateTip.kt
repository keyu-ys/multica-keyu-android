package com.mtk.keyu

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 消息区顶部的一行更新提示（与侧边栏下载图标并存）：
 * - 发现新版本时出现：同一版本 24 小时内最多出现一次，有更新的版本立刻再提示；
 * - 不常驻：15 秒内没碰会自动收起，点 × 也会收起，都记为本周期已提醒；
 * - 点「更新」原地显示下载进度，下完自动校验并拉起系统安装（复用 AutoUpdater）。
 */
private const val TIP_PERIOD_MS = 24 * 60 * 60 * 1000L
private const val TIP_AUTO_HIDE_MS = 15_000L

@Composable
fun UpdateTip(api: Api, prefs: SharedPreferences) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ota = AppState.otaState.value
    val manifest = ota.manifest
    var visible by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    val busy = ota.phase in setOf(AppState.OtaPhase.DOWNLOADING, AppState.OtaPhase.VERIFYING, AppState.OtaPhase.INSTALLING)

    // 触发：拿到可用新版本且本周期还没提醒过
    LaunchedEffect(manifest?.versionCode, ota.phase) {
        if (manifest == null || ota.phase != AppState.OtaPhase.AVAILABLE || visible) return@LaunchedEffect
        val lastVer = prefs.getLong("ota_tip_ver", 0L)
        val lastAt = prefs.getLong("ota_tip_at", 0L)
        val now = System.currentTimeMillis()
        if (manifest.versionCode != lastVer || now - lastAt >= TIP_PERIOD_MS) {
            prefs.edit().putLong("ota_tip_ver", manifest.versionCode).putLong("ota_tip_at", now).apply()
            touched = false
            visible = true
        }
    }
    // 不常驻：没操作就自动收起；下载中不收
    LaunchedEffect(visible, touched, busy) {
        if (visible && !touched && !busy) {
            delay(TIP_AUTO_HIDE_MS)
            if (!touched && AppState.otaState.value.phase == AppState.OtaPhase.AVAILABLE) visible = false
        }
    }
    // 已是最新 / 已安装后自动消失
    LaunchedEffect(ota.phase) {
        if (ota.phase == AppState.OtaPhase.IDLE || ota.phase == AppState.OtaPhase.UP_TO_DATE) visible = false
    }

    AnimatedVisibility(
        visible = visible && manifest != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val m = manifest ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                .clip(shape)
                .background(Brand.LavenderSoft)
                .border(0.6.dp, Color(0xFFE1E6F5), shape)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Brand.Navy))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (ota.phase) {
                        AppState.OtaPhase.DOWNLOADING -> "正在下载 ${verLabel(m.versionName)}…"
                        AppState.OtaPhase.VERIFYING -> "正在校验安装包…"
                        AppState.OtaPhase.INSTALLING -> "正在安装 ${verLabel(m.versionName)}…"
                        AppState.OtaPhase.ERROR -> ota.message.ifEmpty { "更新失败" } + "，点更新重试"
                        else -> "新版本 ${verLabel(m.versionName)}" + (m.notes.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    },
                    fontSize = 12.5.sp,
                    color = if (ota.phase == AppState.OtaPhase.ERROR) Brand.Danger else Brand.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!busy) {
                    Text(
                        "更新",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brand.Navy)
                            .clickable {
                                touched = true
                                scope.launch { AutoUpdater.downloadAndInstall(ctx, api, m) }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).clickable { visible = false },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Close, "收起", tint = Brand.Subtle, modifier = Modifier.size(15.dp)) }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
            }
            if (ota.phase == AppState.OtaPhase.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { ota.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Brand.Navy,
                    trackColor = Color(0xFFE1E6F5)
                )
            }
        }
    }
}

