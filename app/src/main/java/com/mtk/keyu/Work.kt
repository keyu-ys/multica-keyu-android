package com.mtk.keyu

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// ============================================================
// 文案与分组
// ============================================================

object WorkText {
    /** 「与我相关」：需要我看/回的通知；状态、优先级、日期等变更只在「全部」里出现。 */
    val relevantTypes = setOf(
        "mentioned", "issue_assigned", "new_comment", "task_failed",
        "agent_blocked", "agent_completed", "task_completed", "review_requested",
        "quick_create_failed", "doc_mentioned", "doc_comment"
    )

    fun isRelevant(i: InboxItem) = i.type in relevantTypes

    fun statusLabel(status: String, name: String = ""): String = name.ifEmpty {
        when (status) {
            "backlog" -> "待规划"
            "todo" -> "待办"
            "in_progress" -> "进行中"
            "in_review" -> "待验收"
            "done" -> "已完成"
            "blocked" -> "已阻塞"
            "cancelled" -> "已取消"
            else -> status
        }
    }

    fun priorityLabel(p: String): String = when (p) {
        "urgent" -> "紧急"; "high" -> "高"; "medium" -> "中"; "low" -> "低"; "none", "" -> "无"
        else -> p
    }

    fun statusColors(status: String): Pair<Color, Color> = when (status) {
        "in_progress" -> Color(0xFFFFF4E5) to Color(0xFFB26A00)
        "in_review" -> Color(0xFFEFF3FF) to Color(0xFF3355CC)
        "done" -> Brand.SuccessSoft to Brand.Success
        "blocked" -> Color(0xFFFDECEA) to Brand.Danger
        "cancelled" -> Brand.Surface to Brand.Subtle
        else -> Brand.Surface to Brand.InkSoft
    }

    fun actorName(type: String, id: String, fallback: String = ""): String = when (type) {
        "agent" -> AppState.agentName(id) ?: fallback.ifEmpty { "智能体" }
        "member" -> AppState.members[id]?.name ?: fallback.ifEmpty { "同事" }
        "system" -> "系统"
        else -> fallback.ifEmpty { "Multica" }
    }

    fun actorAvatar(type: String, id: String, fallback: String = ""): String? = when (type) {
        "agent" -> AppState.agent(id)?.avatarUrl
        "member" -> AppState.members[id]?.avatarUrl ?: fallback
        else -> null
    }?.ifEmpty { null } ?: fallback.ifEmpty { null }

    private fun detail(json: String, key: String): String =
        runCatching { JSONObject(json).optString(key) }.getOrDefault("")

    fun inboxVerb(i: InboxItem): String = when (i.type) {
        "new_comment" -> "评论了"
        "mentioned" -> "提到了你"
        "issue_assigned" -> "把任务派给了你"
        "unassigned" -> "取消了你的指派"
        "assignee_changed" -> "更换了负责人"
        "status_changed" -> "状态改为 " + statusLabel(detail(i.details, "to").ifEmpty { i.issueStatus })
        "priority_changed" -> "调整了优先级"
        "start_date_changed", "due_date_changed" -> "调整了日期"
        "task_failed" -> "执行失败"
        "agent_blocked" -> "被阻塞，需要你处理"
        "agent_completed", "task_completed" -> "完成了"
        "reaction_added" -> "回应了你"
        "issue_created" -> "新建了任务"
        "issue_subscribed" -> "你已关注"
        "review_requested" -> "请你验收"
        "doc_comment" -> "评论了文档"
        "doc_mentioned" -> "在文档中提到了你"
        "quick_create_done" -> "快速创建完成"
        "quick_create_failed" -> "快速创建失败"
        else -> "有新动态"
    }

    fun activityText(e: TimelineEntry): String {
        val who = actorName(e.actorType, e.actorId, e.actorName)
        val verb = when (e.action) {
            "created" -> "创建了任务"
            "status_changed" -> "将状态改为 " + statusLabel(detail(e.details, "to"))
            "assignee_changed" -> {
                val toType = detail(e.details, "to_type"); val toId = detail(e.details, "to_id")
                when {
                    toId.isEmpty() -> "取消了指派"
                    toType == e.actorType && toId == e.actorId -> "认领了任务"
                    else -> "指派给 " + actorName(toType, toId)
                }
            }
            "priority_changed" -> "将优先级改为 " + priorityLabel(detail(e.details, "to"))
            "title_changed" -> "修改了标题"
            "description_changed", "description_updated" -> "更新了描述"
            "task_completed" -> "完成了一次执行"
            "task_failed" -> "执行失败"
            "duplicate_marked" -> "标记为重复任务"
            "duplicate_unmarked" -> "取消了重复标记"
            "duplicate_added", "duplicate_removed" -> "调整了重复任务"
            "squad_handoff" -> "移交了任务"
            "squad_leader_evaluated" -> "评估了任务"
            "environment_controlled" -> "操作了运行环境"
            "pr_auto_complete_changed" -> "调整了 PR 自动完成"
            "due_date_changed", "start_date_changed" -> "调整了日期"
            "label_added", "labels_changed" -> "调整了标签"
            "parent_changed" -> "调整了父任务"
            "project_changed" -> "调整了所属项目"
            else -> "更新了任务"
        }
        return "$who $verb"
    }
}

data class InboxGroup(
    val key: String,
    val latest: InboxItem,
    val items: List<InboxItem>,
) {
    val unread get() = items.count { !it.read }
}

fun groupInbox(items: List<InboxItem>, relevantOnly: Boolean): List<InboxGroup> =
    items.filter { !relevantOnly || WorkText.isRelevant(it) }
        .groupBy { it.issueId.ifEmpty { it.id } }
        .map { (k, v) -> InboxGroup(k, v.maxBy { it.createdAt }, v.sortedByDescending { it.createdAt }) }
        .sortedByDescending { it.latest.createdAt }

fun workUnreadOf(items: List<InboxItem>): Int =
    groupInbox(items, relevantOnly = true).count { it.unread > 0 }

// ============================================================
// 收件箱列表
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkInbox(
    api: Api,
    relevantOnly: Boolean,
    onRelevantChange: (Boolean) -> Unit,
    refreshKey: Int,
    onOpen: (InboxGroup) -> Unit,
    onAuthExpired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(WorkCache.items) }
    var loading by remember { mutableStateOf(items.isEmpty()) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    suspend fun load() {
        io {
            runCatching { api.members() }
            api.inbox()
        }.onSuccess {
            items = it
            WorkCache.items = it
            AppState.workUnread.intValue = workUnreadOf(it)
            error = ""
        }.onFailure { e ->
            if (e is AuthExpired) onAuthExpired() else error = e.message ?: "加载失败"
        }
        loading = false
    }

    LaunchedEffect(refreshKey) {
        while (true) {
            load()
            delay(8_000)
        }
    }

    val groups = remember(items, relevantOnly) { groupInbox(items, relevantOnly) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .background(Color(0xFFF2F3F7), CircleShape)
                    .padding(2.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterCapsuleItem("与我相关", relevantOnly) { onRelevantChange(true) }
                FilterCapsuleItem("全部", !relevantOnly) { onRelevantChange(false) }
            }
            Spacer(Modifier.weight(1f))
            if (error.isNotEmpty()) Text("网络异常，稍后重试", fontSize = 11.sp, color = Brand.Danger)
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Brand.Lavender)
                }
                groups.isEmpty() -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 160.dp)) {
                        Text("暂无动态", fontSize = 15.sp, color = Brand.InkSoft, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (relevantOnly) "有人 @你、派任务给你或智能体回复时会出现在这里" else "收件箱是空的",
                            fontSize = 12.sp, color = Brand.Subtle
                        )
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(groups, key = { it.key }) { g ->
                        SwipeArchiveRow(onArchive = {
                            val ids = g.items.map { it.id }.toSet()
                            items = items.filterNot { it.id in ids }
                            WorkCache.items = items
                            AppState.workUnread.intValue = workUnreadOf(items)
                            scope.launch { io { ids.forEach { api.archiveInbox(it) } } }
                        }) {
                            InboxCard(g) {
                                // 乐观标记已读
                                val ids = g.items.map { it.id }.toSet()
                                items = items.map { if (it.id in ids) it.copy(read = true) else it }
                                WorkCache.items = items
                                AppState.workUnread.intValue = workUnreadOf(items)
                                if (g.latest.issueId.isEmpty()) {
                                    scope.launch { io { ids.forEach { api.markInboxRead(it) } } }
                                }
                                onOpen(g)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 进程内缓存，切页签回来时先显示旧数据再刷新。 */
object WorkCache {
    @Volatile var items: List<InboxItem> = emptyList()
}

@Composable
private fun FilterCapsuleItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .then(
                if (selected) Modifier.shadow(
                    elevation = 1.2.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x12000000),
                    spotColor = Color(0x1F000000)
                ) else Modifier
            )
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Brand.Ink else Brand.Muted,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeArchiveRow(onArchive: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) { onArchive(); true } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(Brand.LavenderSoft).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) { Text("归档", color = Brand.Navy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    ) { content() }
}

@Composable
private fun InboxCard(g: InboxGroup, onClick: () -> Unit) {
    val i = g.latest
    val unread = g.unread > 0
    val actor = WorkText.actorName(i.actorType, i.actorId)
    val preview = Notify.cleanSummary(i.body, false, 0).takeIf { i.body.isNotBlank() }
    val failed = i.type == "task_failed" || i.type == "quick_create_failed" || i.type == "agent_blocked"
    val noFontPad = PlatformTextStyle(includeFontPadding = false)
    Column(Modifier.fillMaxWidth().background(if (unread) Brand.SurfaceAlt else Color.White)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(actor, WorkText.actorAvatar(i.actorType, i.actorId), size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        i.title.ifEmpty { "（无标题）" },
                        fontSize = 15.5.sp,
                        lineHeight = 21.sp,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                        color = Brand.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(platformStyle = noFontPad),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        timeLabel(i.createdAt),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = Brand.Subtle,
                        maxLines = 1,
                        softWrap = false,
                        style = TextStyle(platformStyle = noFontPad)
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (preview != null && i.type in setOf("new_comment", "mentioned")) "$actor：$preview"
                        else "$actor ${WorkText.inboxVerb(i)}" + (preview?.let { "：$it" } ?: ""),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = if (failed) Brand.Danger else Brand.Muted,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(platformStyle = noFontPad),
                        modifier = Modifier.weight(1f)
                    )
                    if (unread) {
                        Spacer(Modifier.width(10.dp))
                        CountBadge(g.unread)
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 72.dp, end = 16.dp),
            color = Color(0xFFF0F1F4),
            thickness = 0.5.dp
        )
    }
}

// ============================================================
// 任务对话页
// ============================================================

@Composable
fun IssueScreen(api: Api, issueId: String, titleHint: String, identifierHint: String, onBack: () -> Unit, onAuthExpired: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    val myId = remember { prefs.getString("user_id", "").orEmpty() }

    var issue by remember { mutableStateOf<IssueBrief?>(null) }
    var entries by remember { mutableStateOf(listOf<TimelineEntry>()) }
    var active by remember { mutableStateOf<PendingTask?>(null) }
    var liveMsgs by remember { mutableStateOf(listOf<TaskMsg>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showDesc by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(listOf<Uri>()) }
    var sending by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var steerMode by remember { mutableStateOf(true) }
    var replyTo by remember { mutableStateOf<TimelineEntry?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    // 返回键：先关掉「回复」引用条/描述展开，再退出任务页
    BackHandler(onBack = {
        when {
            replyTo != null -> replyTo = null
            showDesc -> showDesc = false
            else -> onBack()
        }
    })
    DisposableEffect(issueId) {
        AppState.foregroundIssueId = issueId
        onDispose { if (AppState.foregroundIssueId == issueId) AppState.foregroundIssueId = null }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        images = (images + uris).distinct().take(6)
    }

    LaunchedEffect(issueId, reload) {
        var round = 0
        var lastTail = ""
        while (true) {
            io {
                runCatching { api.members() }
                val iss = if (round % 4 == 0 || issue == null) api.issue(issueId) else issue
                Triple(iss, api.timeline(issueId), api.activeIssueTask(issueId))
            }.onSuccess { (iss, tl, act) ->
                issue = iss
                val tail = tl.lastOrNull()?.id.orEmpty()
                val atBottom = listState.firstVisibleItemIndex <= 1
                entries = tl
                if (act?.taskId != active?.taskId) liveMsgs = emptyList()
                active = act
                if (act != null) {
                    val since = liveMsgs.lastOrNull()?.seq ?: 0
                    val delta = io { api.taskMessages(act.taskId, since) }.getOrNull().orEmpty()
                    if (delta.isNotEmpty()) liveMsgs = (liveMsgs + delta).associateBy { it.seq }.values.sortedBy { it.seq }
                }
                if (tail != lastTail) {
                    io { api.markIssueInboxRead(issueId) }
                    WorkCache.items = WorkCache.items.map { if (it.issueId == issueId) it.copy(read = true) else it }
                    AppState.workUnread.intValue = workUnreadOf(WorkCache.items)
                    if (lastTail.isNotEmpty() && atBottom) {
                        delay(40)
                        listState.animateScrollToItem(0)
                    }
                    lastTail = tail
                }
                error = ""
            }.onFailure { e ->
                if (e is AuthExpired) { onAuthExpired(); return@LaunchedEffect }
                error = e.message ?: "加载失败"
            }
            loading = false
            round++
            delay(if (active != null || sending) 3_000 else 8_000)
        }
    }

    val iss = issue
    val assigneeAgent = iss?.takeIf { it.assigneeType == "agent" }?.let { AppState.agent(it.assigneeId) }
    val assigneeName = iss?.let { if (it.assigneeId.isEmpty()) "未指派" else WorkText.actorName(it.assigneeType, it.assigneeId) }.orEmpty()
    val runningAgentName = active?.chatSessionId?.let { AppState.agentName(it) } ?: assigneeAgent?.name ?: "智能体"
    val byId = remember(entries) { entries.associateBy { it.id } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .systemBarsPadding()
            .imePadding()
    ) {
        // 顶栏：返回 + 编号标题 + 状态/负责人
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Brand.Ink) }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = iss != null) { showDesc = true }.padding(vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        iss?.title ?: titleHint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (iss != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (bg, fg) = WorkText.statusColors(iss.status)
                        Text(
                            WorkText.statusLabel(iss.status, iss.statusName), fontSize = 10.5.sp, color = fg,
                            modifier = Modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("负责：$assigneeName", fontSize = 11.sp, color = Brand.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text("详情 ›", fontSize = 11.sp, color = Brand.Navy)
                    }
                }
            }
        }
        HorizontalDivider(color = Brand.Border, thickness = 0.5.dp)

        // 时间线
        Box(Modifier.weight(1f).fillMaxWidth().background(Brand.SurfaceAlt)) {
            when {
                loading && entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Brand.Lavender)
                }
                entries.isEmpty() && error.isNotEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("加载失败", color = Brand.InkSoft, fontSize = 14.sp)
                    Text(error.take(80), color = Brand.Subtle, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
                    TextButton(onClick = { loading = true; reload++ }) { Text("重试", color = Brand.Navy) }
                }
                // 倒序布局：一打开就停在最新消息，不需要滚动过去
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(entries.asReversed(), key = { it.id }) { e ->
                        if (e.kind == "comment") {
                            CommentItem(api, e, mine = e.actorType == "member" && e.actorId == myId, parent = byId[e.parentId], onReply = { replyTo = e })
                        } else {
                            Text(
                                "— ${WorkText.activityText(e)} · ${timeLabel(e.createdAt)} —",
                                fontSize = 11.sp, color = Brand.Subtle, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        if (showDesc && iss != null) {
            IssueDetailSheet(api, iss, assigneeName) { showDesc = false }
        }

        // 底部：运行状态 + 输入
        HorizontalDivider(color = Brand.Border, thickness = 0.5.dp)
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 10.dp, vertical = 8.dp)) {
            val act = active
            if (act != null) {
                val digest = remember(act, liveMsgs) { extractThinkingDigest(act, liveMsgs) }
                Row(
                    Modifier.fillMaxWidth().background(Brand.LavenderSoft, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.6.dp, color = Brand.Navy)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$runningAgentName ${if (act.status == "queued") "排队中" else "处理中"} · ${digest.stageLabel}",
                        fontSize = 12.sp, color = Brand.Navy, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (stopping) "停止中…" else "停止", fontSize = 12.sp, color = Brand.Danger, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !stopping) {
                            stopping = true
                            scope.launch {
                                io { api.cancelTask(act.taskId) }
                                    .onSuccess { active = null; Toast.makeText(ctx, "已停止", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(ctx, it.message ?: "停止失败", Toast.LENGTH_SHORT).show() }
                                stopping = false
                            }
                        }.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (act.canSupplement) {
                    Row(Modifier.padding(top = 6.dp, start = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip("补充给这一轮", steerMode) { steerMode = true }
                        ModeChip("等它做完再处理", !steerMode) { steerMode = false }
                    }
                }
                Spacer(Modifier.height(6.dp))
            } else if (iss != null) {
                Text(
                    when {
                        assigneeAgent != null -> "● 发送后将唤起 ${assigneeAgent.name}"
                        else -> "发送为评论；@智能体 可唤起对应智能体"
                    },
                    fontSize = 11.sp, color = if (assigneeAgent != null) Brand.Success else Brand.Muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
            }
            replyTo?.let { r ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp).background(Brand.Surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "回复 ${WorkText.actorName(r.actorType, r.actorId, r.actorName)}：${Notify.cleanSummary(r.content, false, 0)}",
                        fontSize = 11.sp, color = Brand.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Text("✕", fontSize = 12.sp, color = Brand.Subtle, modifier = Modifier.clickable { replyTo = null }.padding(start = 8.dp))
                }
            }
            if (images.isNotEmpty()) {
                Row(Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.forEach { u -> PickedImageThumb(u) { images = images - u } }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Add, "图片", tint = Brand.Muted, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.weight(1f).heightIn(min = 36.dp).background(Brand.Surface, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (input.isEmpty()) Text("回复这个任务…", fontSize = 14.sp, color = Brand.Subtle)
                    BasicTextField(
                        value = input, onValueChange = { input = it },
                        textStyle = TextStyle(fontSize = 14.sp, color = Brand.Ink, lineHeight = 20.sp),
                        cursorBrush = SolidColor(Brand.Navy),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(6.dp))
                val canSend = (input.isNotBlank() || images.isNotEmpty()) && !sending && iss != null
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(if (canSend) Brand.Ink else Color(0xFFD6DAE2))
                        .clickable(enabled = canSend) {
                            val text = input.trim()
                            val imgs = images
                            val parent = replyTo?.let { it.parentId.ifEmpty { it.id } }
                            val steer = active?.canSupplement == true && steerMode
                            sending = true
                            scope.launch {
                                io {
                                    val ids = imgs.mapIndexed { i, u ->
                                        val name = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                                            if (c.moveToFirst()) c.getString(0) else null
                                        } ?: "image_$i.jpg"
                                        val mime = ctx.contentResolver.getType(u) ?: "image/jpeg"
                                        api.uploadForIssue(issueId, name, mime, ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() })
                                    }
                                    api.comment(issueId, text.ifEmpty { "（见图片）" }, ids, steer, parent)
                                }.onSuccess {
                                    input = ""; images = emptyList(); replyTo = null
                                    reload++
                                }.onFailure { e ->
                                    if (e is AuthExpired) onAuthExpired()
                                    else Toast.makeText(ctx, "发送失败：${e.message}", Toast.LENGTH_SHORT).show()
                                }
                                sending = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.Default.KeyboardArrowUp, "发送", tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueDetailSheet(api: Api, iss: IssueBrief, assigneeName: String, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.White,
        scrimColor = Color(0x66000000),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                .padding(horizontal = 20.dp)
        ) {
            Text(iss.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Brand.Ink, lineHeight = 24.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (bg, fg) = WorkText.statusColors(iss.status)
                Text(
                    WorkText.statusLabel(iss.status, iss.statusName), fontSize = 11.sp, color = fg,
                    modifier = Modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("负责：$assigneeName", fontSize = 12.sp, color = Brand.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (iss.identifier.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(iss.identifier, fontSize = 11.sp, color = Brand.Subtle)
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
                if (iss.description.isBlank()) Text("暂无描述", fontSize = 13.sp, color = Brand.Subtle)
                else SelectionContainer { Markdown(iss.description, api = api) }
            }
        }
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 11.sp,
        color = if (selected) Brand.Navy else Brand.Muted,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .border(0.8.dp, if (selected) Brand.Navy else Brand.Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun CommentItem(api: Api, e: TimelineEntry, mine: Boolean, parent: TimelineEntry?, onReply: () -> Unit) {
    val name = WorkText.actorName(e.actorType, e.actorId, e.actorName)
    val text = e.content.replace(Regex("!(?:file)?\\[[^\\]]*]\\(/api/attachments/[^)]*\\)"), "").trim()
    val quote: @Composable () -> Unit = {
        if (parent != null) {
            Text(
                "↪ 回复 ${WorkText.actorName(parent.actorType, parent.actorId, parent.actorName)}：${Notify.cleanSummary(parent.content, false, 0)}",
                fontSize = 11.sp, color = Brand.Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
    val referenced = remember(e.id, e.content) {
        Regex("/api/attachments/([0-9a-fA-F-]{36})/").findAll(e.content).map { it.groupValues[1] }.toSet()
    }
    val rest = e.atts.filterNot { it.id in referenced }
    val restMedia: @Composable () -> Unit = {
        val imgs = rest.filter { it.isImage() }
        if (imgs.isNotEmpty()) Box(Modifier.padding(top = 6.dp)) { MediaGallery(api, imgs.map { it.toMediaRef() }) }
        rest.filterNot { it.isImage() }.forEach { a ->
            Box(Modifier.padding(top = 6.dp)) {
                when {
                    a.isVideo() -> VideoCard(api, a.toMediaRef())
                    a.name.endsWith(".apk", true) -> ApkCard(api, a)
                    else -> FileAttachmentCard(api, a)
                }
            }
        }
    }
    if (e.deleted) {
        Text("（评论已删除）", fontSize = 11.sp, color = Brand.Subtle, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        return
    }
    if (mine) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Box(Modifier.widthIn(max = 280.dp)) { quote() }
            Column(
                Modifier.widthIn(max = 300.dp)
                    .background(Brand.LavenderSoft, RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                    .padding(horizontal = 13.dp, vertical = 9.dp)
            ) {
                if (text.isNotEmpty() && text != "（见图片）") SelectionContainer { Markdown(e.content.trim(), api = api, atts = e.atts) }
                restMedia()
            }
            Text(timeLabel(e.createdAt), fontSize = 10.5.sp, color = Brand.Subtle, modifier = Modifier.padding(top = 3.dp, end = 4.dp))
        }
    } else {
        Row(Modifier.fillMaxWidth()) {
            Avatar(name, WorkText.actorAvatar(e.actorType, e.actorId, e.actorAvatar), size = 28.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Brand.InkSoft)
                    Spacer(Modifier.width(6.dp))
                    Text(timeLabel(e.createdAt), fontSize = 10.5.sp, color = Brand.Subtle)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "回复", fontSize = 11.sp, color = Brand.Subtle,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onReply).padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(3.dp))
                quote()
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(6.dp, 16.dp, 16.dp, 16.dp))
                        .border(0.5.dp, Brand.Border, RoundedCornerShape(6.dp, 16.dp, 16.dp, 16.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    if (e.content.isNotBlank() || e.atts.isEmpty()) SelectionContainer { Markdown(e.content.trim().ifEmpty { "（空）" }, api = api, atts = e.atts) }
                    restMedia()
                }
            }
        }
    }
}

/** 未读数徽标：固定最小尺寸的胶囊，单个数字时为正圆，不随系统字号变形。 */
@Composable
fun CountBadge(n: Int, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fixed = with(density) { (10f / fontScale).sp }
    Box(
        modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .background(Brand.Danger, RoundedCornerShape(50))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (n > 99) "99+" else "$n",
            fontSize = fixed,
            lineHeight = fixed,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
        )
    }
}
