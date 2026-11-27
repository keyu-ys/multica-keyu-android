package com.mtk.keyu

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class OtaManifest(
    val pkg: String,
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String = "",
    val attachmentId: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val notes: String = "",
) {
    val hasDownload: Boolean get() = downloadUrl.isNotEmpty() || attachmentId.isNotEmpty()
}

/**
 * 弱设计全局更新引擎（仿 Codex 桌面端体验）：
 * - 优先从 Multica 全局配置（/api/config）与长期固定目录（${server}/releases/android/latest.json）读取最新版本；
 * - 未挂载固定目录时自动平滑回退到内置清单任务；
 * - 后台检查永远静默不打扰：发现新版仅点亮侧边栏右下角下载图标；用户轻点下载图标后再下载、校验 SHA-256 + 包名 + 签名并拉起覆盖安装。
 */
object AutoUpdater {
    val DEFAULT_OTA_ISSUE_ID: String = BuildConfig.DEFAULT_OTA_ISSUE_ID
    private val mutex = Mutex()
    @Volatile private var lastQuietCheckMs = 0L

    fun fallbackIssueId(prefs: SharedPreferences): String =
        prefs.getString("ota_issue_id", DEFAULT_OTA_ISSUE_ID).orEmpty().trim().ifEmpty { DEFAULT_OTA_ISSUE_ID }

    fun currentVersionCode(ctx: Context): Long = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(1L)

    fun currentVersionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty().ifEmpty { "0.1.2" }
    }.getOrDefault("0.1.2")

    /** 静默检查更新（默认节流 45 秒；手动检查立即执行）。只更新状态点亮图标，不主动弹系统安装打断用户。 */
    suspend fun check(
        ctx: Context,
        api: Api,
        prefs: SharedPreferences,
        manual: Boolean = false,
    ): String {
        val now = System.currentTimeMillis()
        if (!manual && (now - lastQuietCheckMs) < 45_000L) {
            val cur = AppState.otaState.value
            return if (cur.manifest != null && cur.phase == AppState.OtaPhase.AVAILABLE) {
                "发现新版本 ${verLabel(cur.manifest.versionName)}"
            } else "已是最新版本 ${verLabel(currentVersionName(ctx))}"
        }
        if (!mutex.tryLock()) return "正在检查更新…"
        try {
            lastQuietCheckMs = now
            if (manual) {
                AppState.updateOta { it.copy(phase = AppState.OtaPhase.CHECKING, message = "正在检查更新…") }
            }
            val manifest = withContext(Dispatchers.IO) {
                runCatching { api.fetchOtaManifest(fallbackIssueId(prefs)) }
            }.getOrElse { e ->
                val msg = e.message ?: "检查更新失败"
                if (manual) AppState.updateOta { it.copy(phase = AppState.OtaPhase.ERROR, message = msg) }
                return msg
            }

            val curVc = currentVersionCode(ctx)
            val curVn = currentVersionName(ctx)
            if (manifest == null || manifest.pkg != ctx.packageName || !manifest.hasDownload || manifest.versionCode <= curVc) {
                AppState.updateOta {
                    AppState.OtaStatus(
                        phase = if (manual) AppState.OtaPhase.UP_TO_DATE else AppState.OtaPhase.IDLE,
                        message = if (manual) "已是最新版本 v$curVn" else ""
                    )
                }
                return "已是最新版本 v$curVn"
            }

            AppState.updateOta {
                it.copy(
                    phase = AppState.OtaPhase.AVAILABLE,
                    manifest = manifest,
                    message = manifest.notes.ifEmpty { "发现新版本 ${verLabel(manifest.versionName)}" },
                    dismissed = false
                )
            }
            return "发现新版本 ${verLabel(manifest.versionName)}"
        } finally {
            mutex.unlock()
        }
    }

    /** 用户点击亮起的下载图标后执行：流式下载 + SHA-256 校验 + 包名/同签名校验 + 拉起覆盖安装。 */
    suspend fun downloadAndInstall(ctx: Context, api: Api, manifest: OtaManifest) {
        if (!mutex.tryLock()) return
        try {
            if (!Installer.canInstall(ctx)) {
                AppState.updateOta {
                    it.copy(
                        phase = AppState.OtaPhase.AVAILABLE,
                        manifest = manifest,
                        message = "请先开启「安装应用」权限"
                    )
                }
                withContext(Dispatchers.Main) {
                    Installer.openInstallPermissionSettings(ctx)
                }
                return
            }

            val apkFile = File(ctx.cacheDir, "ota/keyu_${manifest.versionCode}.apk")
            AppState.updateOta {
                it.copy(
                    phase = AppState.OtaPhase.DOWNLOADING,
                    manifest = manifest,
                    progress = 0f,
                    message = "正在下载 ${verLabel(manifest.versionName)}…"
                )
            }

            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val needDownload = !apkFile.exists() || apkFile.length() < 1024 ||
                        (manifest.sha256.isNotEmpty() && !sha256(apkFile).equals(manifest.sha256, ignoreCase = true))
                    if (needDownload) {
                        val targetUrl = manifest.downloadUrl.ifEmpty {
                            "${api.server}/api/attachments/${manifest.attachmentId}/download"
                        }
                        api.downloadUrl(targetUrl, apkFile) { p ->
                            AppState.updateOta { it.copy(progress = p) }
                        }
                    }
                    AppState.updateOta { it.copy(phase = AppState.OtaPhase.VERIFYING, message = "正在校验安装包…") }
                    if (manifest.sha256.isNotEmpty()) {
                        val actual = sha256(apkFile)
                        if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                            apkFile.delete()
                            error("安装包校验失败，请重试")
                        }
                    }
                    val info = Installer.inspect(ctx, apkFile) ?: error("无效的安装包")
                    if (info.pkg != ctx.packageName) error("包名不匹配，已拦截")
                    if (Installer.signatureMatchesInstalled(ctx, apkFile, info.pkg) != true) {
                        error("签名校验未通过，已拦截")
                    }
                    info to apkFile
                }
            }

            res.onSuccess { (info, f) ->
                AppState.updateOta {
                    it.copy(phase = AppState.OtaPhase.INSTALLING, manifest = manifest, message = "正在安装 ${verLabel(manifest.versionName)}…")
                }
                withContext(Dispatchers.Main) {
                    Installer.install(ctx, f, "ota_${manifest.versionCode}", info)
                }
            }.onFailure { e ->
                AppState.updateOta {
                    it.copy(phase = AppState.OtaPhase.ERROR, manifest = manifest, message = e.message ?: "更新失败")
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            var n: Int
            while (ins.read(buf).also { n = it } >= 0) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 版本展示：老的语义化版本（0.1.2）前面加 v；日期版本（2026.11.27 Post1）原样展示。 */
fun verLabel(name: String): String =
    if (Regex("^\\d{1,2}\\.\\d+\\.\\d+$").matches(name)) "v$name" else name
