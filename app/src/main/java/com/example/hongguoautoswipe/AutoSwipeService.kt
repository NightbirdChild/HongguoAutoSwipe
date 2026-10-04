package com.example.hongguoautoswipe

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.GestureDescription
import android.widget.Button
import android.widget.Toast
import kotlin.math.abs
import kotlin.random.Random

/**
 * 核心服务：通过无障碍能力，在目标应用（红果短剧）前台时周期性模拟一次上滑手势。
 * 不读取、不修改目标应用的任何内容，仅执行手势注入。
 */
class AutoSwipeService : AccessibilityService() {

    companion object {
        private const val TAG = "HongguoAutoSwipe"

        const val PREFS = "hongguo_settings"
        const val KEY_INTERVAL = "interval_seconds"
        const val KEY_JITTER = "jitter"
        const val KEY_ONLY_TARGET = "only_target"
        const val KEY_TARGET_PACKAGE = "target_package"
        const val KEY_OVERLAY_WANTED = "overlay_wanted"

        /** 红果短剧（国内版）包名；海外版为 com.phoenix.read.oversea.gp，可在主界面修改 */
        const val DEFAULT_TARGET_PACKAGE = "com.phoenix.read"

        /** 由主界面或悬浮球置为 true / false，决定是否自动翻页 */
        @Volatile
        var swipeOn = false

        /** 无障碍服务连接成功后才有值 */
        @Volatile
        var instance: AutoSwipeService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastScrollAt = 0L

    private var windowManager: WindowManager? = null
    private var overlayButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val loop = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (t: Throwable) {
                Log.e(TAG, "tick failed", t)
            }
            handler.postDelayed(this, nextDelayMillis())
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.postDelayed(loop, nextDelayMillis())
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OVERLAY_WANTED, false) && Settings.canDrawOverlays(this)) {
            showOverlay()
        }
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录最近一次滚动（用户手动滑或上一次自动翻页），避免短时间内连翻两页
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastScrollAt = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stopEverything()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        handler.removeCallbacks(loop)
        swipeOn = false
        hideOverlay()
        windowManager = null
        instance = null
    }

    fun isTargetForeground(): Boolean {
        val target = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE)?.trim().orEmpty()
        if (target.isEmpty()) return false
        return currentForegroundPackage()?.equals(target, ignoreCase = true) == true
    }

    private fun currentForegroundPackage(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    private fun tick() {
        refreshOverlayText()
        if (!swipeOn) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONLY_TARGET, true) && !isTargetForeground()) return
        // 1.5 秒内发生过滚动就跳过本轮，防止和手动滑动撞车
        if (System.currentTimeMillis() - lastScrollAt < 1500) return
        performSwipe()
    }

    fun performSwipeNow() = performSwipe()

    private fun performSwipe() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path().apply {
            // 起止点带少量随机，避免每次都精确落在同一位置
            moveTo(w * (0.45f + Random.nextFloat() * 0.10f), h * (0.72f + Random.nextFloat() * 0.06f))
            lineTo(w * (0.45f + Random.nextFloat() * 0.10f), h * (0.26f + Random.nextFloat() * 0.06f))
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L + Random.nextLong(120)))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "swipe completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "swipe cancelled")
            }
        }, null)
    }

    private fun nextDelayMillis(): Long {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val base = prefs.getInt(KEY_INTERVAL, 20).coerceIn(2, 600) * 1000L
        if (!prefs.getBoolean(KEY_JITTER, true)) return base
        val jitter = (base * 0.15).toLong().coerceAtLeast(500)
        return base + Random.nextLong(-jitter, jitter + 1)
    }

    // ---------- 悬浮球 ----------

    fun showOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (overlayButton != null) return true
        val wm = windowManager ?: return false
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()

        val btn = Button(this).apply {
            text = if (swipeOn) "⏸" else "▶"
            setTextColor(Color.LTGRAY)
            textSize = 18f
            setMinWidth(sizePx)
            setMinHeight(sizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xAA222222.toInt())
                setStroke((1 * density).toInt(), 0x55FFFFFF)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * density).toInt()
            y = (280 * density).toInt()
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        val longPressRunnable = Runnable {
            longPressed = true
            hideOverlay()
            Toast.makeText(this, "悬浮球已隐藏，可在主界面重新打开", Toast.LENGTH_SHORT).show()
        }

        btn.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    overlayParams?.let { startX = it.x; startY = it.y }
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPressRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && (abs(ev.rawX - downRawX) > 10 || abs(ev.rawY - downRawY) > 10)) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        overlayParams?.let { p ->
                            p.x = startX + (ev.rawX - downRawX).toInt()
                            p.y = startY + (ev.rawY - downRawY).toInt()
                            try {
                                wm.updateViewLayout(btn, p)
                            } catch (t: Throwable) {
                                // 悬浮球可能已被移除
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressed) toggleSwipe()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(btn, params)
        } catch (t: Throwable) {
            Log.e(TAG, "add overlay failed", t)
            return false
        }
        overlayButton = btn
        overlayParams = params
        return true
    }

    fun hideOverlay() {
        val btn = overlayButton ?: return
        try {
            windowManager?.removeView(btn)
        } catch (t: Throwable) {
            // 悬浮球可能已被移除
        }
        overlayButton = null
        overlayParams = null
    }

    fun refreshOverlayText() {
        overlayButton?.let { btn ->
            btn.text = if (swipeOn) "⏸" else "▶"
        }
    }

    private fun toggleSwipe() {
        swipeOn = !swipeOn
        refreshOverlayText()
        Toast.makeText(this, if (swipeOn) "开始自动翻页" else "已停止自动翻页", Toast.LENGTH_SHORT).show()
    }
}
