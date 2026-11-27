package com.mtk.keyu

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.*
import java.io.File

/**
 * 常驻前台服务：智能自适应轮询会话。
 * - 发现新的未读智能体回复时，推送干净的系统通知（前台正浏览该会话时免打扰）；
 * - 若悬浮球开启且开启了气泡提醒，悬浮球变身翠绿 ✓ 并弹出视频同款白色完成提醒气泡；
 * - 若开启「同签名应用自动更新」，安全校验通过后自动下载并拉起覆盖更新。
 */
class ReplyService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: SharedPreferences
    private lateinit var api: Api
    private val seen = mutableSetOf<String>()
    private val autoInstalled = mutableSetOf<String>()
    private val seenInbox = mutableSetOf<String>()
    private var inboxPrimed = false

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("MFLOAT", "ReplyService onCreate")
        Notify.ensure(this)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        api = Api(prefs)
        AppState.notifRunning.value = true
        startForeground(1, Notify.ongoing(this, "后台接收智能体回复"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        loop()
    }

    private fun loop() = scope.launch {
        runCatching {
            api.refresh()
            api.agents()
            val list = api.sessions()
            list.forEach { if (it.unread) seen.add("${it.id}@${it.updatedAt}") }
            updateUnread(list.count { it.unread })
        }
        var round = 0
        while (isActive) {
            val fast = (System.currentTimeMillis() - AppState.lastSentAtMs) < 180_000L || AppState.workingStatus.value != null
            delay(if (fast) 4_000L else 15_000L)
            round++
            if (!api.loggedIn) continue
            runCatching {
                if (round % 40 == 0) api.agents()
                if (round == 1 || round % 15 == 0) {
                    launch { AutoUpdater.check(this@ReplyService, api, prefs, manual = false) }
                }
                if (round % 2 == 1) runCatching { pollInbox() }
                val sessions = api.sessions()
                api.pendingTasks()
                updateUnread(sessions.count { it.unread })
                for (s in sessions) {
                    val key = "${s.id}@${s.updatedAt}"
                    if (!s.unread || key in seen) continue
                    seen.add(key)

                    // 前台正查看该会话时免打扰
                    if (AppState.appInForeground && AppState.foregroundSessionId == s.id) {
                        AppState.workingStatus.value = null
                        continue
                    }

                    val lastMsg = runCatching { api.messages(s.id).lastOrNull { it.role == "assistant" } }.getOrNull()
                    val apkAtt = lastMsg?.atts?.firstOrNull { it.name.endsWith(".apk", true) }
                    val imgCount = lastMsg?.atts?.count { it.isImage() } ?: 0
                    val agentName = AppState.agentName(s.agentId)
                        ?: runCatching { api.agents(); AppState.agentName(s.agentId) }.getOrNull()
                        ?: "智能体"
                    val summary = Notify.cleanSummary(lastMsg?.content.orEmpty(), apkAtt != null, imgCount)

                    AppState.workingStatus.value = null
                    Notify.reply(this@ReplyService, s.id, agentName, s.title, summary)

                    // 悬浮球开启且开启了气泡提醒 → 球变翠绿 ✓ 并弹出完成提醒气泡
                    if (AppState.bubbleRunning.value && prefs.getBoolean("bubble_alert", true)) {
                        val agentObj = AppState.agent(s.agentId)
                        val notice = AppState.CompletionNotice(
                            sessionId = s.id,
                            agentId = s.agentId,
                            agentName = agentName,
                            avatarUrl = agentObj?.avatarUrl.orEmpty(),
                            summary = summary,
                            hasApk = apkAtt != null
                        )
                        AppState.completionNotice.value = notice
                        AppState.notifyBubble()
                        FloatPanelOverlay.showCompletion(applicationContext, notice)
                    }

                    if (apkAtt != null && prefs.getBoolean("auto_install", false) && apkAtt.id !in autoInstalled) {
                        autoInstalled.add(apkAtt.id)
                        scope.launch { tryAutoInstall(apkAtt, s.id, agentName) }
                    }
                }
            }.onFailure { e ->
                android.util.Log.w("MFLOAT", "ReplyService poll error: ${e.message}")
            }
        }
    }

    /** 收件箱：只对「与我相关」的新未读发通知；首轮只记录不提醒，避免开机刷屏。 */
    private fun pollInbox() {
        runCatching { api.members() }
        val items = api.inbox()
        WorkCache.items = items
        AppState.workUnread.intValue = workUnreadOf(items)
        val fresh = items.filter { !it.read && WorkText.isRelevant(it) && it.id !in seenInbox }
        fresh.forEach { seenInbox.add(it.id) }
        if (!inboxPrimed) { inboxPrimed = true; return }
        if (!prefs.getBoolean("work_notify", true)) return
        fresh.groupBy { it.issueId.ifEmpty { it.id } }.values.forEach { group ->
            val latest = group.maxBy { it.createdAt }
            if (AppState.appInForeground && AppState.foregroundIssueId == latest.issueId && latest.issueId.isNotEmpty()) return@forEach
            Notify.work(this, latest)
        }
    }

    private fun updateUnread(count: Int) {
        if (AppState.unreadCount.intValue != count) {
            AppState.unreadCount.intValue = count
            AppState.notifyBubble()
        }
    }

    private suspend fun tryAutoInstall(att: Att, sessionId: String, agentName: String) {
        if (!Installer.canInstall(this)) return
        runCatching {
            val file = File(cacheDir, "apks/${att.id}.apk")
            AppState.updateInstall(att.id) {
                it.copy(phase = AppState.InstallPhase.DOWNLOADING, progress = 0f, message = "自动下载安装包…")
            }
            if (!file.exists() || file.length() < 1024) {
                api.download(att.id, file) { p ->
                    AppState.updateInstall(att.id) { it.copy(progress = p) }
                }
            }
            AppState.updateInstall(att.id) {
                it.copy(phase = AppState.InstallPhase.VERIFYING, message = "校验包名与签名…")
            }
            val info = Installer.inspect(this, file) ?: error("不是有效的安装包")
            if (!Installer.isSafeForAutoInstall(this, file, info)) {
                AppState.updateInstall(att.id) {
                    it.copy(
                        phase = AppState.InstallPhase.IDLE,
                        pkg = info.pkg,
                        label = info.label,
                        version = info.version,
                        sizeBytes = info.sizeBytes,
                        message = "非本机已装同签名包，请手动确认安装"
                    )
                }
                return
            }
            withContext(Dispatchers.Main) {
                Installer.install(this@ReplyService, file, att.id, info)
            }
        }.onFailure { e ->
            AppState.updateInstall(att.id) {
                it.copy(phase = AppState.InstallPhase.ERROR, message = e.message ?: "自动安装失败")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        AppState.notifRunning.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, ReplyService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ReplyService::class.java)) }
    }
}
