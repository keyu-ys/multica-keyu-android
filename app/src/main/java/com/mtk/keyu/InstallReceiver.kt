package com.mtk.keyu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** 接收 PackageInstaller 真实安装状态：触发确认弹窗、更新卡片状态、并在悬浮球/通知栏提醒完成。 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val attId = intent.getStringExtra(Installer.EXTRA_ATT_ID).orEmpty()
        val pkg = intent.getStringExtra(Installer.EXTRA_PKG).orEmpty()
        val label = intent.getStringExtra(Installer.EXTRA_LABEL).orEmpty().ifEmpty { "应用" }
        val rawMsg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        android.util.Log.i("MFLOAT", "InstallReceiver status=$status att=$attId pkg=$pkg msg=$rawMsg")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (attId.isNotEmpty()) {
                    AppState.updateInstall(attId) {
                        it.copy(phase = AppState.InstallPhase.AWAITING_CONFIRM, message = "请在系统弹窗中点「安装」确认")
                    }
                }
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { runCatching { ctx.startActivity(it) } }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                if (attId.isNotEmpty()) {
                    AppState.updateInstall(attId) {
                        it.copy(phase = AppState.InstallPhase.INSTALLED, pkg = pkg, label = label, message = "安装完成")
                    }
                }
                Toast.makeText(ctx, "$label 已安装完成", Toast.LENGTH_SHORT).show()
                if (!AppState.appInForeground && pkg.isNotEmpty()) {
                    Notify.ensure(ctx)
                    Notify.installDone(ctx, label, pkg)
                    if (AppState.bubbleRunning.value) {
                        val notice = AppState.CompletionNotice(
                            sessionId = "",
                            agentName = label,
                            summary = "新版本已自动覆盖安装完成，可直接打开验收",
                            hasApk = true,
                            installedPkg = pkg
                        )
                        AppState.completionNotice.value = notice
                        AppState.notifyBubble()
                        FloatPanelOverlay.showCompletion(ctx.applicationContext, notice)
                    }
                }
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                if (attId.isNotEmpty()) {
                    AppState.updateInstall(attId) {
                        it.copy(phase = AppState.InstallPhase.ERROR, message = "已取消安装，可点击重试")
                    }
                }
            }

            else -> {
                val friendly = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "包签名或版本与已装应用冲突"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "手机存储空间不足"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与当前设备不兼容"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "安装包已损坏或无效"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被系统策略拦截"
                    else -> rawMsg.ifEmpty { "系统返回错误码 $status" }
                }
                if (attId.isNotEmpty()) {
                    AppState.updateInstall(attId) {
                        it.copy(phase = AppState.InstallPhase.ERROR, message = friendly)
                    }
                }
                Toast.makeText(ctx, "安装未完成：$friendly", Toast.LENGTH_LONG).show()
            }
        }
    }
}
