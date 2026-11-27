package com.mtk.keyu

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 极客极简悬浮交互体系：
 * 1. showHub：紧致深色玻璃控制面板（真实智能体头像 + 续聊/新会话目标直显 + 一键停止 + 切换智能体/会话）
 * 2. showSendSheet：纯净白色底部确认 Sheet（发前一键切换续聊已有会话或新建会话 + 缩略图 + 极简开关）
 * 3. showCompletion：完成后悬浮提醒卡片
 * 4. showBanner：顶部灵动岛风格暗色状态条
 */
object FloatPanelOverlay {
    private var view: View? = null
    private var owner: OverlayLifecycleOwner? = null
    private var bannerView: View? = null
    private var bannerOwner: OverlayLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showHub(ctx: Context, anchorRight: Boolean, anchorYRatio: Float, onCapture: () -> Unit, onStopBubble: () -> Unit) {
        mainHandler.post {
            showInternal(ctx, hideBall = true) { close ->
                FloatHubContent(
                    anchorRight = anchorRight,
                    anchorYRatio = anchorYRatio,
                    onClose = close,
                    onCapture = { close(); onCapture() },
                    onWriteNote = { close(); AnnotateOverlay.show(ctx.applicationContext, null) },
                    onOpenSendSheet = {
                        close()
                        showSendSheet(ctx, onOpenHubAfterCancel = {
                            showHub(ctx, anchorRight, anchorYRatio, onCapture, onStopBubble)
                        })
                    },
                    onOpenApp = { sid ->
                        openMainApp(ctx, sid)
                        mainHandler.postDelayed({ close() }, 120)
                    },
                    onStopBubble = { close(); onStopBubble() }
                )
            }
        }
    }

    fun showSendSheet(ctx: Context, onOpenHubAfterCancel: (() -> Unit)? = null) {
        mainHandler.post {
            showInternal(ctx, hideBall = true) { close ->
                SendConfirmSheet(
                    onClose = {
                        close()
                        onOpenHubAfterCancel?.invoke()
                    },
                    onSent = { agentName, count ->
                        close()
                        showBanner(ctx, "$agentName 已接手", "已发送 $count 条")
                    }
                )
            }
        }
    }

    fun showCompletion(ctx: Context, notice: AppState.CompletionNotice) {
        mainHandler.post {
            showInternal(ctx, hideBall = false) { close ->
                CompletionBubbleOverlay(
                    notice = notice,
                    onLater = {
                        AppState.completionNotice.value = null
                        AppState.notifyBubble()
                        close()
                    },
                    onInspect = {
                        AppState.completionNotice.value = null
                        AppState.notifyBubble()
                        if (notice.installedPkg != null && Installer.launchApp(ctx, notice.installedPkg)) {
                            close()
                        } else {
                            openMainApp(ctx, notice.sessionId)
                            mainHandler.postDelayed({ close() }, 120)
                        }
                    }
                )
            }
        }
    }

    @SuppressLint("InflateParams")
    fun showBanner(ctx: Context, title: String, subtitle: String) {
        mainHandler.post {
            val wm = ctx.getSystemService(WindowManager::class.java)
            dismissBanner(wm)
            val lo = OverlayLifecycleOwner().also { bannerOwner = it; it.onCreate() }
            val compose = androidx.compose.ui.platform.ComposeView(ctx).apply {
                setViewTreeLifecycleOwner(lo)
                setViewTreeViewModelStoreOwner(lo)
                setViewTreeSavedStateRegistryOwner(lo)
                setContent {
                    BrandTheme {
                        TopStatusBanner(title = title, subtitle = subtitle) { dismissBanner(wm) }
                    }
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            runCatching {
                wm.addView(compose, lp)
                bannerView = compose
                lo.onResume()
            }
        }
    }

    private fun dismissBanner(wm: WindowManager) {
        bannerView?.let { runCatching { wm.removeView(it) } }
        bannerView = null
        bannerOwner?.onDestroy()
        bannerOwner = null
    }

    @SuppressLint("InflateParams")
    private fun showInternal(ctx: Context, hideBall: Boolean, content: @Composable (close: () -> Unit) -> Unit) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        dismiss(wm)
        if (hideBall) AppState.overlayVisible = true

        val lo = OverlayLifecycleOwner().also { owner = it; it.onCreate() }
        val container = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss(wm)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setViewTreeLifecycleOwner(lo)
            setViewTreeViewModelStoreOwner(lo)
            setViewTreeSavedStateRegistryOwner(lo)
        }
        val compose = androidx.compose.ui.platform.ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lo)
            setViewTreeViewModelStoreOwner(lo)
            setViewTreeSavedStateRegistryOwner(lo)
            setContent { BrandTheme { content { dismiss(wm) } } }
        }
        container.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching {
            wm.addView(container, lp)
            view = container
            lo.onResume()
        }
    }

    fun dismiss(wm: WindowManager) {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        owner?.onDestroy()
        owner = null
        if (AppState.overlayVisible) AppState.overlayVisible = false
    }

    fun dismissAll(ctx: Context) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        dismiss(wm)
        dismissBanner(wm)
    }

    private fun openMainApp(ctx: Context, sessionId: String?) {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!sessionId.isNullOrEmpty()) i.putExtra("open_session", sessionId)
        val started = runCatching { ctx.startActivity(i) }.isSuccess
        if (!started) {
            runCatching { Notify.openAppIntent(ctx, sessionId).send() }
        }
    }
}

private enum class HubPage { MAIN, PICK_AGENT, PICK_SESSION }

// ==================== 1. 极简深色玻璃悬浮控制小面板 ====================
@Composable
private fun FloatHubContent(
    anchorRight: Boolean,
    anchorYRatio: Float,
    onClose: () -> Unit,
    onCapture: () -> Unit,
    onWriteNote: () -> Unit,
    onOpenSendSheet: () -> Unit,
    onOpenApp: (sessionId: String?) -> Unit,
    onStopBubble: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val api = remember { Api(prefs) }
    val scope = rememberCoroutineScope()

    var visible by remember { mutableStateOf(false) }
    var agentId by remember { mutableStateOf(prefs.getString("agent", "")!!) }
    var sessionId by remember { mutableStateOf(prefs.getString("current_session", "")!!) }
    var agents by remember { mutableStateOf(AppState.allAgents()) }
    var page by remember { mutableStateOf(if (agentId.isEmpty()) HubPage.PICK_AGENT else HubPage.MAIN) }
    var stopping by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        if (api.loggedIn) {
            io {
                if (agents.isEmpty()) api.agents()
                api.sessions()
                api.pendingTasks()
            }.onSuccess {
                agents = AppState.allAgents()
                // 若当前没有显式选中会话，自动定位到该智能体最近会话
                if (sessionId.isEmpty() && agentId.isNotEmpty()) {
                    val pref = AppState.preferredSessionForAgent(prefs, agentId)
                    if (pref.isNotEmpty()) {
                        sessionId = pref
                        AppState.rememberAgentSession(prefs, agentId, pref)
                    }
                }
            }
        }
    }

    val currentAgent = agents.firstOrNull { it.id == agentId }
    val agentSessions = AppState.sessions.filter { it.agentId == agentId }
    val currentSession = agentSessions.firstOrNull { it.id == sessionId }
    val runningTaskId = if (sessionId.isNotEmpty()) AppState.runningTasks[sessionId] else null
    val queued = Outbox.items.size
    val unread = AppState.unreadCount.intValue

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0x30000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
    ) {
        val topPad = ((maxHeight.value * anchorYRatio) - 104f).coerceIn(72f, (maxHeight.value - 330f).coerceAtLeast(80f)).dp
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.90f),
            exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.93f),
            modifier = Modifier
                .align(if (anchorRight) Alignment.TopEnd else Alignment.TopStart)
                .padding(top = topPad, start = 16.dp, end = 16.dp)
        ) {
            Column(
                Modifier
                    .width(276.dp)
                    .shadow(18.dp, RoundedCornerShape(22.dp))
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xF613151B))
                    .border(0.8.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(13.dp)
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(110)) },
                    label = "hubPage"
                ) { curPage ->
                    when (curPage) {
                        HubPage.PICK_AGENT -> {
                            Column {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    if (agentId.isNotEmpty()) {
                                        IconButton(onClick = { page = HubPage.MAIN }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(17.dp))
                                        }
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text("选择智能体", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(10.dp))
                                val recentOrder = AppState.sessions.map { it.agentId }.distinct()
                                val sorted = remember(agents, agentId, recentOrder.size) {
                                    agents.sortedWith(
                                        compareByDescending<Agent> { it.id == agentId }
                                            .thenBy { val idx = recentOrder.indexOf(it.id); if (idx >= 0) idx else Int.MAX_VALUE }
                                            .thenBy { it.name.lowercase() }
                                    )
                                }
                                LazyColumn(Modifier.heightIn(max = 246.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(sorted, key = { it.id }) { a ->
                                        val sel = a.id == agentId
                                        val latest = AppState.sessions.firstOrNull { it.agentId == a.id }
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(13.dp))
                                                .background(if (sel) Color(0xFF232733) else Color(0xFF1A1D25))
                                                .border(
                                                    width = if (sel) 0.9.dp else 0.5.dp,
                                                    color = if (sel) Color(0x70B2BEE2) else Color(0x14FFFFFF),
                                                    shape = RoundedCornerShape(13.dp)
                                                )
                                                .clickable {
                                                    if (a.id != agentId) {
                                                        agentId = a.id
                                                        val pref = AppState.preferredSessionForAgent(prefs, a.id)
                                                        sessionId = pref
                                                        AppState.rememberAgentSession(prefs, a.id, pref)
                                                        AppState.notifyBubble()
                                                    }
                                                    page = HubPage.MAIN
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Avatar(a.name, a.avatarUrl, size = 32.dp, darkSurface = true)
                                            Spacer(Modifier.width(9.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(a.name, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (latest != null) {
                                                    Text("续聊 · ${latest.title.ifEmpty { "最近会话" }}", color = Color(0xFF7F8696), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                            if (sel) Icon(Icons.Default.Check, null, tint = Brand.Lavender, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }

                        HubPage.PICK_SESSION -> {
                            Column {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { page = HubPage.MAIN }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(17.dp))
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text("发到哪个会话", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                }
                                Spacer(Modifier.height(10.dp))
                                LazyColumn(Modifier.heightIn(max = 246.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item(key = "new_session") {
                                        val isNew = sessionId.isEmpty()
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (isNew) Color(0xFF232733) else Color(0xFF1A1D25))
                                                .border(
                                                    width = if (isNew) 0.9.dp else 0.5.dp,
                                                    color = if (isNew) Color(0x70B2BEE2) else Color(0x14FFFFFF),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .clickable {
                                                    sessionId = ""
                                                    prefs.edit().putString("current_session", "").apply()
                                                    page = HubPage.MAIN
                                                }
                                                .padding(horizontal = 11.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Add, null, tint = Brand.Lavender, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("新建会话", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                            if (isNew) Icon(Icons.Default.Check, null, tint = Brand.Lavender, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                    items(agentSessions, key = { it.id }) { s ->
                                        val sel = s.id == sessionId
                                        val typing = AppState.runningTasks.containsKey(s.id)
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (sel) Color(0xFF232733) else Color(0xFF1A1D25))
                                                .border(
                                                    width = if (sel) 0.9.dp else 0.5.dp,
                                                    color = if (sel) Color(0x70B2BEE2) else Color(0x14FFFFFF),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .clickable {
                                                    sessionId = s.id
                                                    AppState.rememberAgentSession(prefs, agentId, s.id)
                                                    page = HubPage.MAIN
                                                }
                                                .padding(horizontal = 11.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(s.title.ifEmpty { "最近会话" }, color = Color.White, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Spacer(Modifier.height(2.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(timeLabel(s.updatedAt), color = Color(0xFF7F8696), fontSize = 11.sp)
                                                    if (typing) {
                                                        Text(" · ", color = Color(0xFF7F8696), fontSize = 11.sp)
                                                        CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.4.dp, color = Color(0xFF28C76F))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("正在输入…", color = Color(0xFF28C76F), fontSize = 11.sp)
                                                    }
                                                }
                                            }
                                            if (sel) Icon(Icons.Default.Check, null, tint = Brand.Lavender, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                }
                            }
                        }

                        HubPage.MAIN -> {
                            Column {
                                // 顶行：真实头像 + 智能体名 + 切换智能体
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { page = HubPage.PICK_AGENT }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Avatar(currentAgent?.name ?: "M", currentAgent?.avatarUrl, size = 32.dp, darkSurface = true)
                                        Spacer(Modifier.width(9.dp))
                                        Text(
                                            currentAgent?.name ?: "选择智能体",
                                            color = Color.White,
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color(0xFF8C94A6), modifier = Modifier.size(16.dp))
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                // 会话续聊条（点一下直接切已有会话或新建会话；若正在输入可直接一键停止）
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF1B1E27))
                                        .clickable { page = HubPage.PICK_SESSION }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (runningTaskId != null) {
                                        CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.6.dp, color = Color(0xFF28C76F))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            currentSession?.title?.ifEmpty { "正在输入…" } ?: "正在输入…",
                                            color = Color(0xFF28C76F),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (stopping) "…" else "停止",
                                            color = Color.White,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(7.dp))
                                                .background(Color(0xFF313644))
                                                .clickable(enabled = !stopping) {
                                                    stopping = true
                                                    scope.launch {
                                                        io { api.cancelTask(runningTaskId) }.onSuccess {
                                                            AppState.setSessionTask(sessionId, null)
                                                            Toast.makeText(ctx, "已停止", Toast.LENGTH_SHORT).show()
                                                        }.onFailure {
                                                            Toast.makeText(ctx, it.message ?: "停止失败", Toast.LENGTH_SHORT).show()
                                                        }
                                                        stopping = false
                                                    }
                                                }
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    } else {
                                        Text(
                                            if (currentSession != null) "续聊 · ${currentSession.title.ifEmpty { "最近会话" }}" else "新建会话",
                                            color = Color(0xFFB5BCCB),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("切换", color = Color(0xFF7D8599), fontSize = 11.5.sp)
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                // 三宫格极简按钮
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    HubActionTile(
                                        label = "截屏",
                                        highlighted = false,
                                        badge = 0,
                                        modifier = Modifier.weight(1f),
                                        icon = { AppIcons.ScreenshotMark(Color.White, 21.dp) },
                                        onClick = onCapture
                                    )
                                    HubActionTile(
                                        label = "速记",
                                        highlighted = false,
                                        badge = 0,
                                        modifier = Modifier.weight(1f),
                                        icon = { AppIcons.NotePen(Color.White, 21.dp) },
                                        onClick = onWriteNote
                                    )
                                    HubActionTile(
                                        label = "发送",
                                        highlighted = queued > 0,
                                        badge = queued,
                                        modifier = Modifier.weight(1f),
                                        icon = { AppIcons.PaperPlane(if (queued > 0) Brand.Ink else Color.White, 20.dp) },
                                        onClick = { if (queued > 0) onOpenSendSheet() else onWriteNote() }
                                    )
                                }

                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = Color(0x16FFFFFF), thickness = 0.6.dp)
                                Spacer(Modifier.height(7.dp))

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onOpenApp(sessionId.ifEmpty { null }) }
                                            .padding(horizontal = 4.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Text("对话", color = Color(0xFFC5CBD8), fontSize = 12.sp)
                                        if (unread > 0) Box(Modifier.size(6.dp).background(Brand.Lavender, CircleShape))
                                    }
                                    Text(
                                        "关闭",
                                        color = Color(0xFF7B8293),
                                        fontSize = 12.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(onClick = onStopBubble)
                                            .padding(horizontal = 4.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubActionTile(
    label: String,
    highlighted: Boolean,
    badge: Int,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (highlighted) Color.White else Color(0xFF20232D))
                .border(0.6.dp, if (highlighted) Color.White else Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 13.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            icon()
            Text(
                label,
                color = if (highlighted) Brand.Ink else Color(0xFFE6E9F0),
                fontSize = 12.sp,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
        }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(Brand.Navy)
                    .border(1.5.dp, Color(0xFF13151B), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("$badge", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ==================== 2. 纯白底部打包确认 Sheet（发前一键确认续聊哪条会话或新建会话） ====================
@Composable
private fun SendConfirmSheet(
    onClose: () -> Unit,
    onSent: (agentName: String, count: Int) -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val api = remember { Api(prefs) }
    val scope = rememberCoroutineScope()

    var visible by remember { mutableStateOf(false) }
    val agentId = remember { prefs.getString("agent", "")!! }
    var targetSessionId by remember {
        mutableStateOf(
            prefs.getString("current_session", "")!!.ifEmpty {
                AppState.preferredSessionForAgent(prefs, agentId)
            }
        )
    }
    var pickingSession by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        if (AppState.sessions.isEmpty() && api.loggedIn) {
            io { api.sessions() }.onSuccess {
                if (targetSessionId.isEmpty() && agentId.isNotEmpty()) {
                    targetSessionId = AppState.preferredSessionForAgent(prefs, agentId)
                }
            }
        }
    }

    val navBottomDp = remember {
        val resId = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val px = if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else 0
        with(density) { px.toDp() }.coerceAtMost(28.dp)
    }

    val agent = AppState.agent(agentId)
    val agentName = agent?.name ?: "智能体"
    val agentSessions = AppState.sessions.filter { it.agentId == agentId }
    val selectedSession = agentSessions.firstOrNull { it.id == targetSessionId }
    val items = Outbox.items
    var bubbleAlert by remember { mutableStateOf(prefs.getBoolean("bubble_alert", true)) }
    var sending by remember { mutableStateOf(false) }
    var sentDone by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var previewShot by remember { mutableStateOf<Pair<java.io.File, String>?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x55000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (!sending) onClose()
            }
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(160)) { it / 2 } + fadeOut(tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color.White)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = navBottomDp + 16.dp)
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape).background(Color(0xFFDFE2E9))
                )
                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(agentName, agent?.avatarUrl, size = 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        agentName,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { if (!sending) onClose() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "关闭", tint = Brand.Muted, modifier = Modifier.size(19.dp))
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 极简目标会话选择条：直观看到「续聊哪条」还是「新建会话」，一点展开切换
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brand.SurfaceAlt)
                        .border(0.6.dp, Brand.Border, RoundedCornerShape(12.dp))
                        .clickable(enabled = !sending) { pickingSession = !pickingSession }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (selectedSession != null) "续聊" else "新会话",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.Navy,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Brand.LavenderSoft)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        selectedSession?.title?.ifEmpty { "最近会话" } ?: "发送后自动新建对话",
                        fontSize = 13.5.sp,
                        color = Brand.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = Brand.Muted, modifier = Modifier.size(18.dp))
                }

                AnimatedVisibility(visible = pickingSession) {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .heightIn(max = 170.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brand.SurfaceAlt)
                            .border(0.6.dp, Brand.Border, RoundedCornerShape(12.dp))
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item(key = "sheet_new") {
                            val isNew = targetSessionId.isEmpty()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (isNew) Brand.LavenderSoft else Color.Transparent)
                                    .clickable {
                                        targetSessionId = ""
                                        prefs.edit().putString("current_session", "").apply()
                                        pickingSession = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Add, null, tint = Brand.Navy, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("新建会话", fontSize = 13.5.sp, color = Brand.Ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                if (isNew) Icon(Icons.Default.Check, null, tint = Brand.Navy, modifier = Modifier.size(15.dp))
                            }
                        }
                        items(agentSessions.take(8), key = { it.id }) { s ->
                            val sel = s.id == targetSessionId
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (sel) Brand.LavenderSoft else Color.Transparent)
                                    .clickable {
                                        targetSessionId = s.id
                                        AppState.rememberAgentSession(prefs, agentId, s.id)
                                        pickingSession = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    s.title.ifEmpty { "最近会话" },
                                    fontSize = 13.5.sp,
                                    color = if (sel) Brand.Navy else Brand.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(timeLabel(s.updatedAt), fontSize = 11.5.sp, color = Brand.Subtle)
                                if (sel) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Default.Check, null, tint = Brand.Navy, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                        val thumb = remember(item.shot?.absolutePath) {
                            item.shot?.takeIf { it.exists() }?.let {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                BitmapFactory.decodeFile(it.absolutePath, opts)?.asImageBitmap()
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brand.SurfaceAlt)
                                .border(0.6.dp, Brand.Border, RoundedCornerShape(14.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (thumb != null) {
                                Image(
                                    thumb, null,
                                    Modifier
                                        .size(46.dp, 64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(0.5.dp, Brand.Border, RoundedCornerShape(8.dp))
                                        .clickable {
                                            item.shot?.takeIf { it.exists() }?.let { f ->
                                                previewShot = f to item.note.ifEmpty { "标注截图 ${idx + 1}" }
                                            }
                                        },
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(Modifier.width(11.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.note.ifEmpty { if (thumb != null) "标注截图 ${idx + 1}" else "文字意见 ${idx + 1}" },
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = Brand.Ink,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                enabled = !sending,
                                onClick = { Outbox.remove(item.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, "移除", tint = Brand.Subtle, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Brand.Border, thickness = 0.6.dp)
                Spacer(Modifier.height(6.dp))

                SheetSwitchRow("完成后气泡提醒", bubbleAlert) { on ->
                    bubbleAlert = on
                    prefs.edit().putBoolean("bubble_alert", on).apply()
                }

                if (errorMsg.isNotEmpty()) {
                    Text(errorMsg, color = Brand.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }

                Spacer(Modifier.height(12.dp))

                val btnBg by animateColorAsState(
                    targetValue = when {
                        sentDone -> Brand.Success
                        items.isEmpty() -> Color(0xFFD7DAE2)
                        else -> Brand.Ink
                    },
                    animationSpec = tween(200),
                    label = "sendBtnBg"
                )
                Button(
                    enabled = !sending && !sentDone && items.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = btnBg,
                        contentColor = Color.White,
                        disabledContainerColor = btnBg,
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(15.dp),
                    onClick = {
                        if (agentId.isEmpty()) { errorMsg = "请先选择智能体"; return@Button }
                        sending = true
                        errorMsg = ""
                        val snapshot = items.toList()
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    var sid = targetSessionId
                                    val firstNote = snapshot.firstOrNull { it.note.isNotEmpty() }?.note.orEmpty()
                                    if (sid.isEmpty()) {
                                        sid = api.createSession(agentId, firstNote.ifEmpty { "截图意见" }.take(24))
                                    }
                                    AppState.rememberAgentSession(prefs, agentId, sid)
                                    val notes = StringBuilder()
                                    val ids = mutableListOf<String>()
                                    snapshot.forEachIndexed { i, it ->
                                        it.shot?.takeIf { f -> f.exists() }?.let { f ->
                                            ids.add(api.upload(sid, f.name, "image/png", f.readBytes()))
                                        }
                                        if (it.note.isNotEmpty()) {
                                            if (notes.isNotEmpty()) notes.append("\n")
                                            notes.append(if (snapshot.size > 1) "${i + 1}. ${it.note}" else it.note)
                                        }
                                    }
                                    api.send(sid, notes.toString().ifEmpty { "（见截图）" }, ids)
                                    api.pendingTasks()
                                }
                            }
                            sending = false
                            r.onSuccess {
                                if (bubbleAlert && !AppState.notifRunning.value) {
                                    runCatching { ReplyService.start(ctx) }
                                }
                                AppState.workingStatus.value = "$agentName 处理中"
                                AppState.notifyBubble()
                                sentDone = true
                                Outbox.clear()
                                delay(600)
                                onSent(agentName, snapshot.size)
                            }.onFailure {
                                errorMsg = if (it is AuthExpired) "登录已失效" else "发送失败：${it.message}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    when {
                        sentDone -> {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("已发送", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        sending -> {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("发送中…", fontSize = 15.sp)
                        }
                        else -> {
                            Text(if (items.size > 1) "发送 ${items.size} 条" else "发送", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        previewShot?.let { (file, title) ->
            LocalFileImageOverlay(file = file, title = title, onDismiss = { previewShot = null })
        }
    }
}

@Composable
private fun SheetSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, color = Brand.InkSoft, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Brand.Ink,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFD8DCE4),
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

// ==================== 3. 完成后白色悬浮气泡 ====================
@Composable
private fun CompletionBubbleOverlay(
    notice: AppState.CompletionNotice,
    onLater: () -> Unit,
    onInspect: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x26000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onLater() },
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(170)) + scaleIn(spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium), initialScale = 0.88f),
            exit = fadeOut(tween(120))
        ) {
            Column(
                Modifier
                    .width(296.dp)
                    .shadow(18.dp, RoundedCornerShape(22.dp))
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White)
                    .border(0.6.dp, Brand.Border, RoundedCornerShape(22.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(notice.agentName, notice.avatarUrl, size = 30.dp, showOnlineDot = true)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (notice.installedPkg != null) "${notice.agentName} 已更新" else notice.agentName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    notice.summary,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = Brand.InkSoft,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brand.SurfaceAlt)
                        .padding(11.dp)
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onLater,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { Text("稍后", color = Brand.InkSoft, fontSize = 13.5.sp) }
                    Button(
                        onClick = onInspect,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { Text(if (notice.installedPkg != null) "打开" else "查看", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

// ==================== 4. 顶部灵动岛风格暗色状态条 ====================
@Composable
private fun TopStatusBanner(title: String, subtitle: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val statusTopDp = remember {
        val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else 0
        with(density) { px.toDp() }.coerceAtLeast(28.dp)
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
        delay(2400)
        visible = false
        delay(200)
        onDone()
    }

    Box(Modifier.fillMaxWidth().padding(top = statusTopDp + 8.dp, start = 20.dp, end = 20.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(200)) { -it } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(140))
        ) {
            Row(
                Modifier
                    .shadow(14.dp, RoundedCornerShape(22.dp))
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xF613151B))
                    .border(0.7.dp, Color(0x24FFFFFF), RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(Brand.Success, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(subtitle, color = Color(0xFF9AA2B4), fontSize = 12.5.sp)
            }
        }
    }
}
