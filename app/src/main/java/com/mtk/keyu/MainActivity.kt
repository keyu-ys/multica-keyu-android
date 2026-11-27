package com.mtk.keyu

import androidx.compose.runtime.saveable.rememberSaveable

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val openSession = mutableStateOf<Pair<String, Long>?>(null)
    private var reauthCapture: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val api = Api(prefs)
        intent?.getStringExtra("open_session")?.takeIf { it.isNotEmpty() }?.let {
            openSession.value = it to SystemClock.uptimeMillis()
        }
        intent?.getStringExtra("open_issue")?.takeIf { it.isNotEmpty() }?.let {
            AppState.openIssueReq.value = it to SystemClock.uptimeMillis()
        }

        val cachedEmail = prefs.getString("email", "").orEmpty()
        if (cachedEmail.isNotEmpty() && AppState.meProfile.value == null) {
            AppState.meProfile.value = UserProfile(
                name = prefs.getString("user_name", "")!!.ifEmpty { cachedEmail.substringBefore("@") },
                email = cachedEmail,
                avatarUrl = prefs.getString("user_avatar", "").orEmpty()
            )
        }

        if (api.loggedIn && prefs.getBoolean("notif", false) && !AppState.notifRunning.value) {
            runCatching { ReplyService.start(this) }
        }

        val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) {
                prefs.edit().putBoolean("notif", true).apply()
                ReplyService.start(this)
            } else {
                toast("请在设置中开启通知权限")
            }
        }
        val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            android.util.Log.i("MFLOAT", "capture result code=${r.resultCode} data=${r.data != null}")
            if (r.resultCode == Activity.RESULT_OK && r.data != null) {
                CaptureService.start(this, r.resultCode, r.data!!)
                BubbleService.start(this)
                prefs.edit().putBoolean("bubble", true).apply()
            } else {
                toast("需要允许屏幕捕获")
            }
        }
        val overlayThenCapture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (AndroidSettings.canDrawOverlays(this)) startCapture(capture) else toast("需要悬浮窗权限")
        }

        val control = object : Control {
            override val notif get() = AppState.notifRunning.value
            override val bubble get() = AppState.bubbleRunning.value
            override fun toggleNotif(on: Boolean) {
                if (on) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        prefs.edit().putBoolean("notif", true).apply()
                        ReplyService.start(this@MainActivity)
                    }
                } else {
                    prefs.edit().putBoolean("notif", false).apply()
                    ReplyService.stop(this@MainActivity)
                }
            }

            override fun toggleBubble(on: Boolean) {
                if (on) {
                    if (!AndroidSettings.canDrawOverlays(this@MainActivity)) {
                        overlayThenCapture.launch(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } else {
                        startCapture(capture)
                    }
                } else {
                    prefs.edit().putBoolean("bubble", false).apply()
                    BubbleService.stop(this@MainActivity)
                    CaptureService.stop(this@MainActivity)
                }
            }
        }
        reauthCapture = { control.toggleBubble(true) }
        if (intent?.getBooleanExtra("request_capture", false) == true) {
            window.decorView.post { control.toggleBubble(true) }
        }

        setContent {
            BrandTheme {
                Surface(Modifier.fillMaxSize(), color = Color.White) {
                    App(api, prefs, openSession, control)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.appInForeground = true
    }

    override fun onPause() {
        super.onPause()
        AppState.appInForeground = false
    }

    private fun startCapture(launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 明确申请「整个屏幕」捕获，避免系统默认「单个应用」导致切到其他应用后仍只截到本应用
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        launcher.launch(intent)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("open_session")?.takeIf { it.isNotEmpty() }?.let {
            openSession.value = it to SystemClock.uptimeMillis()
        }
        intent.getStringExtra("open_issue")?.takeIf { it.isNotEmpty() }?.let {
            AppState.openIssueReq.value = it to SystemClock.uptimeMillis()
        }
        if (intent.getBooleanExtra("request_capture", false)) {
            window.decorView.post { reauthCapture?.invoke() }
        }
    }
}

interface Control {
    val notif: Boolean
    val bubble: Boolean
    fun toggleNotif(on: Boolean)
    fun toggleBubble(on: Boolean)
}

@Composable
fun App(
    api: Api,
    prefs: android.content.SharedPreferences,
    openSession: MutableState<Pair<String, Long>?>,
    control: Control
) {
    var screen by remember { mutableStateOf(if (api.loggedIn) "chat" else "settings") }
    var notice by remember { mutableStateOf("") }

    // 无论当前停留在设置页还是对话页，点击系统通知或悬浮提醒都立即回到对话并打开目标会话
    LaunchedEffect(openSession.value) {
        if (openSession.value != null && api.loggedIn && screen != "chat") {
            screen = "chat"
        }
    }
    LaunchedEffect(AppState.openIssueReq.value) {
        if (AppState.openIssueReq.value != null && api.loggedIn && screen != "chat") {
            screen = "chat"
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            "chat" -> ChatHome(
                api = api,
                prefs = prefs,
                openSession = openSession,
                control = control,
                onSettings = { screen = "settings" },
                onAuthExpired = {
                    prefs.edit().remove("token").apply()
                    notice = "登录已失效，请重新登录"
                    screen = "settings"
                }
            )
            else -> {
                BackHandler(api.loggedIn) { screen = "chat" }
                Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    SettingsScreen(api, prefs, notice, control, onDone = { notice = ""; screen = "chat" })
                }
            }
        }
    }
}

suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

fun timeLabel(iso: String): String = runCatching {
    val t = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
    val d = t.toLocalDate()
    val today = LocalDate.now()
    when {
        d == today -> "%02d:%02d".format(t.hour, t.minute)
        d == today.minusDays(1) -> "昨天"
        else -> "${d.monthValue}-${d.dayOfMonth}"
    }
}.getOrDefault("")

// ---------------- 极客纯净主界面 ----------------
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatHome(
    api: Api,
    prefs: android.content.SharedPreferences,
    openSession: MutableState<Pair<String, Long>?>,
    control: Control,
    onSettings: () -> Unit,
    onAuthExpired: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    val imeVisible = WindowInsets.isImeVisible

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    var agents by remember { mutableStateOf(AppState.allAgents()) }
    val sessions = AppState.sessions
    var agentId by remember { mutableStateOf(prefs.getString("agent", "")!!) }
    var sessionId by remember { mutableStateOf(prefs.getString("current_session", "")!!) }
    var msgs by remember { mutableStateOf(listOf<Msg>()) }
    var pendingLocalMsg by remember(sessionId) { mutableStateOf<Msg?>(null) }
    var pendingLocalQueued by remember(sessionId) { mutableStateOf<String?>(null) }
    var loadingSession by remember { mutableStateOf(sessionId.isNotEmpty()) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var lastLocalSendAtMs by remember(sessionId) { mutableLongStateOf(0L) }
    var hasNewBelow by remember(sessionId) { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(listOf<Uri>()) }
    var status by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var pendingTaskId by remember { mutableStateOf<String?>(null) }
    var pendingTask by remember { mutableStateOf<PendingTask?>(null) }
    var liveTaskMsgs by remember { mutableStateOf(listOf<TaskMsg>()) }
    var liveTaskId by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    var showAgentSheet by remember { mutableStateOf(false) }
    // 顶部「聊天 | 工作」切换
    var tab by rememberSaveable { mutableStateOf("chat") }
    var workRelevantOnly by remember { mutableStateOf(prefs.getBoolean("work_relevant_only", true)) }
    var workRefreshKey by remember { mutableIntStateOf(0) }
    var openIssue by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    val listState = rememberLazyListState()

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount <= 1) true
            else {
                val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
                last.index >= info.totalItemsCount - 1 &&
                    (last.offset + last.size) <= info.viewportEndOffset + 96
            }
        }
    }

    suspend fun scrollToBottom(animate: Boolean = false) {
        val lastIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        if (animate) listState.animateScrollToItem(lastIdx) else listState.scrollToItem(lastIdx)
    }

    // 列表滚动时自动收起键盘（标准移动端聊天交互）
    val dismissOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && abs(available.y) > 4f) {
                    dismissKeyboard()
                }
                return Offset.Zero
            }
        }
    }

    // 拉开侧边栏时收起键盘
    LaunchedEffect(drawer.targetValue) {
        if (drawer.targetValue == DrawerValue.Open) dismissKeyboard()
    }

    // 滑到底部时清除「有新消息」提示
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) hasNewBelow = false
    }

    // 弹起键盘时若原本停留在底部，保持最新消息紧贴键盘上方可见
    var wasAtBottomBeforeIme by remember { mutableStateOf(true) }
    LaunchedEffect(isAtBottom, imeVisible) {
        if (!imeVisible) wasAtBottomBeforeIme = isAtBottom
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible && wasAtBottomBeforeIme && (msgs.isNotEmpty() || waiting)) {
            delay(80)
            scrollToBottom(animate = false)
        }
    }

    LaunchedEffect(sessionId) { AppState.foregroundSessionId = sessionId.ifEmpty { null } }
    DisposableEffect(Unit) { onDispose { AppState.foregroundSessionId = null } }

    fun fail(e: Throwable) {
        if (e is AuthExpired) onAuthExpired() else status = e.message ?: "请求失败"
    }

    fun openSessionById(sid: String, aid: String) {
        dismissKeyboard()
        val sameSession = sid.isNotEmpty() && sid == sessionId
        sessionId = sid
        agentId = aid
        if (!sameSession) {
            msgs = emptyList()
            pendingLocalMsg = null
            pendingLocalQueued = null
            loadingSession = sid.isNotEmpty()
            pendingTaskId = AppState.runningTasks[sid]
            pendingTask = null
            liveTaskMsgs = emptyList()
            liveTaskId = ""
            waiting = pendingTaskId != null
            hasNewBelow = false
        }
        status = ""
        reloadTrigger++
        AppState.markSessionReadLocally(sid)
        AppState.rememberAgentSession(prefs, aid, sid)
        AppState.notifyBubble()
        scope.launch { drawer.close() }
    }

    fun newChat() {
        dismissKeyboard()
        sessionId = ""
        msgs = emptyList()
        pendingLocalMsg = null
        pendingLocalQueued = null
        loadingSession = false
        pendingTaskId = null
        pendingTask = null
        liveTaskMsgs = emptyList()
        liveTaskId = ""
        waiting = false
        hasNewBelow = false
        status = ""
        prefs.edit().putString("current_session", "").apply()
        AppState.notifyBubble()
        scope.launch { drawer.close() }
    }

    /** 切换智能体时默认平滑续聊该智能体最近会话，不再强迫每次建新会话。 */
    fun switchAgent(targetAgentId: String) {
        if (targetAgentId == agentId) return
        val preferred = AppState.preferredSessionForAgent(prefs, targetAgentId)
        openSessionById(preferred, targetAgentId)
    }

    fun stopCurrentTask() {
        val sid = sessionId
        val tid = pendingTaskId ?: AppState.runningTasks[sid]
        if (stopping) return
        stopping = true
        status = ""
        scope.launch {
            io {
                val resolvedTid = tid ?: api.pendingTask(sid)?.taskId
                if (resolvedTid.isNullOrEmpty()) error("当前没有进行中的任务")
                api.cancelTask(resolvedTid)
            }.onSuccess { res ->
                pendingTaskId = null
                pendingTask = null
                liveTaskMsgs = emptyList()
                liveTaskId = ""
                waiting = false
                lastLocalSendAtMs = 0L
                AppState.setSessionTask(sid, null)
                AppState.workingStatus.value = null
                if (!res.restoredText.isNullOrBlank() && input.isBlank()) {
                    input = res.restoredText
                }
                Toast.makeText(ctx, "已停止", Toast.LENGTH_SHORT).show()
                if (sid.isNotEmpty()) {
                    io { api.messages(sid) }.onSuccess { msgs = it }
                }
            }.onFailure { e ->
                status = e.message ?: "停止失败"
            }
            stopping = false
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        images = (images + uris).distinct().take(6)
    }

    LaunchedEffect(openSession.value) {
        val ev = openSession.value
        if (ev != null) {
            val sid = ev.first
            val aid = AppState.sessions.firstOrNull { it.id == sid }?.agentId?.takeIf { it.isNotEmpty() }
                ?: io { api.getSessionAgentId(sid) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: agentId
            openSessionById(sid, aid)
            openSession.value = null
        }
    }

    LaunchedEffect(Unit) {
        io {
            api.refresh()
            runCatching { api.meProfile() }
            val a = api.agents()
            val s = api.sessions()
            api.pendingTasks()
            a to s
        }.onSuccess { (a, s) ->
            agents = a
            AppState.unreadCount.intValue = s.count { it.unread }
            AppState.notifyBubble()
            if (agentId.isEmpty() || a.none { it.id == agentId }) {
                showAgentSheet = true
            } else if (sessionId.isEmpty()) {
                // 若上次未记录具体会话，自动检查是否有最近会话保持同步
                val recent = AppState.preferredSessionForAgent(prefs, agentId)
                if (recent.isNotEmpty()) {
                    sessionId = recent
                    loadingSession = true
                    AppState.rememberAgentSession(prefs, agentId, recent)
                }
            }
            launch { AutoUpdater.check(ctx, api, prefs, manual = false) }
        }.onFailure(::fail)
    }

    // 侧边栏打开时实时轮询所有会话与正在输入状态，同时静默检查一次应用新版本（带节流）
    LaunchedEffect(drawer.isOpen) {
        if (drawer.isOpen) {
            launch { AutoUpdater.check(ctx, api, prefs, manual = false) }
        }
        while (drawer.isOpen) {
            io {
                val s = api.sessions()
                api.pendingTasks()
                s
            }.onSuccess { list ->
                AppState.unreadCount.intValue = list.count { it.unread }
            }.onFailure(::fail)
            delay(3000)
        }
    }

    // 当前会话消息 + pending-task + 关键思考节点增量轮询
    LaunchedEffect(sessionId, reloadTrigger) {
        var firstLoad = true
        var lastVisibleTailId = msgs.lastOrNull()?.id.orEmpty()
        while (sessionId.isNotEmpty()) {
            io { api.messages(sessionId) to api.pendingTask(sessionId) }.onSuccess { (m, p) ->
                val wasBottom = isAtBottom
                val prevCount = msgs.size
                val prevLiveCount = liveTaskMsgs.size
                val prevQueuedIds = pendingTask?.queuedTasks?.mapNotNull { it.messageId.takeIf { id -> id.isNotEmpty() } }?.toSet().orEmpty()
                val nextQueuedIds = p?.queuedTasks?.mapNotNull { it.messageId.takeIf { id -> id.isNotEmpty() } }?.toSet().orEmpty()
                val nextVisible = if (nextQueuedIds.isEmpty()) m else m.filterNot { it.id in nextQueuedIds }
                val nextTailId = nextVisible.lastOrNull()?.id.orEmpty()
                val newVisibleArrived = nextTailId.isNotEmpty() && nextTailId != lastVisibleTailId
                val promotedFromQueue = prevQueuedIds.any { it !in nextQueuedIds }

                msgs = m
                if (pendingLocalMsg != null && m.any { it.role == "user" && it.content.trim() == pendingLocalMsg?.content?.trim() }) {
                    pendingLocalMsg = null
                }
                loadingSession = false

                val persistedPending = p != null && m.any { it.role == "assistant" && it.taskId == p.taskId }
                val activePending = if (persistedPending) null else p
                pendingTask = activePending
                pendingTaskId = activePending?.taskId
                AppState.setSessionTask(sessionId, activePending?.taskId)
                val inSendGrace = (System.currentTimeMillis() - lastLocalSendAtMs) < 6_000L
                waiting = activePending != null || (inSendGrace && m.lastOrNull()?.role == "user" && !stopping)

                if (activePending != null) {
                    if (activePending.taskId != liveTaskId) {
                        liveTaskId = activePending.taskId
                        liveTaskMsgs = emptyList()
                    }
                    val sinceSeq = liveTaskMsgs.lastOrNull()?.seq ?: 0
                    val delta = io { api.taskMessages(activePending.taskId, sinceSeq) }.getOrNull().orEmpty()
                    if (delta.isNotEmpty()) {
                        val merged = (liveTaskMsgs + delta)
                            .associateBy { it.seq }
                            .values
                            .sortedBy { it.seq }
                        liveTaskMsgs = merged
                        val extracted = extractThinkingNodes(merged)
                        if (extracted.isNotEmpty()) {
                            AppState.thinkingNodes[activePending.taskId] = extracted
                        }
                    }
                } else {
                    if (liveTaskId.isNotEmpty()) {
                        liveTaskId = ""
                        liveTaskMsgs = emptyList()
                    }
                    if (!waiting) AppState.workingStatus.value = null

                    val latestAssistant = m.lastOrNull { it.role == "assistant" && !it.taskId.isNullOrEmpty() }
                    val tid = latestAssistant?.taskId
                    if (tid != null && tid !in AppState.thinkingNodes && (latestAssistant.elapsedMs ?: 0L) >= 3000L) {
                        val historyTaskMsgs = io { api.taskMessages(tid) }.getOrNull()
                        if (historyTaskMsgs != null) {
                            AppState.thinkingNodes[tid] = extractThinkingNodes(historyTaskMsgs)
                        }
                    }
                }

                val liveGrew = liveTaskMsgs.size > prevLiveCount
                if (m.isNotEmpty() && (firstLoad || m.size != prevCount || newVisibleArrived)) {
                    AppState.markSessionReadLocally(sessionId)
                    io { api.markRead(sessionId) }
                }
                if (firstLoad || ((newVisibleArrived || promotedFromQueue || liveGrew) && wasBottom)) {
                    delay(45)
                    scrollToBottom(animate = !firstLoad)
                    hasNewBelow = false
                } else if (newVisibleArrived && !wasBottom) {
                    hasNewBelow = true
                }
                lastVisibleTailId = nextTailId
                firstLoad = false
                status = ""
            }.onFailure { e ->
                loadingSession = false
                fail(e)
            }
            delay(if (waiting) 1400 else 8000)
        }
    }

    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(tab == "work" && openIssue == null && !drawer.isOpen) { tab = "chat" }

    // 点击「工作」类系统通知 → 切到工作页并打开对应任务
    LaunchedEffect(AppState.openIssueReq.value) {
        val req = AppState.openIssueReq.value ?: return@LaunchedEffect
        scope.launch { drawer.close() }
        tab = "work"
        openIssue = Triple(req.first, "", "")
        AppState.openIssueReq.value = null
    }

    // 不在工作页时也低频刷新「工作」角标
    LaunchedEffect(Unit) {
        while (true) {
            if (tab != "work" && openIssue == null) {
                io { api.inbox() }.onSuccess {
                    WorkCache.items = it
                    AppState.workUnread.intValue = workUnreadOf(it)
                }
            }
            delay(20_000)
        }
    }

    val currentAgent = agents.firstOrNull { it.id == agentId }
    val agentName = currentAgent?.name ?: "选择智能体"
    val unreadSessions = sessions.count { it.unread && it.id != sessionId }
    val me = AppState.meProfile.value
    val userName = me?.name?.ifEmpty { prefs.getString("email", "")!!.substringBefore("@") } ?: prefs.getString("email", "")!!.substringBefore("@")
    val userAvatar = me?.avatarUrl ?: prefs.getString("user_avatar", "").orEmpty()

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = Color(0x24000000),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(268.dp)
                    .shadow(
                        elevation = 20.dp,
                        shape = RectangleShape,
                        clip = false,
                        ambientColor = Color(0x30000000),
                        spotColor = Color(0x45000000)
                    ),
                drawerShape = RectangleShape,
                drawerContainerColor = Color(0xFFFAFAFC),
                drawerTonalElevation = 0.dp
            ) {
                Column(Modifier.fillMaxHeight().systemBarsPadding()) {
                    // 极简品牌头 + 新建对话
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(painterResource(R.drawable.logo), "Multica", Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text("Multica", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Brand.Ink, modifier = Modifier.weight(1f))
                        IconButton(onClick = { newChat() }, modifier = Modifier.size(36.dp)) {
                            AppIcons.NewChat(Brand.Ink, 19.dp)
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(sessions, key = { it.id }) { s ->
                            val aObj = agents.firstOrNull { it.id == s.agentId }
                            val name = aObj?.name ?: ""
                            val sel = s.id == sessionId
                            val isTyping = AppState.runningTasks.containsKey(s.id)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (sel) Brand.LavenderSoft else Color.Transparent)
                                    .clickable { openSessionById(s.id, s.agentId) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(name.ifEmpty { "M" }, aObj?.avatarUrl, size = 34.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            s.title.ifEmpty { "新对话" },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 14.5.sp,
                                            color = if (sel) Brand.Navy else Brand.Ink,
                                            fontWeight = if (s.unread || sel) FontWeight.SemiBold else FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            timeLabel(s.updatedAt),
                                            fontSize = 11.5.sp,
                                            color = Brand.Subtle
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            name,
                                            fontSize = 12.sp,
                                            color = Brand.Muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isTyping) {
                                            Text(" · ", fontSize = 12.sp, color = Brand.Muted)
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(11.dp),
                                                strokeWidth = 1.6.dp,
                                                color = Brand.Success,
                                                trackColor = Color(0x281E8E5A)
                                            )
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                "正在输入…",
                                                fontSize = 12.sp,
                                                color = Brand.Success,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1
                                            )
                                        }
                                        if (s.unread && !isTyping) {
                                            Spacer(Modifier.weight(1f))
                                            Box(Modifier.size(7.dp).background(Brand.Navy, CircleShape))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Brand.Border, thickness = 0.6.dp)

                    val ota = AppState.otaState.value
                    val hasUpdate = ota.manifest != null && ota.phase in setOf(
                        AppState.OtaPhase.AVAILABLE,
                        AppState.OtaPhase.ERROR
                    )
                    val otaBusy = ota.phase in setOf(
                        AppState.OtaPhase.CHECKING,
                        AppState.OtaPhase.DOWNLOADING,
                        AppState.OtaPhase.VERIFYING,
                        AppState.OtaPhase.INSTALLING
                    )

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { scope.launch { drawer.close() }; onSettings() }
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(name = userName, avatarUrl = userAvatar, size = 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    userName,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Brand.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (ota.phase == AppState.OtaPhase.DOWNLOADING && ota.manifest != null) {
                                    Text(
                                        "更新 ${verLabel(ota.manifest.versionName)} " +
                                            if (ota.progress >= 0f) "${(ota.progress * 100).toInt()}%" else "…",
                                        fontSize = 11.sp,
                                        color = Brand.Navy,
                                        maxLines = 1
                                    )
                                } else if (ota.phase == AppState.OtaPhase.VERIFYING || ota.phase == AppState.OtaPhase.INSTALLING) {
                                    Text(
                                        ota.message.ifEmpty { "正在安装…" },
                                        fontSize = 11.sp,
                                        color = Brand.Navy,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(4.dp))

                        // Codex 桌面端风格弱设计更新图标：无新版时浅灰静置，有新版时亮起，点击直接下载安装
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (hasUpdate) Brand.LavenderSoft else Color.Transparent)
                                .clickable(enabled = !otaBusy) {
                                    scope.launch {
                                        val manifest = ota.manifest
                                        if (hasUpdate && manifest != null) {
                                            AutoUpdater.downloadAndInstall(ctx, api, manifest)
                                        } else {
                                            val msg = AutoUpdater.check(ctx, api, prefs, manual = true)
                                            if (AppState.otaState.value.phase != AppState.OtaPhase.AVAILABLE) {
                                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                ota.phase == AppState.OtaPhase.DOWNLOADING && ota.progress >= 0f -> {
                                    CircularProgressIndicator(
                                        progress = { ota.progress },
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Brand.Navy,
                                        trackColor = Brand.Border
                                    )
                                }
                                otaBusy -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Brand.Navy
                                    )
                                }
                                else -> {
                                    AppIcons.Download(
                                        tint = if (hasUpdate) Brand.Navy else Brand.Subtle,
                                        size = 19.dp
                                    )
                                }
                            }
                            if (hasUpdate) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 6.dp, end = 6.dp)
                                        .size(6.5.dp)
                                        .background(Brand.Navy, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(Color.White)) {
            // 极简通透顶栏（点击空白处自动收起键盘）；切换器按整屏宽度绝对居中
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismissKeyboard() }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    IconButton(onClick = { dismissKeyboard(); scope.launch { drawer.open() } }) {
                        AppIcons.MenuLines(Brand.Ink, 21.dp)
                    }
                    if (unreadSessions > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 11.dp, end = 10.dp)
                                .size(7.dp)
                                .background(Brand.Navy, CircleShape)
                        )
                    }
                }

                Box(Modifier.align(Alignment.Center)) {
                    Row(
                        Modifier
                            .background(Color(0xFFF2F3F7), RoundedCornerShape(19.dp))
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左段：聊天
                        Row(
                            Modifier
                                .then(if (tab == "chat") Modifier.shadow(1.5.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x14000000), spotColor = Color(0x22000000)) else Modifier)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (tab == "chat") Color.White else Color.Transparent)
                                .clickable { dismissKeyboard(); tab = "chat" }
                                .padding(horizontal = 18.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "聊天",
                                maxLines = 1,
                                softWrap = false,
                                fontSize = 14.sp,
                                fontWeight = if (tab == "chat") FontWeight.SemiBold else FontWeight.Normal,
                                color = if (tab == "chat") Brand.Ink else Brand.Muted
                            )
                            if (tab != "chat" && unreadSessions > 0) {
                                Spacer(Modifier.width(4.dp))
                                Box(Modifier.size(6.dp).background(Brand.Navy, CircleShape))
                            }
                        }
                        // 右段：工作 + 未读角标
                        Row(
                            Modifier
                                .then(if (tab == "work") Modifier.shadow(1.5.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x14000000), spotColor = Color(0x22000000)) else Modifier)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (tab == "work") Color.White else Color.Transparent)
                                .clickable { dismissKeyboard(); if (tab == "work") workRefreshKey++ else tab = "work" }
                                .padding(horizontal = 18.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "工作",
                                maxLines = 1,
                                softWrap = false,
                                fontSize = 14.sp,
                                fontWeight = if (tab == "work") FontWeight.SemiBold else FontWeight.Normal,
                                color = if (tab == "work") Brand.Ink else Brand.Muted
                            )
                            val wu = AppState.workUnread.intValue
                            if (wu > 0) {
                                Spacer(Modifier.width(5.dp))
                                CountBadge(wu)
                            }
                        }
                    }
                }

                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                if (tab == "chat") Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (control.bubble) Brand.LavenderSoft else Color.Transparent)
                        .clickable { dismissKeyboard(); control.toggleBubble(!control.bubble) },
                    contentAlignment = Alignment.Center
                ) {
                    AppIcons.FloatOrb(
                        tint = if (control.bubble) Brand.Navy else Brand.Ink,
                        filledCenter = control.bubble,
                        size = 20.dp
                    )
                }

                if (tab == "work") {
                    TextButton(onClick = {
                        scope.launch {
                            io { api.markAllInboxRead() }.onSuccess {
                                WorkCache.items = WorkCache.items.map { it.copy(read = true) }
                                AppState.workUnread.intValue = 0
                                workRefreshKey++
                            }
                        }
                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("全部已读", fontSize = 12.5.sp, color = Brand.Navy, maxLines = 1, softWrap = false)
                    }
                } else {
                    IconButton(onClick = { newChat() }) {
                        AppIcons.NewChat(Brand.Ink, 20.dp)
                    }
                }
                }
            }

            if (tab == "work") {
                WorkInbox(
                    api = api,
                    relevantOnly = workRelevantOnly,
                    onRelevantChange = { workRelevantOnly = it; prefs.edit().putBoolean("work_relevant_only", it).apply() },
                    refreshKey = workRefreshKey,
                    onOpen = { g -> if (g.latest.issueId.isNotEmpty()) openIssue = Triple(g.latest.issueId, g.latest.title, g.latest.issueIdentifier) else Toast.makeText(ctx, "该动态请在电脑端查看", Toast.LENGTH_SHORT).show() },
                    onAuthExpired = onAuthExpired
                )
            } else {
            // 第二层：当前智能体（点按切换），柔和胶囊，不画分割线
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF5F6F9))
                        .clickable { dismissKeyboard(); showAgentSheet = true }
                        .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(name = currentAgent?.name ?: "M", avatarUrl = currentAgent?.avatarUrl, size = 22.dp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        agentName,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Brand.InkSoft,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = Brand.Subtle, modifier = Modifier.size(16.dp))
                }
            }
            // 更新提示：一行 tips，按周期触发、会自动收起（与侧边栏下载图标并存）
            UpdateTip(api, prefs)
            // 消息区（轻点空白/对话区或拖拽列表时立即收起键盘，不拦截链接/图片/按钮与长按选择）
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var moved = false
                            var upTime = 0L
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                }
                                if (change.changedToUpIgnoreConsumed()) {
                                    upTime = change.uptimeMillis
                                    break
                                }
                            }
                            if (!moved && upTime > 0L && (upTime - down.uptimeMillis) < 280L) {
                                dismissKeyboard()
                            }
                        }
                    }
            ) {
                if (loadingSession && msgs.isEmpty() && pendingLocalMsg == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Brand.Lavender)
                    }
                } else if (msgs.isEmpty() && pendingLocalMsg == null && !waiting) {
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(painterResource(R.drawable.logo), null, Modifier.size(56.dp).clip(RoundedCornerShape(15.dp)))
                        Spacer(Modifier.height(16.dp))
                        Text("有什么可以帮你？", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink)
                        if (!control.bubble && agentId.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Brand.Surface)
                                    .border(0.6.dp, Brand.Border, RoundedCornerShape(20.dp))
                                    .clickable { dismissKeyboard(); control.toggleBubble(true) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AppIcons.FloatOrb(Brand.InkSoft, filledCenter = false, size = 15.dp)
                                Text("开启悬浮助手", fontSize = 13.sp, color = Brand.InkSoft, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                } else {
                    val queuedMsgIds = remember(pendingTask) {
                        pendingTask?.queuedTasks?.mapNotNull { it.messageId.takeIf { id -> id.isNotEmpty() } }?.toSet().orEmpty()
                    }
                    val visibleMsgs = remember(msgs, queuedMsgIds, pendingLocalMsg) {
                        val base = if (queuedMsgIds.isEmpty()) msgs else msgs.filterNot { it.id in queuedMsgIds }
                        val local = pendingLocalMsg
                        if (local != null) base + local else base
                    }
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(dismissOnScroll)
                            .padding(horizontal = 16.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 14.dp)
                    ) {
                        items(visibleMsgs, key = { it.id }) { m -> MessageRow(api, m) }
                        if (waiting) {
                            item(key = "thinking") {
                                val digest = remember(pendingTask, liveTaskMsgs) {
                                    extractThinkingDigest(pendingTask, liveTaskMsgs)
                                }
                                LiveThinkingBlock(
                                    pendingTask = pendingTask,
                                    digest = digest
                                )
                            }
                        }
                        // 底部锚点：保证长回复/流式回复始终可准确滚动到最底端
                        item(key = "bottom_anchor") { Spacer(Modifier.height(1.dp)) }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isAtBottom && (msgs.isNotEmpty() || waiting),
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                    ) {
                        if (hasNewBelow) {
                            Row(
                                Modifier
                                    .shadow(4.dp, RoundedCornerShape(18.dp))
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Brand.Ink)
                                    .clickable {
                                        hasNewBelow = false
                                        scope.launch { scrollToBottom(animate = true) }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Text("有新消息", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                            }
                        } else {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .shadow(3.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(0.6.dp, Brand.Border, CircleShape)
                                    .clickable {
                                        scope.launch { scrollToBottom(animate = true) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, "回到底部", tint = Brand.Ink, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            // 底部统一输入区：导航栏与软键盘 inset 合并，杜绝双边距与跳动
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            ) {
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        color = Brand.Danger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                    )
                }

                // Codex 风格排队栏：紧贴输入框上方，展示排队中的消息，支持点击展开、一键「引导」或移除
                val serverQueuedTasks = pendingTask?.queuedTasks.orEmpty()
                val queuedTasks = remember(serverQueuedTasks, pendingLocalQueued) {
                    val localText = pendingLocalQueued
                    if (localText != null && serverQueuedTasks.none { it.content.trim() == localText.trim() }) {
                        serverQueuedTasks + QueuedTask(taskId = "local_queued", status = "queued", content = localText)
                    } else serverQueuedTasks
                }
                var busyQueueAction by remember { mutableStateOf<String?>(null) }
                val canSteerQueued = pendingTask?.status in setOf("running", "dispatched", "waiting_local_directory")

                AnimatedVisibility(visible = queuedTasks.isNotEmpty()) {
                    ChatQueueBar(
                        tasks = queuedTasks,
                        canSteer = canSteerQueued,
                        busyKey = busyQueueAction,
                        onSteer = { qTaskId ->
                            if (qTaskId == "local_queued" || busyQueueAction != null || sessionId.isEmpty()) return@ChatQueueBar
                            busyQueueAction = "steer:$qTaskId"
                            status = ""
                            scope.launch {
                                io { api.steerQueuedTask(sessionId, qTaskId) }
                                    .onSuccess {
                                        liveTaskMsgs = emptyList()
                                        liveTaskId = ""
                                        io { api.messages(sessionId) to api.pendingTask(sessionId) }.onSuccess { (m, p) ->
                                            msgs = m
                                            pendingTask = p
                                            pendingTaskId = p?.taskId
                                            AppState.setSessionTask(sessionId, p?.taskId)
                                        }
                                    }
                                    .onFailure { e -> status = e.message ?: "引导失败" }
                                busyQueueAction = null
                            }
                        },
                        onRemove = { qTaskId ->
                            if (qTaskId == "local_queued" || busyQueueAction != null || sessionId.isEmpty()) return@ChatQueueBar
                            busyQueueAction = "remove:$qTaskId"
                            status = ""
                            scope.launch {
                                io { api.removeQueuedTask(sessionId, qTaskId) }
                                    .onSuccess {
                                        io { api.messages(sessionId) to api.pendingTask(sessionId) }.onSuccess { (m, p) ->
                                            msgs = m
                                            pendingTask = p
                                            pendingTaskId = p?.taskId
                                            AppState.setSessionTask(sessionId, p?.taskId)
                                        }
                                    }
                                    .onFailure { e -> status = e.message ?: "移除失败" }
                                busyQueueAction = null
                            }
                        }
                    )
                }

                // 底部输入胶囊：空闲发消息，运行中默认排队；无输入时右侧一键变「停止 ■」按钮
                val hasDraft = input.isNotBlank() || images.isNotEmpty()
                val showStopBtn = waiting && !hasDraft && !sending
                val canSend = !sending && agentId.isNotEmpty() && hasDraft

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(Brand.Surface)
                        .border(0.7.dp, Brand.Border, RoundedCornerShape(26.dp))
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                ) {
                    AnimatedVisibility(visible = images.isNotEmpty()) {
                        LazyRow(
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(images, key = { it.toString() }) { uri ->
                                PickedImageThumb(uri) { images = images - uri }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = { dismissKeyboard(); pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Default.Add, "图片", tint = Brand.InkSoft)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 42.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    inputFocus.requestFocus()
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (input.isEmpty()) {
                                Text(if (waiting) "继续发消息将自动排队…" else "发消息…", color = Brand.Subtle, fontSize = 15.5.sp)
                            }
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.fillMaxWidth().focusRequester(inputFocus),
                                textStyle = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, color = Brand.Ink),
                                cursorBrush = SolidColor(Brand.Ink),
                                maxLines = 6
                            )
                        }

                        if (showStopBtn) {
                            IconButton(enabled = !stopping, onClick = { stopCurrentTask() }) {
                                Box(
                                    Modifier.size(34.dp).background(Brand.Ink, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (stopping) {
                                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                                    } else {
                                        Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.5.dp)).background(Color.White))
                                    }
                                }
                            }
                        } else {
                            IconButton(
                                enabled = canSend,
                                onClick = {
                                    val text = input.trim()
                                    val imgs = images
                                    val wasWaiting = waiting
                                    val outgoingText = text.ifEmpty { "（见图片）" }
                                    input = ""
                                    images = emptyList()
                                    sending = true
                                    status = ""
                                    lastLocalSendAtMs = System.currentTimeMillis()
                                    if (!wasWaiting) {
                                        pendingLocalMsg = Msg(
                                            id = "local_${System.currentTimeMillis()}",
                                            role = "user",
                                            content = outgoingText,
                                            createdAt = "",
                                            failed = false,
                                            elapsedMs = null,
                                            atts = emptyList()
                                        )
                                        waiting = true
                                    } else {
                                        pendingLocalQueued = outgoingText
                                    }
                                    scope.launch {
                                        delay(25)
                                        scrollToBottom(animate = true)
                                        io {
                                            var sid = sessionId
                                            val created = sid.isEmpty()
                                            if (created) sid = api.createSession(agentId, text.ifEmpty { "截图意见" }.take(24))
                                            val ids = imgs.mapIndexed { i, u ->
                                                val name = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                                                    if (c.moveToFirst()) c.getString(0) else null
                                                } ?: "image_$i.jpg"
                                                val mime = ctx.contentResolver.getType(u) ?: "image/jpeg"
                                                api.upload(sid, name, mime, ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() })
                                            }
                                            api.send(sid, outgoingText, ids, steer = false)
                                            sid to created
                                        }.onSuccess { (sid, created) ->
                                            lastLocalSendAtMs = System.currentTimeMillis()
                                            waiting = true
                                            AppState.rememberAgentSession(prefs, agentId, sid)
                                            if (created) {
                                                launch { io { api.sessions() } }
                                            }
                                            if (sid != sessionId) {
                                                pendingLocalMsg = null
                                                pendingLocalQueued = null
                                                sessionId = sid
                                            } else {
                                                io { api.messages(sid) to api.pendingTask(sid) }.onSuccess { (m, p) ->
                                                    pendingLocalMsg = null
                                                    pendingLocalQueued = null
                                                    msgs = m
                                                    pendingTask = p
                                                    pendingTaskId = p?.taskId
                                                    AppState.setSessionTask(sid, p?.taskId)
                                                }
                                                delay(45)
                                                scrollToBottom(animate = true)
                                            }
                                        }.onFailure { e ->
                                            pendingLocalMsg = null
                                            pendingLocalQueued = null
                                            if (!wasWaiting) waiting = false
                                            if (input.isEmpty()) input = text
                                            if (images.isEmpty()) images = imgs
                                            fail(e)
                                        }
                                        sending = false
                                    }
                                }
                            ) {
                                Box(
                                    Modifier.size(34.dp).background(if (canSend) Brand.Ink else Color(0xFFD6DAE2), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (sending) {
                                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                                    } else {
                                        Icon(Icons.Default.KeyboardArrowUp, "发送", tint = Color.White, modifier = Modifier.size(21.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    openIssue?.let { (id, t, ident) ->
        IssueScreen(
            api = api,
            issueId = id,
            titleHint = t,
            identifierHint = ident,
            onBack = { openIssue = null; workRefreshKey++ },
            onAuthExpired = onAuthExpired
        )
    }

    // 底部智能体选择器：切换时默认续接该智能体最近活跃会话，不再强迫开新会话
    if (showAgentSheet) {
        var agentQuery by remember { mutableStateOf("") }
        val recentOrder = remember(sessions.size) { sessions.map { it.agentId }.distinct() }
        val sortedAgents = remember(agents, agentId, recentOrder, agentQuery) {
            val q = agentQuery.trim()
            agents
                .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.desc.contains(q, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<Agent> { it.id == agentId }
                        .thenBy { val idx = recentOrder.indexOf(it.id); if (idx >= 0) idx else Int.MAX_VALUE }
                        .thenBy { it.name.lowercase() }
                )
        }
        ModalBottomSheet(
            onDismissRequest = { showAgentSheet = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = {
                Box(
                    Modifier.padding(top = 10.dp, bottom = 6.dp).width(36.dp).height(4.dp).clip(CircleShape).background(Color(0xFFDFE2E9))
                )
            }
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("选择智能体", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink, modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brand.Surface)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcons.Search(Brand.Subtle, 15.dp)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (agentQuery.isEmpty()) Text("搜索", color = Brand.Subtle, fontSize = 14.sp)
                        BasicTextField(
                            value = agentQuery,
                            onValueChange = { agentQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Brand.Ink, fontSize = 14.sp),
                            cursorBrush = SolidColor(Brand.Ink),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (agentQuery.isNotEmpty()) {
                        Icon(Icons.Default.Close, "清空", tint = Brand.Subtle, modifier = Modifier.size(16.dp).clickable { agentQuery = "" })
                    }
                }
                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(sortedAgents, key = { it.id }) { a ->
                        val sel = a.id == agentId
                        val latestSession = sessions.firstOrNull { it.agentId == a.id }
                        val agentTyping = sessions.any { it.agentId == a.id && AppState.runningTasks.containsKey(it.id) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (sel) Brand.LavenderSoft else Color.Transparent)
                                .clickable {
                                    showAgentSheet = false
                                    switchAgent(a.id)
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(name = a.name, avatarUrl = a.avatarUrl, size = 38.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    a.name,
                                    fontSize = 15.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (sel) Brand.Navy else Brand.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                when {
                                    agentTyping -> {
                                        Spacer(Modifier.height(2.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = Brand.Success)
                                            Spacer(Modifier.width(5.dp))
                                            Text("正在输入…", fontSize = 12.sp, color = Brand.Success)
                                        }
                                    }
                                    latestSession != null -> {
                                        Spacer(Modifier.height(1.dp))
                                        Text(
                                            "续聊 · ${latestSession.title.ifEmpty { "最近会话" }}",
                                            fontSize = 12.sp,
                                            color = Brand.Muted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    a.desc.isNotEmpty() -> {
                                        Spacer(Modifier.height(1.dp))
                                        Text(a.desc, fontSize = 12.sp, color = Brand.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (sel) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Default.Check, null, tint = Brand.Navy, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatQueueBar(
    tasks: List<QueuedTask>,
    canSteer: Boolean,
    busyKey: String?,
    onSteer: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.SurfaceAlt)
            .border(0.7.dp, Brand.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "待执行队列 (${tasks.size})",
            fontSize = 11.sp,
            color = Brand.Subtle,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 4.dp, top = 1.dp, bottom = 1.dp)
        )
        tasks.forEach { task ->
            val steerKey = "steer:${task.taskId}"
            val removeKey = "remove:${task.taskId}"
            val isBusy = busyKey != null
            var expanded by remember(task.taskId) { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .border(0.5.dp, Brand.Border, RoundedCornerShape(10.dp))
                    .clickable { expanded = !expanded }
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(5.dp).background(Brand.Lavender, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    task.content.trim().ifEmpty { "排队消息" },
                    fontSize = 13.sp,
                    color = Brand.InkSoft,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                // 直接引导按钮（类似 Codex Steer）
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (canSteer && !isBusy) Brand.LavenderSoft else Brand.Surface)
                        .clickable(enabled = canSteer && !isBusy) { onSteer(task.taskId) }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (busyKey == steerKey) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.6.dp, color = Brand.Navy)
                    } else {
                        Text(
                            "引导",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (canSteer && !isBusy) Brand.Navy else Brand.Subtle
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !isBusy) { onRemove(task.taskId) },
                    contentAlignment = Alignment.Center
                ) {
                    if (busyKey == removeKey) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.6.dp, color = Brand.Muted)
                    } else {
                        Icon(Icons.Default.Close, "移除排队", tint = Brand.Subtle, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PickedImageThumb(uri: Uri, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    var previewOpen by remember(uri) { mutableStateOf(false) }
    val displayName = remember(uri) {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull().orEmpty().ifEmpty { "图片预览" }
    }
    val thumb = remember(uri) {
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(ins, null, opts)?.asImageBitmap()
            }
        }.getOrNull()
    }
    Box(Modifier.size(62.dp)) {
        if (thumb != null) {
            Image(
                thumb, displayName,
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .border(0.7.dp, Brand.Border, RoundedCornerShape(12.dp))
                    .clickable { previewOpen = true },
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brand.Surface)
                    .clickable { previewOpen = true }
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0xCC0A0A0A))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, "移除", tint = Color.White, modifier = Modifier.size(11.dp))
        }
    }
    if (previewOpen) {
        LocalImagePreviewDialog(uri = uri, title = displayName, fallback = thumb, onDismiss = { previewOpen = false })
    }
}

@Composable
fun MessageRow(api: Api, m: Msg) {
    val ctx = LocalContext.current
    if (m.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier
                    .widthIn(max = 304.dp)
                    .background(Brand.LavenderSoft, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                val text = m.content.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "").trim()
                if (text.isNotEmpty() && text != "（见图片）") {
                    SelectionContainer {
                        Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = Brand.Ink)
                    }
                }
                val hasText = text.isNotEmpty() && text != "（见图片）"
                val imgs = m.atts.filter { it.isImage() }
                if (imgs.isNotEmpty()) {
                    Box(Modifier.padding(top = if (hasText) 8.dp else 0.dp)) { MediaGallery(api, imgs.map { it.toMediaRef() }) }
                }
                m.atts.filterNot { it.isImage() }.forEach { a ->
                    Box(Modifier.padding(top = 8.dp)) {
                        if (a.isVideo()) VideoCard(api, a.toMediaRef()) else FileAttachmentCard(api, a)
                    }
                }
            }
        }
    } else {
        var copied by remember(m.id) { mutableStateOf(false) }
        LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }

        Column(Modifier.fillMaxWidth()) {
            val nodes = m.taskId?.let { AppState.thinkingNodes[it] }.orEmpty()
            if (nodes.isNotEmpty()) {
                SettledThinkingSummary(nodes = nodes, elapsedMs = m.elapsedMs)
            }
            if (m.failed) {
                Text("处理失败", color = Brand.Danger, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
            }
            // 正文里 !file / ![](附件) 引用到的附件就地渲染，其余附件按 图片组 → 视频 → 安装包/文件 追加在后面
            val referenced = remember(m.id, m.content) {
                Regex("/api/attachments/([0-9a-fA-F-]{36})/").findAll(m.content).map { it.groupValues[1] }.toSet()
            }
            val cleaned = m.content.replace(Regex("!(?:file)?\\[[^\\]]*]\\(/api/attachments/[^)]*\\)"), "").trim()
            if (m.content.isNotBlank() || m.atts.isEmpty()) {
                SelectionContainer {
                    Markdown(m.content.trim().ifEmpty { "（无文字回复）" }, api = api, atts = m.atts)
                }
            }
            val rest = m.atts.filterNot { it.id in referenced }
            val restImgs = rest.filter { it.isImage() }
            if (restImgs.isNotEmpty()) {
                Box(Modifier.padding(top = 8.dp)) { MediaGallery(api, restImgs.map { it.toMediaRef() }) }
            }
            rest.filterNot { it.isImage() }.forEach { a ->
                when {
                    a.isVideo() -> Box(Modifier.padding(top = 8.dp)) { VideoCard(api, a.toMediaRef()) }
                    a.name.endsWith(".apk", true) -> ApkCard(api, a)
                    else -> FileAttachmentCard(api, a)
                }
            }

            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (cleaned.isNotEmpty()) {
                    Text(
                        if (copied) "已复制" else "复制",
                        fontSize = 11.5.sp,
                        color = if (copied) Brand.Success else Brand.Subtle,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("reply", cleaned))
                                copied = true
                            }
                            .padding(vertical = 4.dp, horizontal = 6.dp)
                    )
                }
                if (m.elapsedMs != null && m.elapsedMs > 0) {
                    Text("${m.elapsedMs / 1000}s", fontSize = 11.5.sp, color = Brand.Subtle)
                }
            }
        }
    }
}

@Composable
fun FileAttachmentCard(api: Api, att: Att) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember(att.id) { mutableStateOf(false) }
    var progress by remember(att.id) { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .padding(top = 8.dp)
            .widthIn(max = 310.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.SurfaceAlt)
            .border(0.7.dp, Brand.Border, RoundedCornerShape(12.dp))
            .clickable(enabled = !downloading) {
                downloading = true
                scope.launch {
                    val f = File(ctx.cacheDir, "files/${att.id}_${att.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                    val ok = if (f.exists() && f.length() > 0) Result.success(f)
                    else io { api.download(att.id, f) { p -> progress = p }; f }
                    downloading = false
                    ok.onSuccess {
                        if (!Installer.openFile(ctx, it, att.type)) {
                            Toast.makeText(ctx, "无可打开此文件的应用", Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure {
                        Toast.makeText(ctx, "下载失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcons.PackageBox(Brand.InkSoft, 18.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(att.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Brand.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sizeStr = Installer.formatSize(att.size)
                Text(if (downloading) "下载中…" else sizeStr.ifEmpty { "点击打开" }, fontSize = 11.5.sp, color = Brand.Muted)
            }
        }
        if (downloading && progress >= 0f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Brand.Ink,
                trackColor = Brand.Border
            )
        }
    }
}

@Composable
fun ApkCard(api: Api, att: Att) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = AppState.installs[att.id] ?: AppState.InstallStatus(sizeBytes = att.size)

    fun startInstall() {
        if (!Installer.canInstall(ctx)) {
            AppState.updateInstall(att.id) { it.copy(phase = AppState.InstallPhase.ERROR, message = "需开启安装应用权限") }
            Installer.openInstallPermissionSettings(ctx)
            return
        }
        AppState.updateInstall(att.id) { it.copy(phase = AppState.InstallPhase.DOWNLOADING, progress = 0f, message = "") }
        scope.launch {
            val res = io {
                val f = File(ctx.cacheDir, "apks/${att.id}.apk")
                if (!f.exists() || f.length() < 1024) {
                    api.download(att.id, f) { p ->
                        AppState.updateInstall(att.id) { it.copy(progress = p) }
                    }
                }
                AppState.updateInstall(att.id) { it.copy(phase = AppState.InstallPhase.VERIFYING, message = "校验签名…") }
                val info = Installer.inspect(ctx, f) ?: error("无效的 APK")
                val match = Installer.signatureMatchesInstalled(ctx, f, info.pkg)
                if (match == false) error("签名与已装版本不一致，已拦截")
                info to f
            }
            res.onSuccess { (info, f) -> Installer.install(ctx, f, att.id, info) }
                .onFailure { e -> AppState.updateInstall(att.id) { it.copy(phase = AppState.InstallPhase.ERROR, message = e.message ?: "安装失败") } }
        }
    }

    Column(
        Modifier
            .padding(top = 8.dp)
            .widthIn(max = 316.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(0.8.dp, Brand.Border, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Brand.Surface),
                contentAlignment = Alignment.Center
            ) {
                AppIcons.PackageBox(Brand.Ink, 19.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(st.label.ifEmpty { att.name }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    if (st.version.isNotEmpty()) add(verLabel(st.version))
                    val sz = Installer.formatSize(if (st.sizeBytes > 0) st.sizeBytes else att.size)
                    if (sz.isNotEmpty()) add(sz)
                    if (isEmpty()) add("APK")
                }.joinToString(" · ")
                Text(meta, fontSize = 12.sp, color = Brand.Muted, maxLines = 1)
            }

            when (st.phase) {
                AppState.InstallPhase.DOWNLOADING -> Text(if (st.progress >= 0) "${(st.progress * 100).toInt()}%" else "…", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Brand.Ink)
                AppState.InstallPhase.VERIFYING -> Text("校验中", fontSize = 12.sp, color = Brand.Muted)
                AppState.InstallPhase.AWAITING_CONFIRM -> Text("待确认", fontSize = 12.sp, color = Brand.Navy, fontWeight = FontWeight.Medium)
                AppState.InstallPhase.INSTALLING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Brand.Ink)
                AppState.InstallPhase.INSTALLED -> OutlinedButton(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(16.dp),
                    onClick = { if (st.pkg.isEmpty() || !Installer.launchApp(ctx, st.pkg)) Toast.makeText(ctx, "已安装", Toast.LENGTH_SHORT).show() },
                    modifier = Modifier.height(32.dp)
                ) { Text("打开", fontSize = 12.5.sp, color = Brand.Ink) }
                AppState.InstallPhase.IDLE, AppState.InstallPhase.ERROR -> Button(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(16.dp),
                    onClick = { startInstall() },
                    modifier = Modifier.height(32.dp)
                ) { Text(if (st.phase == AppState.InstallPhase.ERROR) "重试" else "安装", fontSize = 12.5.sp) }
            }
        }

        if (st.phase == AppState.InstallPhase.DOWNLOADING && st.progress >= 0f) {
            LinearProgressIndicator(
                progress = { st.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Brand.Ink,
                trackColor = Brand.Surface
            )
        }
        if (st.message.isNotEmpty()) {
            Text(
                st.message,
                fontSize = 11.5.sp,
                color = if (st.phase == AppState.InstallPhase.ERROR) Brand.Danger else Brand.Muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
