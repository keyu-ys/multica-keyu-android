package com.mtk.keyu

import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class AuthExpired : Exception("登录已失效，请重新登录")

data class UserProfile(val name: String, val email: String, val avatarUrl: String)
data class Agent(
    val id: String,
    val name: String,
    val desc: String = "",
    val status: String = "",
    val avatarUrl: String = ""
)
data class Session(val id: String, val agentId: String, val title: String, val updatedAt: String, val unread: Boolean, val pinned: Boolean)
data class QueuedTask(
    val taskId: String,
    val status: String,
    val content: String,
    val messageId: String = "",
    val createdAt: String = "",
)
data class PendingTask(
    val taskId: String,
    val status: String,
    val chatSessionId: String = "",
    val createdAt: String = "",
    val waitReason: String = "",
    val canSupplement: Boolean = false,
    val queuedTasks: List<QueuedTask> = emptyList(),
)
data class TaskMsg(
    val seq: Int,
    val type: String,
    val tool: String,
    val content: String,
    val summary: String = "",
    val target: String = "",
    val command: String = "",
    val createdAt: String = "",
)
data class CancelResult(val restoredText: String?)
data class Att(val id: String, val name: String, val type: String, val size: Long = 0L)
data class Msg(val id: String, val role: String, val content: String, val createdAt: String,
               val failed: Boolean, val elapsedMs: Long?, val atts: List<Att>, val taskId: String? = null)

/** 直连 Multica 服务端的极简客户端。只在 401 时视为登录失效，网络抖动不会清掉登录态。 */
class Api(private val prefs: SharedPreferences) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()
    val server get() = prefs.getString("server", BuildConfig.DEFAULT_SERVER)!!.trim().trimEnd('/')
    val slug get() = prefs.getString("slug", BuildConfig.DEFAULT_SLUG)!!.trim()
    val token get() = prefs.getString("token", "")!!
    val loggedIn get() = token.isNotEmpty()

    fun call(path: String, method: String = "GET", body: JSONObject? = null, auth: Boolean = true): String {
        val b = Request.Builder().url(server + path)
        if (auth) b.header("Authorization", "Bearer $token").header("X-Workspace-Slug", slug)
        when {
            body != null -> b.method(method, body.toString().toRequestBody(json))
            method != "GET" -> b.method(method, "{}".toRequestBody(json))
        }
        return exec(b.build())
    }

    private fun exec(req: Request): String = http.newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (r.code == 401 && req.header("Authorization") != null) throw AuthExpired()
        if (!r.isSuccessful) {
            val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            error("HTTP ${r.code} ${msg.ifEmpty { text.take(160) }}")
        }
        text
    }

    fun sendCode(email: String) { call("/auth/send-code", "POST", JSONObject().put("email", email), auth = false) }
    fun verify(email: String, code: String) {
        val t = JSONObject(call("/auth/verify-code", "POST", JSONObject().put("email", email).put("code", code), auth = false)).optString("token")
        require(t.isNotEmpty()) { "服务器没有返回登录凭证" }
        prefs.edit().putString("token", t).putString("email", email).apply()
    }

    fun refresh() = runCatching {
        JSONObject(call("/api/auth/refresh", "POST")).optString("token").takeIf { it.isNotEmpty() }
            ?.let { prefs.edit().putString("token", it).apply() }
    }

    fun meProfile(): UserProfile {
        val o = JSONObject(call("/api/me"))
        val name = o.optString("name").ifEmpty { o.optString("email").substringBefore("@") }
        val email = o.optString("email").ifEmpty { prefs.getString("email", "")!! }
        val avatar = if (o.isNull("avatar_url")) "" else o.optString("avatar_url")
        prefs.edit()
            .putString("user_id", o.optString("id"))
            .putString("user_name", name)
            .putString("email", email)
            .putString("user_avatar", avatar)
            .apply()
        return UserProfile(name, email, avatar).also { AppState.setMeProfile(it) }
    }

    fun me(): String = meProfile().name

    fun agents(): List<Agent> {
        val raw = call("/api/agents")
        val arr = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("agents") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.isNull("archived_at") || it.optString("archived_at").isEmpty() }
            .map {
                val desc = it.optString("description")
                    .ifEmpty { it.optString("role") }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                val avatar = if (it.isNull("avatar_url")) "" else it.optString("avatar_url")
                Agent(it.getString("id"), it.optString("name"), desc, it.optString("status"), avatar)
            }
            .also { AppState.updateAgents(it) }
    }

    fun createSession(agentId: String, title: String): String =
        JSONObject(call("/api/chat/sessions", "POST", JSONObject().put("agent_id", agentId).put("title", title))).getString("id")

    fun sessions(): List<Session> {
        val raw = call("/api/chat/sessions?status=active")
        val arr = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("sessions") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val last = it.optJSONObject("last_message")
            Session(
                it.getString("id"),
                it.optString("agent_id"),
                it.optString("title"),
                last?.optString("created_at")?.takeIf { s -> s.isNotEmpty() } ?: it.optString("updated_at"),
                it.optBoolean("has_unread"),
                it.optBoolean("pinned")
            )
        }.sortedWith(compareByDescending<Session> { it.pinned }.thenByDescending { it.updatedAt })
            .also { AppState.updateSessions(it) }
    }

    fun getSessionAgentId(sessionId: String): String = JSONObject(call("/api/chat/sessions/$sessionId")).optString("agent_id")

    fun markRead(sessionId: String) { runCatching { call("/api/chat/sessions/$sessionId/read", "POST") } }

    fun messages(sessionId: String): List<Msg> {
        val raw = call("/api/chat/sessions/$sessionId/messages/page?limit=50")
        val arr = JSONObject(raw).optJSONArray("messages") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { m ->
            val a = m.optJSONArray("attachments") ?: JSONArray()
            Msg(
                m.getString("id"),
                m.optString("role"),
                m.optString("content"),
                m.optString("created_at"),
                !m.isNull("failure_reason") && m.optString("failure_reason").isNotEmpty(),
                if (m.isNull("elapsed_ms")) null else m.optLong("elapsed_ms"),
                (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Att(it.getString("id"), it.optString("filename"), it.optString("content_type"), it.optLong("size_bytes", 0L))
                },
                if (m.isNull("task_id")) null else m.optString("task_id").takeIf { it.isNotEmpty() }
            )
        }.sortedBy { it.createdAt }
    }

    /** 发送聊天消息：默认按排队模式（steer = false，类似 Codex）；仅显式传 steer = true 时直接穿插引导。 */
    fun send(sessionId: String, text: String, attachmentIds: List<String>, steer: Boolean = false) {
        val body = JSONObject().put("content", text).put("steer", steer)
        if (attachmentIds.isNotEmpty()) body.put("attachment_ids", JSONArray(attachmentIds))
        call("/api/chat/sessions/$sessionId/messages", "POST", body)
        AppState.lastSentAtMs = System.currentTimeMillis()
    }

    fun upload(sessionId: String, name: String, mime: String, bytes: ByteArray): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType()))
            .addFormDataPart("chat_session_id", sessionId).build()
        val req = Request.Builder().url("$server/api/upload-file").post(form)
            .header("Authorization", "Bearer $token").header("X-Workspace-Slug", slug).build()
        return JSONObject(exec(req)).getString("id")
    }

    fun avatarBytes(url: String): ByteArray {
        val fullUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> server + url
            else -> "$server/$url"
        }
        val b = Request.Builder().url(fullUrl)
        if (fullUrl.startsWith(server)) {
            b.header("Authorization", "Bearer $token").header("X-Workspace-Slug", slug)
        }
        return http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body?.bytes()?.takeIf { it.isNotEmpty() } ?: error("空头像")
        }
    }

    fun attachmentBytes(id: String, preferFull: Boolean = false): ByteArray {
        val paths = if (preferFull) listOf("/api/attachments/$id/download", "/api/attachments/$id/thumbnail")
        else listOf("/api/attachments/$id/thumbnail", "/api/attachments/$id/download")
        for (path in paths) {
            val req = Request.Builder().url(server + path)
                .header("Authorization", "Bearer $token").header("X-Workspace-Slug", slug).build()
            val bytes = http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        error("附件取不到")
    }

    fun download(id: String, dest: File, onProgress: (Float) -> Unit) {
        downloadUrl("$server/api/attachments/$id/download", dest, onProgress)
    }

    /** 通用流式下载：支持固定发布目录绝对 URL 或站内相对路径；仅对同源 Multica 服务器附带登录头。 */
    fun downloadUrl(rawUrl: String, dest: File, onProgress: (Float) -> Unit) {
        val fullUrl = resolveUrl(rawUrl)
        val b = Request.Builder().url(fullUrl)
        if (fullUrl.startsWith(server) && token.isNotEmpty()) {
            b.header("Authorization", "Bearer $token").header("X-Workspace-Slug", slug)
        }
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error("空响应")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, dest.name + ".part")
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        read += n
                        onProgress(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        }
    }

    /** 富内容里的媒体字节：站内附件走带登录态的缩略图/原图，外链直接拉取（同源才附带登录头）。 */
    fun mediaBytes(rawUrl: String, preferFull: Boolean = false): ByteArray {
        attachmentIdOf(rawUrl)?.let { return attachmentBytes(it, preferFull) }
        val full = resolveUrl(rawUrl)
        val b = Request.Builder().url(full)
        if (full.startsWith(server) && token.isNotEmpty()) b.header("Authorization", "Bearer $token").header("X-Workspace-Slug", slug)
        return http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.body?.bytes()?.takeIf { it.isNotEmpty() } ?: error("空内容")
        }
    }

    /** 视频播放地址：站内附件先跟随服务端 302 拿到签名直链（播放器无需登录头），拿不到则带登录头直连。 */
    fun resolveMedia(rawUrl: String): Pair<String, Map<String, String>> {
        val full = resolveUrl(rawUrl)
        if (!full.startsWith(server)) return full to emptyMap()
        val headers = mapOf("Authorization" to "Bearer $token", "X-Workspace-Slug" to slug)
        runCatching {
            val noFollow = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val req = Request.Builder().url(full).header("Range", "bytes=0-0").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            noFollow.newCall(req).execute().use { r ->
                if (r.isRedirect) r.header("Location")?.let { loc -> return resolveUrl(loc) to emptyMap() }
            }
        }
        return full to headers
    }

    fun attachmentIdOf(rawUrl: String): String? =
        Regex("/api/attachments/([0-9a-fA-F-]{36})/(?:download|thumbnail)").find(rawUrl)?.groupValues?.get(1)

    fun resolveUrl(rawUrl: String): String {
        val u = rawUrl.trim()
        return when {
            u.startsWith("http://", true) || u.startsWith("https://", true) -> u
            u.startsWith("/") -> server + u
            else -> "$server/$u"
        }
    }

    /** 批量拉取工作区内所有正在运行/排队的聊天任务，驱动侧边栏与悬浮面板的「正在输入…」状态。 */
    fun pendingTasks(): List<PendingTask> = runCatching {
        val raw = call("/api/chat/pending-tasks")
        val arr = JSONObject(raw).optJSONArray("tasks") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val tid = o.optString("task_id")
            val sid = o.optString("chat_session_id")
            if (tid.isEmpty() || sid.isEmpty()) null else PendingTask(tid, o.optString("status"), sid)
        }.also { AppState.updateRunningTasks(it) }
    }.getOrDefault(emptyList())

    /** 拉取单个会话当前正在运行/排队的任务（含 queued_tasks 排队队列、created_at，供思考进度与排队栏使用）。 */
    fun pendingTask(sessionId: String): PendingTask? = runCatching {
        val o = JSONObject(call("/api/chat/sessions/$sessionId/pending-task"))
        val tid = o.optString("task_id").ifEmpty { o.optJSONObject("task")?.optString("id").orEmpty() }
        if (tid.isEmpty()) null else {
            val qArr = o.optJSONArray("queued_tasks") ?: JSONArray()
            val queued = (0 until qArr.length()).map { qArr.getJSONObject(it) }.mapNotNull { q ->
                val qid = q.optString("task_id")
                if (qid.isEmpty()) null else QueuedTask(
                    taskId = qid,
                    status = q.optString("status"),
                    content = q.optString("content"),
                    messageId = q.optString("message_id"),
                    createdAt = q.optString("created_at")
                )
            }
            PendingTask(
                taskId = tid,
                status = o.optString("status").ifEmpty { o.optJSONObject("task")?.optString("status").orEmpty() },
                chatSessionId = sessionId,
                createdAt = o.optString("created_at").ifEmpty { o.optJSONObject("task")?.optString("created_at").orEmpty() },
                waitReason = o.optString("wait_reason"),
                canSupplement = o.optBoolean("can_supplement", false),
                queuedTasks = queued
            )
        }
    }.getOrNull()

    /** 增量拉取任务思考与执行过程事件（提取真实思考内容与精炼目标摘要，不留底层大块日志）。 */
    fun taskMessages(taskId: String, sinceSeq: Int = 0): List<TaskMsg> {
        val q = if (sinceSeq > 0) "?since=$sinceSeq" else ""
        val raw = call("/api/tasks/$taskId/messages$q")
        val arr = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("messages") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { m ->
            val inp = m.optJSONObject("input")
            val summary = firstNonBlank(inp, "toolSummary", "description", "Role", "role", "subagent_type").take(64)
            val rawPath = firstNonBlank(inp, "file_path", "path", "Path", "TargetFile", "AbsolutePath", "DirectoryPath", "SearchPath")
            val patchText = firstNonBlank(inp, "patch", "input")
            val queryText = firstNonBlank(inp, "query", "Query", "pattern", "Pattern", "url", "Url")
            val target = when {
                rawPath.isNotEmpty() -> shortBaseName(rawPath)
                patchText.contains("*** Update File:") || patchText.contains("*** Add File:") -> extractPatchBaseNames(patchText)
                queryText.isNotEmpty() -> queryText.take(40)
                else -> ""
            }
            val cmd = firstNonBlank(inp, "command", "cmd", "CommandLine").take(160)
            TaskMsg(
                seq = m.optInt("seq", 0),
                type = m.optString("type"),
                tool = m.optString("tool"),
                content = m.optString("content"),
                summary = summary,
                target = target,
                command = cmd,
                createdAt = m.optString("created_at")
            )
        }
    }

    private fun firstNonBlank(obj: JSONObject?, vararg keys: String): String {
        if (obj == null) return ""
        for (k in keys) {
            if (!obj.isNull(k)) {
                val v = obj.opt(k)
                if (v is String && v.isNotBlank()) return v.trim()
            }
        }
        return ""
    }

    private fun shortBaseName(path: String): String {
        val clean = path.trim().trimEnd('/', '\\')
        if (clean.isEmpty()) return ""
        return clean.substringAfterLast('/').substringAfterLast('\\')
    }

    private fun extractPatchBaseNames(patch: String): String {
        val names = linkedSetOf<String>()
        for (line in patch.lineSequence()) {
            val trimmed = line.trim()
            val prefix = when {
                trimmed.startsWith("*** Update File:") -> "*** Update File:"
                trimmed.startsWith("*** Add File:") -> "*** Add File:"
                trimmed.startsWith("*** Delete File:") -> "*** Delete File:"
                else -> null
            } ?: continue
            val name = shortBaseName(trimmed.removePrefix(prefix))
            if (name.isNotEmpty()) names.add(name)
        }
        return names.take(3).joinToString("、")
    }

    /** 立即停止/取消指定任务，并支持在后端允许时还原刚取消的用户草稿。 */
    fun cancelTask(taskId: String): CancelResult {
        val raw = call("/api/tasks/$taskId/cancel", "POST")
        val o = runCatching { JSONObject(raw) }.getOrNull()
        val cm = o?.optJSONObject("cancelled_chat_message")
        val restored = if (cm != null && cm.optBoolean("restore_to_input", false)) {
            cm.optString("content").takeIf { it.isNotBlank() }
        } else null
        return CancelResult(restored)
    }

    /** 将排队中的某条消息置顶并直接引导执行（优先排到队头 + 停止当前运行中的任务，与网页端一致）。 */
    fun steerQueuedTask(sessionId: String, taskId: String) {
        val raw = call("/api/chat/sessions/$sessionId/queued-tasks/$taskId/prioritize", "POST")
        val activeId = runCatching { JSONObject(raw).optString("active_task_id") }.getOrDefault("")
        if (activeId.isNotEmpty()) {
            cancelTask(activeId)
        }
    }

    /**
     * 按三层优先级解析安卓端最新版本清单（面向全员跨工作区优先）：
     * 1. Multica 全局配置 `/api/config` 中的 `android_client` 或 `android_release_url`；
     * 2. Multica 长期固定发布目录 `${server}/releases/android/latest.json`；
     * 3. 兼容兜底：工作区内置清单任务（过渡期未挂载固定目录时仍可用）。
     */
    fun fetchOtaManifest(fallbackIssueId: String): OtaManifest? {
        var releaseUrl = "$server/releases/android/latest.json"

        // 1. 检查 Multica 全局配置 /api/config
        runCatching {
            val cfg = JSONObject(call("/api/config", auth = false))
            cfg.optJSONObject("android_client")?.let { obj ->
                parseOtaJson(obj)?.let { return it }
            }
            val customUrl = cfg.optString("android_release_url").trim()
                .ifEmpty { cfg.optString("mobile_release_url").trim() }
            if (customUrl.isNotEmpty()) releaseUrl = resolveUrl(customUrl)
        }

        // 2. 读取 Multica 长期固定发布目录 latest.json
        runCatching {
            val b = Request.Builder().url(releaseUrl)
            if (releaseUrl.startsWith(server) && token.isNotEmpty()) {
                b.header("Authorization", "Bearer $token").header("X-Workspace-Slug", slug)
            }
            val raw = exec(b.build())
            parseOtaJson(JSONObject(raw))?.let { return it }
        }

        // 3. 兼容兜底：读取内置 Multica 清单任务
        if (fallbackIssueId.isBlank() || !loggedIn) return null
        val raw = call("/api/issues/$fallbackIssueId")
        val desc = JSONObject(raw).optString("description")
        if (desc.isBlank()) return null
        val jsonText = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```").find(desc)?.groupValues?.get(1)
            ?: Regex("\\{[\\s\\S]*\"version_code\"[\\s\\S]*\\}").find(desc)?.value
            ?: return null
        return parseOtaJson(JSONObject(jsonText))
    }

    private fun parseOtaJson(o: JSONObject): OtaManifest? {
        val vc = o.optLong("version_code", 0L)
        if (vc <= 0L) return null
        val attId = o.optString("attachment_id").trim()
        val rawUrl = o.optString("url").trim().ifEmpty { o.optString("apk_url").trim() }
        val resolvedUrl = when {
            rawUrl.isNotEmpty() -> resolveUrl(rawUrl)
            attId.isNotEmpty() -> "$server/api/attachments/$attId/download"
            else -> ""
        }
        return OtaManifest(
            pkg = o.optString("pkg", "com.mtk.keyu").trim(),
            versionCode = vc,
            versionName = o.optString("version_name").trim().ifEmpty { vc.toString() },
            downloadUrl = resolvedUrl,
            attachmentId = attId,
            sha256 = o.optString("sha256").trim(),
            sizeBytes = o.optLong("size_bytes", 0L),
            notes = o.optString("notes").trim(),
        )
    }

    /** 从排队队列中移除某条尚未开始的消息。 */
    fun removeQueuedTask(sessionId: String, taskId: String) {
        call("/api/tasks/$taskId/cancel?expected_status=queued&chat_session_id=$sessionId&queue_action=remove", "POST")
    }

    // ---------------- 工作（收件箱 + 任务对话） ----------------

    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

    fun inbox(): List<InboxItem> {
        val raw = call("/api/inbox")
        val arr = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            InboxItem(
                id = o.getString("id"),
                type = o.str("type"),
                issueId = o.str("issue_id"),
                issueIdentifier = o.str("issue_identifier"),
                title = o.str("title"),
                body = o.str("body"),
                read = o.optBoolean("read"),
                archived = o.optBoolean("archived"),
                createdAt = o.str("created_at"),
                issueStatus = o.str("issue_status"),
                actorType = o.str("actor_type"),
                actorId = o.str("actor_id"),
                details = o.optJSONObject("details")?.toString().orEmpty()
            )
        }.filter { !it.archived }.sortedByDescending { it.createdAt }
    }

    fun markInboxRead(id: String) { call("/api/inbox/$id/read", "POST") }
    fun markIssueInboxRead(issueId: String) { runCatching { call("/api/inbox/issues/$issueId/read", "POST") } }
    fun markAllInboxRead() { call("/api/inbox/mark-all-read", "POST") }
    fun archiveInbox(id: String) { call("/api/inbox/$id/archive", "POST") }

    fun issue(id: String): IssueBrief {
        val o = JSONObject(call("/api/issues/$id"))
        return IssueBrief(
            id = o.getString("id"),
            identifier = o.str("identifier"),
            title = o.str("title"),
            description = o.str("description"),
            status = o.str("status"),
            statusName = o.str("status_name"),
            assigneeType = o.str("assignee_type"),
            assigneeId = o.str("assignee_id")
        )
    }

    fun timeline(issueId: String): List<TimelineEntry> {
        val raw = call("/api/issues/$issueId/timeline")
        val arr = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("entries") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val a = o.optJSONArray("attachments") ?: JSONArray()
            TimelineEntry(
                id = o.getString("id"),
                kind = o.str("type"),
                actorType = o.str("actor_type"),
                actorId = o.str("actor_id"),
                actorName = o.str("actor_name"),
                actorAvatar = o.str("actor_avatar_url"),
                createdAt = o.str("created_at"),
                action = o.str("action"),
                details = o.optJSONObject("details")?.toString().orEmpty(),
                content = o.str("content"),
                parentId = o.str("parent_id"),
                deleted = o.str("deleted_at").isNotEmpty(),
                atts = (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Att(it.getString("id"), it.optString("filename"), it.optString("content_type"), it.optLong("size_bytes", 0L))
                }
            )
        }.sortedBy { it.createdAt }
    }

    /** 当前任务上正在跑/排队的智能体任务（取第一条）。 */
    fun activeIssueTask(issueId: String): PendingTask? = runCatching {
        val o = JSONObject(call("/api/issues/$issueId/active-task"))
        val arr = o.optJSONArray("tasks") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { it.optString("status") in setOf("running", "dispatched", "queued", "waiting_local_directory") }
            ?.let {
                PendingTask(
                    taskId = it.getString("id"),
                    status = it.optString("status"),
                    createdAt = it.str("started_at").ifEmpty { it.str("created_at") },
                    canSupplement = it.optString("status") == "running",
                    chatSessionId = it.str("agent_id") // 复用字段：任务页里存 agent_id
                )
            }
    }.getOrNull()

    fun comment(issueId: String, text: String, attachmentIds: List<String>, steer: Boolean, parentId: String? = null) {
        val body = JSONObject().put("content", text)
        if (steer) body.put("steer", true)
        if (!parentId.isNullOrEmpty()) body.put("parent_id", parentId)
        if (attachmentIds.isNotEmpty()) body.put("attachment_ids", JSONArray(attachmentIds))
        call("/api/issues/$issueId/comments", "POST", body)
    }

    fun uploadForIssue(issueId: String, name: String, mime: String, bytes: ByteArray): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType()))
            .addFormDataPart("issue_id", issueId).build()
        val req = Request.Builder().url("$server/api/upload-file").post(form)
            .header("Authorization", "Bearer $token").header("X-Workspace-Slug", slug).build()
        return JSONObject(exec(req)).getString("id")
    }

    /** 工作区成员（用于收件箱里显示同事姓名/头像），带进程内缓存。 */
    fun members(): Map<String, Member> {
        if (AppState.members.isNotEmpty()) return AppState.members
        val wsRaw = call("/api/workspaces")
        val wsArr = if (wsRaw.trimStart().startsWith("[")) JSONArray(wsRaw) else JSONObject(wsRaw).optJSONArray("workspaces") ?: JSONArray()
        val ws = (0 until wsArr.length()).map { wsArr.getJSONObject(it) }.firstOrNull { it.optString("slug") == slug } ?: return emptyMap()
        val raw = call("/api/workspaces/${ws.getString("id")}/members")
        val arr = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("members") ?: JSONArray()
        val map = (0 until arr.length()).map { arr.getJSONObject(it) }.associate {
            it.optString("user_id") to Member(it.optString("user_id"), it.optString("name").ifEmpty { it.optString("email").substringBefore("@") }, it.str("avatar_url"))
        }
        AppState.members.putAll(map)
        return map
    }
}

data class InboxItem(
    val id: String, val type: String, val issueId: String, val issueIdentifier: String,
    val title: String, val body: String, val read: Boolean, val archived: Boolean,
    val createdAt: String, val issueStatus: String, val actorType: String, val actorId: String,
    val details: String,
)
data class IssueBrief(
    val id: String, val identifier: String, val title: String, val description: String,
    val status: String, val statusName: String, val assigneeType: String, val assigneeId: String,
)
data class TimelineEntry(
    val id: String, val kind: String, val actorType: String, val actorId: String,
    val actorName: String, val actorAvatar: String, val createdAt: String,
    val action: String, val details: String, val content: String, val parentId: String,
    val deleted: Boolean, val atts: List<Att>,
)
data class Member(val userId: String, val name: String, val avatarUrl: String)
