package com.mtk.keyu

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 校验与安装器：
 * - 使用 PackageInstaller + setAppPackageName + USER_ACTION_NOT_REQUIRED（Android 12+ 同源更新可免确认）；
 * - 严格只做覆盖安装，绝不卸载；
 * - 已装同包名时强制比对签名，防止误装异签包。
 */
object Installer {
    const val ACTION_INSTALL_RESULT = "com.mtk.keyu.INSTALL_RESULT"
    const val EXTRA_ATT_ID = "att_id"
    const val EXTRA_PKG = "pkg"
    const val EXTRA_LABEL = "label"

    data class ApkInfo(val pkg: String, val version: String, val code: Long, val label: String, val sizeBytes: Long)

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun inspect(ctx: Context, apk: File): ApkInfo? {
        if (!apk.exists() || apk.length() < 1024) return null
        val pm = ctx.packageManager
        val pi = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        pi.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        val label = pi.applicationInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pi.packageName
        val code = pi.longVersionCode
        return ApkInfo(pi.packageName, pi.versionName ?: "", code, label, apk.length())
    }

    /** 已装同包名时，校验签名一致；返回 null 表示未安装该包名，true 一致，false 不一致。 */
    fun signatureMatchesInstalled(ctx: Context, apk: File, pkg: String): Boolean? {
        val pm = ctx.packageManager
        val installed = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: Exception) {
            return null
        }
        val incoming = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        fun sigs(pi: PackageInfo): Set<String> =
            pi.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val a = sigs(installed)
        val b = sigs(incoming)
        return a.isNotEmpty() && a == b
    }

    /** 自动安装安全门槛：仅限本机已安装且签名严格一致的覆盖更新包（不绑定特定 App）。 */
    fun isSafeForAutoInstall(ctx: Context, apk: File, info: ApkInfo): Boolean {
        return signatureMatchesInstalled(ctx, apk, info.pkg) == true
    }

    fun install(ctx: Context, apk: File, attId: String, info: ApkInfo) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(info.pkg)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        AppState.updateInstall(attId) {
            it.copy(
                phase = AppState.InstallPhase.INSTALLING,
                pkg = info.pkg,
                label = info.label,
                version = info.version,
                sizeBytes = info.sizeBytes,
                message = "正在提交系统安装…"
            )
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
                .setAction(ACTION_INSTALL_RESULT)
                .putExtra(EXTRA_ATT_ID, attId)
                .putExtra(EXTRA_PKG, info.pkg)
                .putExtra(EXTRA_LABEL, info.label)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    fun launchApp(ctx: Context, pkg: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }

    fun openFile(ctx: Context, file: File, mime: String?): Boolean {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime?.takeIf { it.isNotBlank() } ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
