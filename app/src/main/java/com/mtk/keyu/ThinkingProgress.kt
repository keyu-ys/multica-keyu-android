package com.mtk.keyu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * 面向用户的精炼思考/过程节点（Codex 风格）：
 * - 有真实 thinking 流时：直接展示模型思考小标题/关键推演结论（点按可展开完整思考段）；
 * - 无 thinking 流时：只提炼有信息量的具体动作（如实际查看/修改了哪些文件、具体执行意图、阶段说明），
 *   拒绝笼统重复的套话标签，也拒绝倾倒底层命令参数/JSON/日志。
 */
data class ThinkingNode(
    val title: String,
    val detail: String = "",
    val isThinking: Boolean = true,
)

data class LiveThinkingDigest(
    val stageLabel: String,
    val isStatic: Boolean,
    val nodes: List<ThinkingNode>,
    val streamingAnswer: String,
)

private data class CoalescedTaskItem(
    val seq: Int,
    val type: String,
    val tool: String,
    val content: String,
    val summary: String,
    val target: String,
    val command: String,
)

private enum class ToolKind { READ, SEARCH, EDIT, WRITE, COMMAND, COLLAB, OTHER }

private fun classifyTool(tool: String): ToolKind {
    val k = tool.trim().lowercase()
    return when {
        k == "write" || k == "write_file" || k == "write_to_file" -> ToolKind.WRITE
        k.contains("edit") || k.contains("patch") || k.contains("replace") -> ToolKind.EDIT
        k.contains("grep") || k.contains("search") || k.contains("web") || k.contains("url") -> ToolKind.SEARCH
        k.contains("read") || k.contains("view") || k.contains("glob") || k.contains("list") || k.contains("find") -> ToolKind.READ
        k.contains("cmd") || k.contains("command") || k.contains("bash") || k.contains("exec") || k.contains("shell") || k.contains("terminal") -> ToolKind.COMMAND
        k.contains("subagent") || k.contains("workflow") || k == "task" || k == "agent" -> ToolKind.COLLAB
        else -> ToolKind.OTHER
    }
}

private val BOLD_HEADING_LINE_RE = Regex("^\\s*(?:[-*•]\\s*)?\\*\\*([^*\\n]{2,72})\\*\\*(?:\\s*[:：]\\s*(.*))?$")
private val MD_HEADING_RE = Regex("^\\s*#{1,4}\\s+(.{2,72})$")
private val INLINE_BOLD_START_RE = Regex("^\\s*\\*\\*([^*\\n]{3,60})\\*\\*(?:\\s*[:：]?\\s*(.*))?", RegexOption.DOT_MATCHES_ALL)

private fun cleanText(raw: String, maxChars: Int = 64): String {
    val s = raw
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+)\\*"), "$1")
        .replace(Regex("^\\s*(?:#{1,6}\\s+|[-*•>]+\\s+|\\d+[.)、]\\s+)"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd(':', '：', ';', '；', ',')
    if (s.length <= maxChars) return s
    val cutCandidates = listOf("。", "！", "？", ". ", "! ", "? ")
    for (sep in cutCandidates) {
        val idx = s.indexOf(sep)
        if (idx in 10..maxChars) {
            return s.substring(0, idx + if (sep.length == 1) 1 else 0).trim()
        }
    }
    return s.take(maxChars - 1).trimEnd() + "…"
}

private fun cleanDetail(raw: String, maxChars: Int = 360): String {
    val s = raw
        .replace(Regex("```[\\s\\S]*?```"), "")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()
    return if (s.length <= maxChars) s else s.take(maxChars - 1).trimEnd() + "…"
}

/** 从一段真实 thinking 文本里提炼出带可选详情的思考节点（Codex 风格：小标题为主，正文可点开看）。 */
private fun extractThinkingBlocks(thinkingText: String): List<ThinkingNode> {
    val trimmed = thinkingText.trim()
    if (trimmed.isEmpty()) return emptyList()

    val nodes = mutableListOf<ThinkingNode>()
    val lines = trimmed.lines()
    var curTitle: String? = null
    val curBody = StringBuilder()

    fun flushCurrent() {
        val t = curTitle?.trim().orEmpty()
        if (t.length >= 2) {
            val detail = cleanDetail(curBody.toString())
            nodes.add(ThinkingNode(title = t, detail = if (detail != t) detail else "", isThinking = true))
        }
        curTitle = null
        curBody.clear()
    }

    var foundStructuredHeading = false
    for (line in lines) {
        val l = line.trim()
        if (l.isEmpty()) continue
        val mBold = BOLD_HEADING_LINE_RE.matchEntire(l)
        if (mBold != null) {
            foundStructuredHeading = true
            flushCurrent()
            curTitle = cleanText(mBold.groupValues[1], 60)
            val rest = mBold.groupValues.getOrNull(2)?.trim().orEmpty()
            if (rest.isNotEmpty()) curBody.append(rest).append('\n')
            continue
        }
        val mHash = MD_HEADING_RE.matchEntire(l)
        if (mHash != null) {
            foundStructuredHeading = true
            flushCurrent()
            curTitle = cleanText(mHash.groupValues[1], 60)
            continue
        }
        if (foundStructuredHeading) {
            curBody.append(l).append('\n')
        }
    }
    if (foundStructuredHeading) {
        flushCurrent()
        if (nodes.isNotEmpty()) return nodes
    }

    // 检查段落开头的 **小标题**
    val paragraphs = trimmed.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    val paraNodes = mutableListOf<ThinkingNode>()
    for (p in paragraphs) {
        val m = INLINE_BOLD_START_RE.find(p)
        if (m != null) {
            val t = cleanText(m.groupValues[1], 60)
            val rest = cleanDetail(m.groupValues.getOrNull(2).orEmpty())
            if (t.length >= 2) paraNodes.add(ThinkingNode(t, rest, isThinking = true))
        }
    }
    if (paraNodes.isNotEmpty()) return paraNodes

    // 兜底：无显式标题时，取第一句有实质内容的思考作标题，剩余正文收纳进可点开详情
    val firstLine = lines
        .map { it.trim() }
        .firstOrNull { line ->
            line.length >= 4 &&
                !line.startsWith("```") &&
                !line.startsWith("{") &&
                !line.startsWith("[") &&
                !line.matches(Regex("^(嗯|好|好的|ok|okay|hmm|let me see|alright)[,.，。!！?？]*$", RegexOption.IGNORE_CASE))
        }
        ?: lines.firstOrNull { it.isNotBlank() }
        ?: return emptyList()

    val title = cleanText(firstLine, 62)
    if (title.length < 2) return emptyList()
    val fullDetail = cleanDetail(trimmed)
    val detail = if (fullDetail.length > title.length + 12) fullDetail else ""
    return listOf(ThinkingNode(title = title, detail = detail, isThinking = true))
}

/** 根据命令内容推断有信息量的高层意图；琐碎探测命令（ls/find/ps/rm 等）返回 null 不刷屏。 */
private fun inferCommandIntent(summary: String, command: String): String? {
    val cmd = command.lowercase()
    // 琐碎清理/探测命令，在没有明确高价值 summary 时直接跳过
    val isTrivialHousekeeping = cmd.startsWith("rm ") || cmd.startsWith("ls ") || cmd.startsWith("ps ") ||
        (cmd.contains("ls -l") && !cmd.contains("gradlew") && !cmd.contains("adb install"))

    if (summary.isNotBlank()) {
        val clean = cleanText(summary, 52)
        val low = clean.lowercase()
        val isGenericProbe = low.startsWith("check modification") ||
            low.startsWith("check kotlin file timestamps") ||
            low.startsWith("clean up temporary") ||
            low.startsWith("check for any running") ||
            low.startsWith("list ")
        if (!isGenericProbe && clean.length >= 3) return clean
    }

    if (isTrivialHousekeeping) return null

    return when {
        cmd.contains("gradlew") && (cmd.contains("assemblerelease") || cmd.contains("assembledebug") || cmd.contains("build")) ->
            if (cmd.contains("adb install")) "编译打包并安装到真机" else "编译 Android 安装包"
        cmd.contains("adb install") -> "安装 APK 到真机"
        cmd.contains("screencap") -> "真机截屏核验界面"
        cmd.contains("flutter build") -> "编译 Flutter 安装包"
        cmd.contains("flutter test") || cmd.contains("go test") || cmd.contains("pytest") || cmd.contains("npm test") || cmd.contains("pnpm test") -> "运行测试验证"
        cmd.contains("attachment download") -> "下载消息附件"
        cmd.contains("attachment upload") -> "上传交付附件"
        cmd.contains("git diff") || cmd.contains("git status") || cmd.contains("git log") -> "核对代码变更"
        cmd.contains("grep ") || cmd.contains("rg ") -> null // 零散 grep 归为静默探测，不刷节点
        else -> null
    }
}

private fun formatFileList(verb: String, files: Collection<String>): String {
    val list = files.filter { it.isNotBlank() }.distinct()
    return when {
        list.isEmpty() -> ""
        list.size == 1 -> "$verb ${list[0]}"
        list.size == 2 -> "$verb ${list[0]}、${list[1]}"
        else -> "$verb ${list[0]}、${list[1]} 等 ${list.size} 个文件"
    }
}

private fun liveStageLabelForTool(item: CoalescedTaskItem): String {
    val kind = classifyTool(item.tool)
    return when (kind) {
        ToolKind.READ -> if (item.target.isNotEmpty()) "查看 ${item.target}" else "查看文件"
        ToolKind.SEARCH -> if (item.target.isNotEmpty()) "搜索 ${item.target.take(18)}" else "搜索信息"
        ToolKind.EDIT -> if (item.target.isNotEmpty()) "修改 ${item.target}" else "修改代码"
        ToolKind.WRITE -> if (item.target.isNotEmpty()) "写入 ${item.target}" else "写入文件"
        ToolKind.COLLAB -> if (item.summary.isNotEmpty()) item.summary.take(24) else "协作分析"
        ToolKind.COMMAND -> inferCommandIntent(item.summary, item.command)?.take(24) ?: "执行命令"
        ToolKind.OTHER -> if (item.summary.isNotEmpty()) item.summary.take(24) else "处理中"
    }
}

private fun coalesceTaskMessages(messages: List<TaskMsg>): List<CoalescedTaskItem> {
    if (messages.isEmpty()) return emptyList()
    val sorted = messages.sortedBy { it.seq }
    val out = mutableListOf<CoalescedTaskItem>()
    for (m in sorted) {
        val prev = out.lastOrNull()
        if (prev != null && (prev.type == "thinking" || prev.type == "text") && prev.type == m.type) {
            out[out.lastIndex] = prev.copy(content = prev.content + m.content)
        } else {
            out.add(CoalescedTaskItem(m.seq, m.type, m.tool, m.content, m.summary, m.target, m.command))
        }
    }
    return out
}

/** 从原始任务事件流中提炼 Codex 风格的真实思考与关键过程节点。 */
fun extractThinkingDigest(
    pending: PendingTask?,
    rawMessages: List<TaskMsg>,
): LiveThinkingDigest {
    val items = coalesceTaskMessages(rawMessages)
    val effectiveStatus = when {
        pending?.status == "deferred" -> "deferred"
        items.isNotEmpty() -> "running"
        else -> pending?.status.orEmpty()
    }

    val latestMeaningful = items.lastOrNull { it.type != "error" && it.type != "tool_result" }
    val (stageLabel, isStatic) = when (effectiveStatus) {
        "deferred" -> "等待重试" to false
        "waiting_local_directory" -> "等待目录空闲" to true
        "queued" -> "排队中" to false
        "dispatched" -> "正在启动" to false
        else -> when (latestMeaningful?.type) {
            "text" -> "正在回复" to false
            "tool_use" -> liveStageLabelForTool(latestMeaningful) to false
            else -> "思考中" to false
        }
    }

    val lastNonTextIdx = items.indexOfLast { it.type != "text" }
    val streamingAnswer = if (items.isNotEmpty() && lastNonTextIdx < items.lastIndex) {
        items.subList(lastNonTextIdx + 1, items.size)
            .filter { it.type == "text" }
            .joinToString("") { it.content }
            .trim()
    } else ""

    val nodes = extractThinkingNodesFromCoalesced(items, lastNonTextIdx)
    return LiveThinkingDigest(
        stageLabel = stageLabel,
        isStatic = isStatic,
        nodes = nodes,
        streamingAnswer = streamingAnswer,
    )
}

fun extractThinkingNodes(rawMessages: List<TaskMsg>): List<ThinkingNode> {
    val items = coalesceTaskMessages(rawMessages)
    val lastNonTextIdx = items.indexOfLast { it.type != "text" }
    return extractThinkingNodesFromCoalesced(items, lastNonTextIdx)
}

private fun extractThinkingNodesFromCoalesced(
    items: List<CoalescedTaskItem>,
    lastNonTextIdx: Int,
): List<ThinkingNode> {
    if (items.isEmpty()) return emptyList()

    val hasAnyThinking = items.any { it.type == "thinking" && it.content.isNotBlank() }
    val result = mutableListOf<ThinkingNode>()
    val seenReadFiles = linkedSetOf<String>()
    val seenCommandLabels = mutableSetOf<String>()

    fun pushNode(node: ThinkingNode) {
        val t = node.title.trim()
        if (t.length < 2) return
        if (result.lastOrNull()?.title == t) return
        // 非思考类节点若在最近 4 条内已经出现过完全相同的标题，直接跳过，避免重复表达
        if (!node.isThinking && result.takeLast(4).any { it.title == t }) return
        result.add(node.copy(title = t))
    }

    var i = 0
    while (i < items.size) {
        val item = items[i]
        when (item.type) {
            "thinking" -> {
                for (n in extractThinkingBlocks(item.content)) {
                    pushNode(n)
                }
            }
            "text" -> {
                // 仅收录最终回复之前的阶段性真实说明，忽略末尾正文
                if (i < lastNonTextIdx && item.content.isNotBlank()) {
                    val firstLine = item.content.lines().firstOrNull { it.trim().length >= 4 }
                    if (firstLine != null) {
                        val title = cleanText(firstLine, 60)
                        val detail = cleanDetail(item.content)
                        pushNode(ThinkingNode(title, if (detail.length > title.length + 12) detail else "", isThinking = true))
                    }
                }
            }
            "tool_use" -> {
                val kind = classifyTool(item.tool)
                when (kind) {
                    ToolKind.READ -> {
                        val batch = linkedSetOf<String>()
                        if (item.target.isNotEmpty()) batch.add(item.target)
                        while (i + 1 < items.size) {
                            val next = items[i + 1]
                            if (next.type == "tool_result") { i++; continue }
                            if (next.type == "tool_use" && classifyTool(next.tool) == ToolKind.READ) {
                                if (next.target.isNotEmpty()) batch.add(next.target)
                                i++
                                continue
                            }
                            break
                        }
                        if (!hasAnyThinking) {
                            val fresh = batch.filter { it !in seenReadFiles }
                            if (fresh.isNotEmpty()) {
                                seenReadFiles.addAll(fresh)
                                val label = formatFileList("查看", fresh)
                                if (label.isNotEmpty()) pushNode(ThinkingNode(label, isThinking = false))
                            }
                        }
                    }
                    ToolKind.EDIT, ToolKind.WRITE -> {
                        val edited = linkedSetOf<String>()
                        val created = linkedSetOf<String>()
                        if (item.target.isNotEmpty()) {
                            if (kind == ToolKind.WRITE) created.add(item.target) else edited.add(item.target)
                        }
                        while (i + 1 < items.size) {
                            val next = items[i + 1]
                            if (next.type == "tool_result") { i++; continue }
                            val nk = if (next.type == "tool_use") classifyTool(next.tool) else null
                            if (nk == ToolKind.EDIT || nk == ToolKind.WRITE) {
                                if (next.target.isNotEmpty()) {
                                    if (nk == ToolKind.WRITE) created.add(next.target) else edited.add(next.target)
                                }
                                i++
                                continue
                            }
                            break
                        }
                        val allTouched = (created + edited).distinct()
                        val verb = if (created.isNotEmpty() && edited.isEmpty()) "写入" else "修改"
                        val label = formatFileList(verb, allTouched)
                        if (label.isNotEmpty()) pushNode(ThinkingNode(label, isThinking = false))
                    }
                    ToolKind.SEARCH -> {
                        val queries = linkedSetOf<String>()
                        if (item.target.isNotEmpty()) queries.add(item.target)
                        while (i + 1 < items.size) {
                            val next = items[i + 1]
                            if (next.type == "tool_result") { i++; continue }
                            if (next.type == "tool_use" && classifyTool(next.tool) == ToolKind.SEARCH) {
                                if (next.target.isNotEmpty()) queries.add(next.target)
                                i++
                                continue
                            }
                            break
                        }
                        if (!hasAnyThinking && queries.isNotEmpty()) {
                            val q = queries.first().take(28)
                            val label = if (queries.size > 1) "检索「$q」等 ${queries.size} 项" else "检索「$q」"
                            pushNode(ThinkingNode(label, isThinking = false))
                        }
                    }
                    ToolKind.COMMAND -> {
                        val intent = inferCommandIntent(item.summary, item.command)
                        if (intent != null) {
                            val isMajor = intent.contains("编译") || intent.contains("安装") || intent.contains("测试") ||
                                intent.contains("build", ignoreCase = true) || intent.contains("install", ignoreCase = true)
                            if (!hasAnyThinking || isMajor) {
                                // 避免同义命令在整个任务里被机械重复多次
                                if (intent !in seenCommandLabels || isMajor) {
                                    seenCommandLabels.add(intent)
                                    pushNode(ThinkingNode(intent, isThinking = false))
                                }
                            }
                        }
                    }
                    ToolKind.COLLAB -> {
                        val label = if (item.summary.isNotEmpty()) "协作：${cleanText(item.summary, 42)}" else "委派子任务分析"
                        pushNode(ThinkingNode(label, isThinking = false))
                    }
                    ToolKind.OTHER -> {
                        if (!hasAnyThinking && item.summary.isNotEmpty()) {
                            pushNode(ThinkingNode(cleanText(item.summary, 48), isThinking = false))
                        }
                    }
                }
            }
        }
        i++
    }
    return result
}

private fun parseEpochMs(iso: String?): Long? = runCatching {
    if (iso.isNullOrBlank()) null else Instant.parse(iso).toEpochMilli()
}.getOrNull()

private fun formatDurationSec(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

/** 对话区运行中视图（Codex 风格：简洁状态头 + 关键思考节点竖轴，不堆底层细节）。 */
@Composable
fun LiveThinkingBlock(
    pendingTask: PendingTask?,
    digest: LiveThinkingDigest,
) {
    val anchorMs = remember(pendingTask?.taskId) {
        parseEpochMs(pendingTask?.createdAt) ?: System.currentTimeMillis()
    }
    var stableAnchor by remember(pendingTask?.taskId) { mutableLongStateOf(anchorMs) }
    LaunchedEffect(pendingTask?.createdAt) {
        val parsed = parseEpochMs(pendingTask?.createdAt)
        if (parsed != null && parsed < stableAnchor) stableAnchor = parsed
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val elapsedSec = ((nowMs - stableAnchor) / 1000L).coerceAtLeast(0L)

    val pulse = rememberInfiniteTransition(label = "thinkPulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.28f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dotAlpha"
    )

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // 状态头：呼吸圆点 + 当前阶段 + 计时（停止统一走底部输入栏右侧 ■ 按钮）
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .alpha(if (digest.isStatic) 0.5f else dotAlpha)
                    .background(Brand.Navy, CircleShape)
            )
            Text(
                digest.stageLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Brand.InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (elapsedSec > 0) {
                Text(
                    "· ${formatDurationSec(elapsedSec)}",
                    fontSize = 12.sp,
                    color = Brand.Subtle
                )
            }
        }

        if (digest.nodes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ThinkingMilestoneRail(
                nodes = digest.nodes,
                isLive = true,
                activeDotAlpha = dotAlpha,
                maxVisibleLive = 6
            )
        }

        if (digest.streamingAnswer.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Markdown(digest.streamingAnswer)
        }
    }
}

/** 已完成回复上方的极简可折叠思考节点条（默认收起，单行不占空间）。 */
@Composable
fun SettledThinkingSummary(
    nodes: List<ThinkingNode>,
    elapsedMs: Long?,
) {
    if (nodes.isEmpty()) return
    var expanded by remember(nodes.size) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val label = buildString {
                if (elapsedMs != null && elapsedMs >= 1000L) {
                    append("已思考 ${formatDurationSec(elapsedMs / 1000L)} · ")
                } else {
                    append("思考过程 · ")
                }
                append("${nodes.size} 个节点")
            }
            Text(
                label,
                fontSize = 12.sp,
                color = Brand.Muted,
                fontWeight = FontWeight.Medium
            )
            Box(Modifier.rotate(if (expanded) 90f else 0f)) {
                AppIcons.ChevronRight(Brand.Subtle, 13.dp)
            }
        }

        AnimatedVisibility(visible = expanded) {
            Box(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                ThinkingMilestoneRail(
                    nodes = nodes,
                    isLive = false,
                    activeDotAlpha = 1f,
                    maxVisibleLive = Int.MAX_VALUE
                )
            }
        }
    }
}

@Composable
private fun ThinkingMilestoneRail(
    nodes: List<ThinkingNode>,
    isLive: Boolean,
    activeDotAlpha: Float,
    maxVisibleLive: Int,
) {
    var showAll by remember { mutableStateOf(false) }
    val hiddenCount = if (isLive && !showAll && nodes.size > maxVisibleLive) nodes.size - maxVisibleLive else 0
    val displayed = if (hiddenCount > 0) nodes.drop(hiddenCount) else nodes

    Column(Modifier.fillMaxWidth().padding(start = 2.dp)) {
        if (hiddenCount > 0) {
            Text(
                "展开前面 $hiddenCount 个节点",
                fontSize = 11.5.sp,
                color = Brand.Subtle,
                modifier = Modifier
                    .padding(start = 15.dp, bottom = 5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showAll = true }
                    .padding(vertical = 1.dp, horizontal = 2.dp)
            )
        }
        displayed.forEachIndexed { idx, node ->
            val isLast = idx == displayed.lastIndex
            val isActive = isLive && isLast
            var detailExpanded by remember(node.title) { mutableStateOf(false) }
            val hasDetail = node.detail.isNotEmpty()

            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    Modifier.width(12.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .size(if (isActive) 5.5.dp else 4.5.dp)
                            .alpha(if (isActive) activeDotAlpha else 0.7f)
                            .background(if (isActive) Brand.Navy else Brand.Subtle, CircleShape)
                    )
                    if (!isLast) {
                        Box(
                            Modifier
                                .padding(top = 3.dp)
                                .width(1.dp)
                                .weight(1f)
                                .background(Brand.Border)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(bottom = if (isLast) 2.dp else 7.dp)
                        .then(
                            if (hasDetail) Modifier.clip(RoundedCornerShape(6.dp)).clickable { detailExpanded = !detailExpanded }
                            else Modifier
                        )
                ) {
                    Text(
                        text = node.title,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = if (isActive) Brand.InkSoft else Brand.Muted,
                        fontWeight = if (isActive || node.isThinking) FontWeight.Medium else FontWeight.Normal,
                        maxLines = if (detailExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AnimatedVisibility(visible = detailExpanded && hasDetail) {
                        Text(
                            text = node.detail,
                            fontSize = 12.sp,
                            lineHeight = 17.5.sp,
                            color = Brand.Subtle,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
