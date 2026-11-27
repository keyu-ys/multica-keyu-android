package com.mtk.keyu

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 全屏暗色标注悬浮窗：
 * - 绕开 Android 后台启动 Activity 限制；
 * - 拦截系统返回键优雅关闭；
 * - 显示期间自动隐藏悬浮球，关闭后恢复并刷新角标。
 */
object AnnotateOverlay {
    private var view: View? = null
    private var owner: OverlayLifecycleOwner? = null

    @SuppressLint("InflateParams")
    fun show(ctx: Context, shotPath: String?) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        dismiss(wm)
        AppState.annotateVisible = true

        val lifecycleOwner = OverlayLifecycleOwner().also { owner = it; it.onCreate() }
        val container = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    shotPath?.let { java.io.File(it).delete() }
                    dismiss(wm)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }
        val compose = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent { BrandTheme { AnnotateUi(shotPath) { dismiss(wm) } } }
        }
        container.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(container, lp)
        view = container
        lifecycleOwner.onResume()
    }

    fun dismiss(wm: WindowManager) {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        owner?.onDestroy()
        owner = null
        AppState.annotateVisible = false
    }
}

/** 极简 LifecycleOwner，供 overlay 里的 Compose 使用。 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    fun onCreate() { savedStateController.performRestore(null); lifecycleRegistry.currentState = Lifecycle.State.CREATED }
    fun onResume() { lifecycleRegistry.currentState = Lifecycle.State.RESUMED }
    fun onDestroy() { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED; store.clear() }
}
