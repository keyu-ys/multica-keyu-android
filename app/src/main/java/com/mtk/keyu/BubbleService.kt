package com.mtk.keyu

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 极客纯净贴边悬浮球：
 * - 优先展示当前智能体的真实头像（图片圆裁切 / Emoji），无头像时展示品牌八角星黑圆；
 * - 拖动松手丝滑吸附边缘，闲置轻度半透明不干扰操作；
 * - 单击展开极简深色玻璃悬浮控制小面板；长按快捷直达截屏标注。
 */
class BubbleService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var ball: FrameLayout
    private lateinit var avatarImage: ImageView
    private lateinit var starIcon: ImageView
    private lateinit var labelText: TextView
    private lateinit var countBadge: TextView
    private lateinit var unreadDot: View
    private lateinit var lp: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { root.animate().alpha(0.84f).setDuration(260).start() }
    private val bubbleListener: () -> Unit = { mainHandler.post { syncVisual() } }
    private var d = 1f
    private var loadedAvatarUrl: String? = null
    private fun dp(v: Int) = (v * d).roundToInt()

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("MFLOAT", "BubbleService onCreate")
        Notify.ensure(this)
        AppState.bubbleRunning.value = true
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(3, Notify.ongoing(this, "悬浮助手运行中"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(3, Notify.ongoing(this, "悬浮助手运行中"))
        }

        wm = getSystemService(WindowManager::class.java)
        d = resources.displayMetrics.density

        root = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }

        ball = FrameLayout(this).apply {
            elevation = dp(7).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }

        avatarImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        starIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        labelText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        // 外圈细白描边覆盖层，让真实照片头像边缘极度利落
        val ringOverlay = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.parseColor("#E6FFFFFF"))
            }
        }

        ball.addView(avatarImage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ball.addView(starIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        ball.addView(labelText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ball.addView(ringOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(ball, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        // 右上角：极客克制数字角标（品牌黑底白字 + 细白环）
        countBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0C0D10"))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        root.addView(countBadge, FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.TOP or Gravity.END })

        // 左下角：克制的未读/处理中极小状态点
        unreadDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#28C76F"))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        root.addView(unreadDot, FrameLayout.LayoutParams(dp(11), dp(11)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(dp(3), 0, 0, dp(3))
        })

        val dm = resources.displayMetrics
        val boxSize = dp(56)
        lp = WindowManager.LayoutParams(
            boxSize, boxSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - boxSize - dp(8)
            y = (dm.heightPixels * 0.56f).roundToInt()
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val longPressRunnable = Runnable {
            if (!moved) {
                longPressed = true
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                triggerCapture()
            }
        }

        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    wakeUp()
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longPressed = false
                    ball.animate().scaleX(0.90f).scaleY(0.90f).setDuration(90).start()
                    mainHandler.postDelayed(longPressRunnable, 420)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        val m = resources.displayMetrics
                        lp.x = (startX + dx).roundToInt().coerceIn(-dp(4), m.widthPixels - boxSize + dp(4))
                        lp.y = (startY + dy).roundToInt().coerceIn(dp(42), m.heightPixels - boxSize - dp(54))
                        runCatching { wm.updateViewLayout(root, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    ball.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                    if (e.action == MotionEvent.ACTION_UP && !moved && !longPressed) {
                        onTap()
                    } else if (moved) {
                        snapToEdge()
                    }
                    scheduleDim()
                    true
                }
                else -> false
            }
        }

        wm.addView(root, lp)
        AppState.addBubbleListener(bubbleListener)
        syncVisual()
        scheduleDim()
    }

    private fun wakeUp() {
        mainHandler.removeCallbacks(dimRunnable)
        root.animate().alpha(1f).setDuration(120).start()
    }

    private fun scheduleDim() {
        mainHandler.removeCallbacks(dimRunnable)
        mainHandler.postDelayed(dimRunnable, 3400)
    }

    private fun snapToEdge() {
        val screenW = resources.displayMetrics.widthPixels
        val boxSize = lp.width
        val targetX = if (lp.x + boxSize / 2 < screenW / 2) dp(8) else screenW - boxSize - dp(8)
        val fromX = lp.x
        if (fromX == targetX) return
        ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 230
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                lp.x = anim.animatedValue as Int
                runCatching { wm.updateViewLayout(root, lp) }
            }
            start()
        }
    }

    private fun onTap() {
        val completion = AppState.completionNotice.value
        if (completion != null) {
            FloatPanelOverlay.showCompletion(applicationContext, completion)
            return
        }
        val m = resources.displayMetrics
        val onRight = (lp.x + lp.width / 2) >= m.widthPixels / 2
        val yRatio = (lp.y.toFloat() / m.heightPixels.toFloat()).coerceIn(0.15f, 0.85f)
        FloatPanelOverlay.showHub(
            ctx = applicationContext,
            anchorRight = onRight,
            anchorYRatio = yRatio,
            onCapture = { triggerCapture() },
            onStopBubble = {
                getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("bubble", false).apply()
                CaptureService.stop(this)
                stopSelf()
            }
        )
    }

    private fun triggerCapture() {
        if (Outbox.projection == null) {
            Toast.makeText(this, "请重新开启悬浮助手", Toast.LENGTH_SHORT).show()
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            return
        }
        AppState.capturing = true
        root.visibility = View.INVISIBLE
        CaptureService.capture(this)
        mainHandler.postDelayed({
            if (AppState.capturing) AppState.capturing = false
            syncVisual()
        }, 1200)
    }

    private fun syncVisual() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val agentId = prefs.getString("agent", "")!!
        val agent = AppState.agent(agentId)
        val avatarUrl = agent?.avatarUrl.orEmpty().trim()
        val completed = AppState.completionNotice.value != null
        val working = AppState.workingStatus.value != null

        when {
            completed -> {
                ball.setBackgroundColor(Color.parseColor("#1E8E5A"))
                avatarImage.visibility = View.GONE
                starIcon.visibility = View.GONE
                labelText.visibility = View.VISIBLE
                labelText.text = "✓"
                labelText.textSize = 20f
            }
            AvatarLoader.isEmoji(avatarUrl) -> {
                ball.setBackgroundColor(Color.parseColor("#181A20"))
                avatarImage.visibility = View.GONE
                starIcon.visibility = View.GONE
                labelText.visibility = View.VISIBLE
                labelText.text = AvatarLoader.emojiOf(avatarUrl)
                labelText.textSize = 22f
            }
            avatarUrl.isNotEmpty() -> {
                ball.setBackgroundColor(Color.parseColor("#181A20"))
                val cached = AvatarLoader.cached(avatarUrl)
                if (cached != null) {
                    avatarImage.setImageBitmap(cached)
                    avatarImage.visibility = View.VISIBLE
                    starIcon.visibility = View.GONE
                    labelText.visibility = View.GONE
                } else {
                    avatarImage.visibility = View.GONE
                    labelText.visibility = View.GONE
                    starIcon.visibility = View.VISIBLE
                    if (loadedAvatarUrl != avatarUrl) {
                        loadedAvatarUrl = avatarUrl
                        serviceScope.launch {
                            val api = Api(prefs)
                            val bmp = AvatarLoader.load(applicationContext, api, avatarUrl)
                            if (bmp != null) syncVisual()
                        }
                    }
                }
            }
            else -> {
                ball.setBackgroundColor(Color.parseColor("#0C0D10"))
                avatarImage.visibility = View.GONE
                labelText.visibility = View.GONE
                starIcon.visibility = View.VISIBLE
            }
        }

        val n = Outbox.items.size
        countBadge.text = if (n > 9) "9+" else n.toString()
        countBadge.visibility = if (n > 0 && !completed) View.VISIBLE else View.GONE

        unreadDot.visibility = if (!completed && (AppState.unreadCount.intValue > 0 || working)) View.VISIBLE else View.GONE
        root.visibility = if (AppState.overlayVisible || AppState.capturing) View.INVISIBLE else View.VISIBLE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        syncVisual()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        AppState.removeBubbleListener(bubbleListener)
        FloatPanelOverlay.dismissAll(applicationContext)
        runCatching { wm.removeView(root) }
        AppState.bubbleRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, BubbleService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, BubbleService::class.java)) }
    }
}
