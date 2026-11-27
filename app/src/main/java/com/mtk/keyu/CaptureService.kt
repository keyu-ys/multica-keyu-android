package com.mtk.keyu

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.io.File

/**
 * 持有全屏捕获授权：
 * - ImageReader(maxImages = 4) 持续监听排空，绝不卡死在旧应用画面；
 * - 闲置且无悬浮面板遮挡时零拷贝保留最新 1 帧作为静止画面兜底（剩余 3 槽常空不阻塞）；
 * - 收到 CAPTURE 指令时先等悬浮球/面板消失并用实绘 1x1 像素触发 SurfaceFlinger 合成新帧；
 * - 若系统录屏等静默抢占导致通道失活，通过悬浮横幅提示并自动拉起一键重授（不依赖易被小米拦截的 Toast）。
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var w = 0
    private var h = 0
    private var dpi = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private var latestCleanImage: Image? = null
    @Volatile private var latestCleanTimeMs = 0L
    @Volatile private var lastAnyFrameMs = 0L

    @Volatile private var captureArmed = false
    @Volatile private var captureReadyAfterMs = 0L
    @Volatile private var captureToken = 0
    private var pulseView: View? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("MFLOAT", "CaptureService start action=${intent?.action}")
        Notify.ensure(this)
        startForeground(2, Notify.ongoing(this, "悬浮助手运行中"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        when (intent?.action) {
            ACTION_START -> {
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra("data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra("data")
                }
                if (data != null) setup(intent.getIntExtra("code", 0), data)
            }
            ACTION_CAPTURE -> requestFreshFrame()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setup(code: Int, data: Intent) {
        teardown()
        val wm = getSystemService(WindowManager::class.java)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        w = m.widthPixels
        h = m.heightPixels
        dpi = m.densityDpi

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                android.util.Log.i("MFLOAT", "MediaProjection stopped by system")
                teardown()
                getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("bubble", false).apply()
                BubbleService.stop(this@CaptureService)
                stopSelf()
            }
        }, mainHandler)

        val thread = HandlerThread("mfloat-capture").apply { start() }
        val handler = Handler(thread.looper)
        imageThread = thread
        imageHandler = handler

        projection = mp
        Outbox.projection = mp
        lastAnyFrameMs = SystemClock.uptimeMillis()

        // 4 缓冲：最多持有 1 帧最新干净画面，其余 3 槽常空，保证 SurfaceFlinger 永不堵塞
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4)
        ir.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, handler)
        reader = ir

        vd = mp.createVirtualDisplay(
            "cap", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, handler
        )
    }

    private fun onFrameAvailable(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        val now = SystemClock.uptimeMillis()
        lastAnyFrameMs = now

        try {
            if (captureArmed && now >= captureReadyAfterMs) {
                captureArmed = false
                clearLatestCleanImage()
                val file = writeImageToPngIfValid(image)
                image.close()
                if (file != null) {
                    android.util.Log.i("MFLOAT", "Captured fresh frame: ${file.name} (${w}x${h})")
                    mainHandler.post {
                        removePulseView()
                        AppState.capturing = false
                        AnnotateOverlay.show(applicationContext, file.absolutePath)
                    }
                } else {
                    android.util.Log.w("MFLOAT", "Rejected solid-black frame (projection evicted or FLAG_SECURE)")
                    mainHandler.post {
                        removePulseView()
                        notifyBlockedOrEvicted()
                    }
                }
                return
            }

            if (!AppState.overlayVisible && !captureArmed) {
                // 零拷贝缓存最新一帧干净前台画面（不含悬浮面板与标注页）
                clearLatestCleanImage()
                latestCleanImage = image
                latestCleanTimeMs = now
            } else {
                image.close()
            }
        } catch (e: Throwable) {
            android.util.Log.e("MFLOAT", "Frame handling error", e)
            runCatching { image.close() }
        }
    }

    private fun isSolidBlack(bmp: Bitmap): Boolean {
        val cols = 8
        val rows = 8
        val stepX = (bmp.width / (cols + 1)).coerceAtLeast(1)
        val stepY = (bmp.height / (rows + 1)).coerceAtLeast(1)
        for (r in 1..rows) {
            val y = (r * stepY).coerceAtMost(bmp.height - 1)
            for (c in 1..cols) {
                val x = (c * stepX).coerceAtMost(bmp.width - 1)
                val px = bmp.getPixel(x, y)
                val red = (px shr 16) and 0xFF
                val green = (px shr 8) and 0xFF
                val blue = px and 0xFF
                if (red > 3 || green > 3 || blue > 3) return false
            }
        }
        return true
    }

    private fun writeImageToPngIfValid(image: Image): File? {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowPad = rowStride - pixelStride * w
        val bmp = Bitmap.createBitmap(w + rowPad / pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
        bmp.recycle()

        if (isSolidBlack(cropped)) {
            cropped.recycle()
            return null
        }

        val dir = File(cacheDir, "shots").apply { mkdirs() }
        val f = File(dir, "shot_${System.currentTimeMillis()}.png")
        f.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 96, it) }
        cropped.recycle()
        return f
    }

    private fun clearLatestCleanImage() {
        latestCleanImage?.let { runCatching { it.close() } }
        latestCleanImage = null
    }

    private fun requestFreshFrame() {
        val r = reader ?: run {
            promptReauthorize("截屏通道未就绪", "正在拉起屏幕授权…")
            return
        }

        val token = ++captureToken
        captureArmed = false
        imageHandler?.post {
            // 仅等待 45ms 让悬浮球/面板从 SurfaceFlinger 消失，避免错失面板关闭刷新帧
            captureReadyAfterMs = SystemClock.uptimeMillis() + 45L
            captureArmed = true
        }

        // 55ms 和 200ms 触发实绘 2x2 微透明像素脉冲，迫使 SurfaceFlinger 对完全静止画面合成新帧
        mainHandler.postDelayed({
            if (captureArmed && captureToken == token) triggerSurfaceRefresh(0x03000000)
        }, 55L)
        mainHandler.postDelayed({
            if (captureArmed && captureToken == token) triggerSurfaceRefresh(0x04000000)
        }, 200L)

        // 480ms 仍未收到新帧：检查通道是否存活；存活则直接采用零拷贝缓存的当前干净帧，失活则提示并自动重授
        mainHandler.postDelayed({
            if (!captureArmed || captureToken != token) return@postDelayed
            imageHandler?.post {
                if (!captureArmed || captureToken != token) return@post
                captureArmed = false
                val now = SystemClock.uptimeMillis()
                val channelAlive = (now - lastAnyFrameMs) < 12_000L
                val cached = latestCleanImage
                if (channelAlive && cached != null) {
                    latestCleanImage = null
                    val file = runCatching { writeImageToPngIfValid(cached) }.getOrNull()
                    runCatching { cached.close() }
                    if (file != null) {
                        android.util.Log.i("MFLOAT", "Captured static screen from live clean buffer: ${file.name}")
                        mainHandler.post {
                            removePulseView()
                            AppState.capturing = false
                            AnnotateOverlay.show(applicationContext, file.absolutePath)
                        }
                        return@post
                    }
                    android.util.Log.w("MFLOAT", "Cached frame was solid black (projection evicted by system recorder or FLAG_SECURE)")
                    mainHandler.post {
                        removePulseView()
                        notifyBlockedOrEvicted()
                    }
                    return@post
                }
                android.util.Log.w("MFLOAT", "Capture channel stalled/evicted (lastAnyFrameAge=${now - lastAnyFrameMs}ms, hasCached=${cached != null})")
                mainHandler.post {
                    removePulseView()
                    promptReauthorize("截屏权限已被系统回收", "请点击「共享屏幕」恢复截屏")
                }
            }
        }, 480L)
    }

    private fun notifyBlockedOrEvicted() {
        AppState.capturing = false
        FloatPanelOverlay.showBanner(
            applicationContext,
            "无法截取当前画面",
            "系统录屏正在占用或页面禁止截屏，关闭录屏后重开悬浮球"
        )
        AppState.notifyBubble()
    }

    private fun promptReauthorize(title: String, subtitle: String) {
        AppState.capturing = false
        FloatPanelOverlay.showBanner(applicationContext, title, subtitle)
        AppState.notifyBubble()
        runCatching {
            val i = Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("request_capture", true)
            startActivity(i)
        }
    }

    /** 提交真实绘制的 2x2 极微透明色块，迫使 HWUI 提交真实 BLAST Buffer 触发 SurfaceFlinger 合成。 */
    private fun triggerSurfaceRefresh(argb: Int) {
        runCatching {
            val wm = getSystemService(WindowManager::class.java)
            removePulseView()
            val v = View(this).apply {
                setBackgroundColor(argb)
            }
            val lp = WindowManager.LayoutParams(
                2, 2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            wm.addView(v, lp)
            v.invalidate()
            pulseView = v
            mainHandler.postDelayed({ removePulseView() }, 50L)
        }
    }

    private fun removePulseView() {
        pulseView?.let { v ->
            runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        }
        pulseView = null
    }

    private fun teardown() {
        captureArmed = false
        captureToken++
        mainHandler.removeCallbacksAndMessages(null)
        removePulseView()
        imageHandler?.post { clearLatestCleanImage() }
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { imageThread?.quitSafely() }
        Outbox.projection = null
        vd = null
        reader = null
        projection = null
        imageThread = null
        imageHandler = null
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "start"
        const val ACTION_CAPTURE = "capture"
        const val ACTION_STOP = "stop"
        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).setAction(ACTION_START).putExtra("code", code).putExtra("data", data))
        }
        fun capture(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).setAction(ACTION_CAPTURE))
        }
        fun stop(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
