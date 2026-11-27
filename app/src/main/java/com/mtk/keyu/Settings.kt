package com.mtk.keyu

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 极客纯净分组设置页：单色细线图标、零花哨底盒、真实用户头像、文字极度克制。 */
@Composable
fun SettingsScreen(
    api: Api,
    prefs: android.content.SharedPreferences,
    notice: String,
    control: Control,
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    val appVersion = remember { AutoUpdater.currentVersionName(ctx) }
    val otaState = AppState.otaState.value

    var server by remember { mutableStateOf(api.server) }
    var slug by remember { mutableStateOf(api.slug) }
    var email by remember { mutableStateOf(prefs.getString("email", "")!!) }
    var code by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(api.loggedIn) }
    var result by remember { mutableStateOf(notice) }
    var resultError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var cooldown by remember { mutableIntStateOf(0) }
    var showServerConfig by remember { mutableStateOf(!api.loggedIn && (api.server.isBlank() || api.slug.isBlank())) }
    var autoInstall by remember { mutableStateOf(prefs.getBoolean("auto_install", false)) }
    var bubbleAlert by remember { mutableStateOf(prefs.getBoolean("bubble_alert", true)) }

    var permTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val overlayOk = remember(permTick) { AndroidSettings.canDrawOverlays(ctx) }
    val notifOk = remember(permTick) { ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    val installOk = remember(permTick) { Installer.canInstall(ctx) }
    val batteryOk = remember(permTick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }

    LaunchedEffect(cooldown) { if (cooldown > 0) { delay(1000); cooldown-- } }

    val me = AppState.meProfile.value
    val userName = me?.name?.ifEmpty { email.substringBefore("@") } ?: email.substringBefore("@")
    val userAvatar = me?.avatarUrl ?: prefs.getString("user_avatar", "").orEmpty()

    fun saveServer() = prefs.edit().putString("server", server).putString("slug", slug).putString("email", email).apply()
    fun runTask(label: String, block: () -> String) {
        busy = true; result = label; resultError = false
        scope.launch {
            io(block).onSuccess { result = it; resultError = false }
                .onFailure {
                    if (it is AuthExpired) { prefs.edit().remove("token").apply(); loggedIn = false }
                    result = it.message ?: "请求失败"; resultError = true
                }
            busy = false
        }
    }

    if (!loggedIn) {
        // 极简纯净独立登录页：纯白画布、自定义等高输入框、零错位对齐、低干扰服务器折叠项
        var emailFocused by remember { mutableStateOf(false) }
        var codeFocused by remember { mutableStateOf(false) }
        val emailValid = email.contains("@") && email.substringAfter("@").contains(".")
        val serverValid = (server.startsWith("http://") || server.startsWith("https://")) && slug.isNotBlank()
        val canSendCode = !busy && cooldown == 0 && emailValid && serverValid
        val canLogin = !busy && code.isNotEmpty() && emailValid && serverValid

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismissKeyboard() }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(76.dp))

            Image(
                painterResource(R.drawable.logo),
                contentDescription = "Multica",
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .border(0.6.dp, Brand.Border, RoundedCornerShape(15.dp))
            )
            Spacer(Modifier.height(14.dp))
            Text("Multica", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Brand.Ink)
            Spacer(Modifier.height(4.dp))
            Text("使用工作邮箱验证码登录", fontSize = 13.5.sp, color = Brand.Muted)

            Spacer(Modifier.height(34.dp))

            // 邮箱输入框（精确 50dp 等高，无 Material 浮动标签错位）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (emailFocused) Color.White else Brand.Surface)
                    .border(
                        width = if (emailFocused) 1.2.dp else 0.8.dp,
                        color = if (emailFocused) Brand.Ink else Brand.Border,
                        shape = RoundedCornerShape(13.dp)
                    )
                    .padding(horizontal = 15.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (email.isEmpty()) {
                    Text("工作邮箱", fontSize = 15.sp, color = Brand.Subtle)
                }
                BasicTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    textStyle = TextStyle(fontSize = 15.sp, color = Brand.Ink),
                    cursorBrush = SolidColor(Brand.Ink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { emailFocused = it.isFocused }
                )
            }

            Spacer(Modifier.height(12.dp))

            // 验证码 + 获取验证码 一体化 50dp 行（上下边缘与基线完全像素级对齐）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (codeFocused) Color.White else Brand.Surface)
                    .border(
                        width = if (codeFocused) 1.2.dp else 0.8.dp,
                        color = if (codeFocused) Brand.Ink else Brand.Border,
                        shape = RoundedCornerShape(13.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 15.dp, end = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (code.isEmpty()) {
                        Text("验证码", fontSize = 15.sp, color = Brand.Subtle)
                    }
                    BasicTextField(
                        value = code,
                        onValueChange = { code = it.trim() },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 15.sp, color = Brand.Ink),
                        cursorBrush = SolidColor(Brand.Ink),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { codeFocused = it.isFocused }
                    )
                }
                Box(Modifier.width(0.8.dp).height(20.dp).background(Brand.Border))
                Box(
                    Modifier
                        .widthIn(min = 104.dp)
                        .fillMaxHeight()
                        .clickable(enabled = canSendCode) {
                            saveServer()
                            cooldown = 60
                            runTask("发送中…") { api.sendCode(email); "验证码已发送，请查收邮箱" }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (cooldown > 0) "${cooldown}s 后重试" else "获取验证码",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            cooldown > 0 -> Brand.Subtle
                            canSendCode -> Brand.Navy
                            else -> Brand.Subtle
                        },
                        maxLines = 1
                    )
                }
            }

            // 紧贴表单的内联状态提示（成功/错误清晰反馈）
            if (result.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    result,
                    fontSize = 12.5.sp,
                    color = when {
                        resultError -> Brand.Danger
                        result.contains("已发送") || result.contains("已保存") -> Brand.Success
                        else -> Brand.Muted
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                )
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.height(20.dp))
            }

            // 主登录按钮（与输入框严格等宽等高 50dp）
            Button(
                enabled = canLogin,
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Ink,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFE7E9EF),
                    disabledContentColor = Brand.Subtle
                ),
                onClick = {
                    saveServer()
                    busy = true; result = "登录中…"; resultError = false
                    scope.launch {
                        io { api.verify(email, code); api.meProfile() }
                            .onSuccess { loggedIn = true; result = ""; onDone() }
                            .onFailure { result = it.message ?: "登录失败"; resultError = true }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (busy && result == "登录中…") {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("登录", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(22.dp))

            // 低干扰服务器配置入口（居中单行，点击再展开）
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showServerConfig = !showServerConfig }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppIcons.ServerLink(Brand.Subtle, 14.dp)
                val serverSummary = if (server.isBlank() || slug.isBlank()) {
                    "请配置 Multica 服务器与工作区"
                } else {
                    "${server.removePrefix("https://").removePrefix("http://")} · $slug"
                }
                Text(
                    serverSummary,
                    fontSize = 12.5.sp,
                    color = Brand.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (showServerConfig) "收起" else "修改",
                    fontSize = 12.5.sp,
                    color = Brand.Navy,
                    fontWeight = FontWeight.Medium
                )
            }

            AnimatedVisibility(visible = showServerConfig) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brand.SurfaceAlt)
                        .border(0.7.dp, Brand.Border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactFlatField(label = "服务器地址", value = server, onChange = { server = it.trim() })
                    CompactFlatField(label = "工作区", value = slug, onChange = { slug = it.trim() })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { saveServer(); showServerConfig = false; result = "服务器配置已保存"; resultError = false },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) { Text("保存", fontSize = 13.5.sp) }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF6F7FA))) {
        // 极简顶栏
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { saveServer(); onDone() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Brand.Ink)
            }
            Text(
                "设置",
                fontSize = 17.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.Ink,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (otaState.manifest != null && otaState.phase == AppState.OtaPhase.AVAILABLE)
                    "更新 ${verLabel(otaState.manifest.versionName)}" else verLabel(appVersion),
                fontSize = 12.sp,
                color = if (otaState.manifest != null && otaState.phase == AppState.OtaPhase.AVAILABLE) Brand.Navy else Brand.Subtle,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !busy) {
                        scope.launch {
                            val m = otaState.manifest
                            if (m != null && otaState.phase in setOf(AppState.OtaPhase.AVAILABLE, AppState.OtaPhase.ERROR)) {
                                AutoUpdater.downloadAndInstall(ctx, api, m)
                            } else {
                                result = AutoUpdater.check(ctx, api, prefs, manual = true)
                                resultError = false
                            }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        HorizontalDivider(color = Brand.Border, thickness = 0.5.dp)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            run {
                // 个人账号行（真实头像 + 姓名 + 邮箱 + 极简检测）
                SettingsGroup {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = userName, avatarUrl = userAvatar, size = 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(userName, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink)
                            Spacer(Modifier.height(1.dp))
                            Text(email, fontSize = 12.5.sp, color = Brand.Muted)
                        }
                        Text(
                            if (busy) "检查中" else "检查连接",
                            fontSize = 12.5.sp,
                            color = Brand.Navy,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brand.LavenderSoft)
                                .clickable(enabled = !busy) {
                                    saveServer()
                                    runTask("检查连接…") {
                                        api.refresh()
                                        val p = api.meProfile()
                                        "已连接 · ${p.name} · ${api.agents().size} 个智能体"
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                // 悬浮与提醒（单色细线图标 + 一行超短副标）
                GroupTitle("功能")
                SettingsGroup {
                    FeatureRow(
                        icon = { AppIcons.FloatOrb(Brand.InkSoft, filledCenter = control.bubble, size = 19.dp) },
                        title = "悬浮助手",
                        sub = "点球截屏圈注",
                        checked = control.bubble
                    ) { control.toggleBubble(it) }
                    GroupDivider()
                    FeatureRow(
                        icon = { AppIcons.Bell(Brand.InkSoft, 19.dp) },
                        title = "后台通知",
                        sub = "离线接收新回复",
                        checked = control.notif
                    ) { control.toggleNotif(it) }
                    GroupDivider()
                    FeatureRow(
                        icon = { AppIcons.BubbleChat(Brand.InkSoft, 19.dp) },
                        title = "完成气泡",
                        sub = "回复后悬浮提醒",
                        checked = bubbleAlert
                    ) { on ->
                        bubbleAlert = on
                        prefs.edit().putBoolean("bubble_alert", on).apply()
                    }
                    GroupDivider()
                    FeatureRow(
                        icon = { AppIcons.PackageBox(Brand.InkSoft, 19.dp) },
                        title = "自动更新聊天安装包",
                        sub = "仅本机已装且同签名的 APK",
                        checked = autoInstall
                    ) { on ->
                        if (on && !Installer.canInstall(ctx)) {
                            Installer.openInstallPermissionSettings(ctx)
                            result = "请先开启安装应用权限"
                            resultError = false
                        } else {
                            autoInstall = on
                            prefs.edit().putBoolean("auto_install", on).apply()
                        }
                    }
                }

                // 系统权限（极简纯单行列表）
                GroupTitle("权限")
                SettingsGroup {
                    PermLine(
                        icon = { AppIcons.FloatOrb(Brand.InkSoft, filledCenter = false, size = 18.dp) },
                        title = "悬浮窗",
                        granted = overlayOk
                    ) {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                    GroupDivider()
                    PermLine(
                        icon = { AppIcons.Bell(Brand.InkSoft, 18.dp) },
                        title = "系统通知",
                        granted = notifOk
                    ) {
                        val i = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                        ctx.startActivity(i)
                    }
                    GroupDivider()
                    PermLine(
                        icon = { AppIcons.ShieldCheck(Brand.InkSoft, 18.dp) },
                        title = "安装应用",
                        granted = installOk
                    ) { Installer.openInstallPermissionSettings(ctx) }
                    GroupDivider()
                    PermLine(
                        icon = { AppIcons.Bolt(Brand.InkSoft, 18.dp) },
                        title = "后台保活",
                        granted = batteryOk
                    ) {
                        runCatching {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                        }.onFailure {
                            runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        }
                    }
                }
            }

            // 服务器（单行折叠）
            GroupTitle("服务器")
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().clickable { showServerConfig = !showServerConfig },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcons.ServerLink(Brand.InkSoft, 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${server.removePrefix("https://").removePrefix("http://")} · $slug",
                        fontSize = 14.sp,
                        color = Brand.Ink,
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (showServerConfig) "收起" else "修改", fontSize = 13.sp, color = Brand.Muted)
                }
                AnimatedVisibility(visible = showServerConfig) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it.trim() },
                            label = { Text("服务器地址") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = slug,
                            onValueChange = { slug = it.trim() },
                            label = { Text("工作区") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { saveServer(); showServerConfig = false; result = "已保存"; resultError = false },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("保存") }
                    }
                }
            }

            if (result.isNotEmpty()) {
                Text(
                    result,
                    fontSize = 12.5.sp,
                    color = if (resultError) Brand.Danger else Brand.Muted,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }

            if (loggedIn) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(0.6.dp, Brand.Border, RoundedCornerShape(16.dp))
                        .clickable(enabled = !busy) {
                            control.toggleNotif(false)
                            control.toggleBubble(false)
                            prefs.edit().remove("token").apply()
                            loggedIn = false; code = ""; result = ""
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("退出登录", fontSize = 14.5.sp, color = Brand.Danger, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CompactFlatField(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = Brand.Muted, fontWeight = FontWeight.Medium)
        Box(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .border(0.7.dp, Brand.Border, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Brand.Ink),
                cursorBrush = SolidColor(Brand.Ink),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Brand.Muted,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(0.6.dp, Brand.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp),
        content = content
    )
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(color = Brand.Border, thickness = 0.5.dp, modifier = Modifier.padding(start = 31.dp, top = 11.dp, bottom = 11.dp))
}

@Composable
private fun FeatureRow(
    icon: @Composable () -> Unit,
    title: String,
    sub: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Brand.Ink)
            Text(sub, fontSize = 12.sp, color = Brand.Muted, maxLines = 1)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Brand.Ink,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFD9DDE6),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun PermLine(
    icon: @Composable () -> Unit,
    title: String,
    granted: Boolean,
    onFix: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !granted, onClick = onFix).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Brand.Ink, modifier = Modifier.weight(1f))
        if (granted) {
            Text("已开启", fontSize = 13.sp, color = Brand.Subtle)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("去开启", fontSize = 13.sp, color = Brand.Navy, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(2.dp))
                AppIcons.ChevronRight(Brand.Navy, 16.dp)
            }
        }
    }
}
