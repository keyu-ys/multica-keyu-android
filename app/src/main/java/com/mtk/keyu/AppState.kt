package com.mtk.keyu

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.CopyOnWriteArrayList

/** 进程内响应式运行态：统一同步服务状态、会话缓存、运行中任务、用户资料与 APK 安装进度。 */
object AppState {
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    val notifRunning = mutableStateOf(false)
    val bubbleRunning = mutableStateOf(false)
    val unreadCount = mutableIntStateOf(0)
    val meProfile = mutableStateOf<UserProfile?>(null)

    /** 活跃会话列表缓存（主界面、侧边栏、悬浮面板与发送确认 Sheet 共享）。 */
    val sessions = mutableStateListOf<Session>()
    @Volatile private var sessionsSnapshot: List<Session> = emptyList()

    /** 正在运行/排队中的聊天会话映射：chatSessionId -> taskId。 */
    val runningTasks = mutableStateMapOf<String, String>()

    @Volatile var foregroundSessionId: String? = null
    @Volatile var appInForeground: Boolean = false
    @Volatile var lastSentAtMs: Long = 0L
    val workingStatus = mutableStateOf<String?>(null)

    // ---- 工作（收件箱）----
    /** 「与我相关」未读的任务数，用于顶部「工作」角标。 */
    val workUnread = mutableIntStateOf(0)
    /** 点击系统通知后要打开的任务：issueId + 时间戳。 */
    val openIssueReq = mutableStateOf<Pair<String, Long>?>(null)
    /** 前台正在查看的任务，用于免打扰。 */
    @Volatile var foregroundIssueId: String? = null
    val members = java.util.concurrent.ConcurrentHashMap<String, Member>()

    data class CompletionNotice(
        val sessionId: String,
        val agentId: String = "",
        val agentName: String,
        val avatarUrl: String = "",
        val summary: String,
        val hasApk: Boolean = false,
        val installedPkg: String? = null,
    )
    val completionNotice = mutableStateOf<CompletionNotice?>(null)

    @Volatile var overlayVisible: Boolean = false
        set(value) { field = value; notifyBubble() }
    @Volatile var capturing: Boolean = false
        set(value) { field = value; notifyBubble() }

    var annotateVisible: Boolean
        get() = overlayVisible
        set(value) { overlayVisible = value }

    private val bubbleListeners = CopyOnWriteArrayList<() -> Unit>()
    fun addBubbleListener(l: () -> Unit) { bubbleListeners.add(l) }
    fun removeBubbleListener(l: () -> Unit) { bubbleListeners.remove(l) }
    fun notifyBubble() { bubbleListeners.forEach { runCatching { it() } } }

    enum class InstallPhase { IDLE, DOWNLOADING, VERIFYING, AWAITING_CONFIRM, INSTALLING, INSTALLED, ERROR }
    data class InstallStatus(
        val phase: InstallPhase = InstallPhase.IDLE,
        val progress: Float = 0f,
        val message: String = "",
        val pkg: String = "",
        val label: String = "",
        val version: String = "",
        val sizeBytes: Long = 0L,
    )
    val installs = mutableStateMapOf<String, InstallStatus>()
    val thinkingNodes = mutableStateMapOf<String, List<ThinkingNode>>()

    enum class OtaPhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, INSTALLING, UP_TO_DATE, ERROR }
    data class OtaStatus(
        val phase: OtaPhase = OtaPhase.IDLE,
        val manifest: OtaManifest? = null,
        val progress: Float = 0f,
        val message: String = "",
        val dismissed: Boolean = false,
    )
    val otaState = mutableStateOf(OtaStatus())

    fun updateOta(block: (OtaStatus) -> OtaStatus) {
        onMain { otaState.value = block(otaState.value) }
    }

    fun markSessionReadLocally(sessionId: String) {
        if (sessionId.isEmpty()) return
        sessionsSnapshot = sessionsSnapshot.map { if (it.id == sessionId) it.copy(unread = false) else it }
        onMain {
            val idx = sessions.indexOfFirst { it.id == sessionId }
            if (idx >= 0 && sessions[idx].unread) {
                sessions[idx] = sessions[idx].copy(unread = false)
            }
            unreadCount.intValue = sessions.count { it.unread }
            notifyBubble()
        }
    }

    fun setMeProfile(profile: UserProfile) {
        onMain { meProfile.value = profile }
    }

    fun updateInstall(attId: String, block: (InstallStatus) -> InstallStatus) {
        onMain {
            val cur = installs[attId] ?: InstallStatus()
            installs[attId] = block(cur)
        }
    }

    private val agentMap = linkedMapOf<String, Agent>()
    fun updateAgents(list: List<Agent>) {
        synchronized(agentMap) {
            agentMap.clear()
            list.forEach { agentMap[it.id] = it }
        }
        notifyBubble()
    }
    fun agent(id: String): Agent? = synchronized(agentMap) { agentMap[id] }
    fun agentName(id: String): String? = synchronized(agentMap) { agentMap[id]?.name }
    fun allAgents(): List<Agent> = synchronized(agentMap) { agentMap.values.toList() }

    fun updateSessions(list: List<Session>) {
        sessionsSnapshot = list
        onMain {
            sessions.clear()
            sessions.addAll(list)
        }
    }

    fun updateRunningTasks(list: List<PendingTask>) {
        onMain {
            runningTasks.clear()
            list.forEach { runningTasks[it.chatSessionId] = it.taskId }
            notifyBubble()
        }
    }

    fun setSessionTask(sessionId: String, taskId: String?) {
        if (sessionId.isEmpty()) return
        onMain {
            if (taskId.isNullOrEmpty()) runningTasks.remove(sessionId) else runningTasks[sessionId] = taskId
            notifyBubble()
        }
    }

    /** 记住某个智能体最近打开/活跃的会话，切换智能体时默认平滑续聊，而不是强迫开新会话。 */
    fun rememberAgentSession(prefs: SharedPreferences, agentId: String, sessionId: String) {
        val e = prefs.edit().putString("agent", agentId).putString("current_session", sessionId)
        if (agentId.isNotEmpty() && sessionId.isNotEmpty()) {
            e.putString("last_session_$agentId", sessionId)
        }
        e.apply()
    }

    /** 为指定智能体找到最合适的续聊会话：优先上次打开的，其次该智能体最新活跃的会话；若从未聊过则返回空字符串。 */
    fun preferredSessionForAgent(prefs: SharedPreferences, agentId: String): String {
        if (agentId.isEmpty()) return ""
        val source = sessionsSnapshot.ifEmpty { sessions.toList() }
        val agentSessions = source.filter { it.agentId == agentId }
        val remembered = prefs.getString("last_session_$agentId", "").orEmpty()
        if (remembered.isNotEmpty() && (agentSessions.isEmpty() || agentSessions.any { it.id == remembered })) {
            return remembered
        }
        return agentSessions.firstOrNull()?.id.orEmpty()
    }
}
