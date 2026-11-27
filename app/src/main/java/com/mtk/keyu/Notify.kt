package com.mtk.keyu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notify {
    const val CH_SERVICE = "service"     // 常驻低打扰前台服务通知
    const val CH_REPLY = "reply"         // 智能体新回复提醒
    const val CH_INSTALL = "install"     // 安装包安装完成提醒

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "后台保活与悬浮球", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                description = "保持悬浮助手和后台回复监听正常运行"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REPLY, "智能体回复提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setShowBadge(true)
                description = "智能体完成回复或发来安装包时弹出通知"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INSTALL, "安装包状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "安装包下载与安装完成提醒"
            }
        )
    }

    fun openAppIntent(ctx: Context, sessionId: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (sessionId != null) i.putExtra("open_session", sessionId)
        return PendingIntent.getActivity(
            ctx,
            sessionId?.hashCode() ?: 0,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun ongoing(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Multica 助手")
            .setContentText(text)
            .setContentIntent(openAppIntent(ctx))
            .setOngoing(true)
            .build()

    /** 把 Markdown 原始回复清洗成适合系统通知栏阅读的干净摘要。 */
    fun cleanSummary(raw: String, hasApk: Boolean, imageCount: Int): String {
        val stripped = raw
            .replace(Regex("```[\\s\\S]*?```"), " [代码] ")
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s+"), "")
            .replace(Regex("[*_~`>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val prefix = buildString {
            if (hasApk) append("[安装包] ")
            if (imageCount > 0 && stripped.isEmpty()) append("[图片×$imageCount] ")
        }
        val body = (prefix + stripped).trim().ifEmpty { "收到一条新回复，点击查看" }
        return if (body.length > 140) body.take(140) + "…" else body
    }

    fun reply(ctx: Context, sessionId: String, agentName: String, sessionTitle: String, summary: String) {
        val title = agentName.ifEmpty { "智能体" }
        val b = Notification.Builder(ctx, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, sessionId))
        if (sessionTitle.isNotEmpty() && sessionTitle != title) {
            b.setSubText(sessionTitle.take(24))
        }
        ctx.getSystemService(NotificationManager::class.java).notify(sessionId.hashCode(), b.build())
    }

    fun openIssueIntent(ctx: Context, issueId: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open_issue", issueId)
        return PendingIntent.getActivity(ctx, ("issue:$issueId").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 工作（收件箱）提醒：同一任务只保留一条通知，新动态覆盖旧的。 */
    fun work(ctx: Context, item: InboxItem) {
        val actor = WorkText.actorName(item.actorType, item.actorId)
        val title = listOf(item.issueIdentifier, item.title).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "工作动态" }
        val body = if (item.body.isNotBlank() && item.type in setOf("new_comment", "mentioned"))
            "$actor：" + cleanSummary(item.body, false, 0)
        else "$actor ${WorkText.inboxVerb(item)}" + (item.body.takeIf { it.isNotBlank() }?.let { "：" + cleanSummary(it, false, 0) } ?: "")
        val n = Notification.Builder(ctx, CH_REPLY)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSubText("工作")
            .setAutoCancel(true)
            .setContentIntent(if (item.issueId.isNotEmpty()) openIssueIntent(ctx, item.issueId) else openAppIntent(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(("issue:" + item.issueId.ifEmpty { item.id }).hashCode(), n)
    }

    fun installDone(ctx: Context, label: String, pkg: String) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = if (launch != null) {
            PendingIntent.getActivity(ctx, pkg.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            openAppIntent(ctx)
        }
        val n = Notification.Builder(ctx, CH_INSTALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$label 已更新完成")
            .setContentText("点击直接打开 $label")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(("inst:$pkg").hashCode(), n)
    }
}
