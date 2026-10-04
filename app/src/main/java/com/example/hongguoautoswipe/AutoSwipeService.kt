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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.GestureDescription
import android.widget.Button
import android.widget.Toast
import kotlin.math.abs
import kotlin.random.Random

/**
 * 核心服务：通过无障碍能力，在目标应用（红果短剧）前台时按设定间隔模拟上滑手势。
 * 自适应逻辑：每秒检查一次屏幕，若识别到广告页的“N秒后可继续上滑”倒计时文案，
 * 则暂停翻页，等倒计时结束后立刻补翻一次；普通页面按设定间隔翻页。
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
        const val KEY_AD_WAIT = "ad_wait"

        /** 红果短剧（国内版）包名；海外版为 com.phoenix.read.oversea.gp，可在主界面修改 */
        const val DEFAULT_TARGET_PACKAGE = "com.phoenix.read"

        /** 广告页底部倒计时文案的特征词，如“2秒后可继续上滑观看短剧” */
        private const val AD_TEXT_1 = "秒后可继续上滑"
        private const val AD_TEXT_2 = "秒后可继续观看"

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
    private var nextSwipeAt = 0L

    private var windowManager: WindowManager? = null
    private var overlayButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    /** 每秒轮询：检查前台应用 → 识别广告倒计时 → 判断是否到达翻页时间 */
    private val loop = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (t: Throwable) {
                Log.e(TAG, "tick failed", t)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.postDelayed(loop, 1000L)
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
        // 1.5 秒内发生过滚动就先等动画结束，防止和手动滑动撞车
        if (System.currentTimeMillis() - lastScrollAt < 1500) return
        // 广告页：倒计时文案还在，本轮不动，1 秒后复检；倒计时一消失立即翻页
        if (prefs.getBoolean(KEY_AD_WAIT, true) && findAdCountdownSeconds() != null) return
        val now = System.currentTimeMillis()
        if (now < nextSwipeAt) return
        performSwipe()
        nextSwipeAt = now + nextIntervalMillis()
    }

    /**
     * 遍历当前窗口的文字，找广告倒计时文案（如“2秒后可继续上滑观看短剧”）。
     * 找到返回剩余秒数，没找到返回 null。
     */
    private fun findAdCountdownSeconds(): Int? {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return null

        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < 400) {
            val node = queue.removeFirst()
            visited++
            val text = buildString {
                node.text?.let { append(it) }
                node.contentDescription?.let { append(' '); append(it) }
            }
            if (text.contains(AD_TEXT_1) || text.contains(AD_TEXT_2)) {
                val seconds = Regex("\\d+").find(text)?.value?.toIntOrNull()
                return (seconds ?: 2).coerceIn(0, 120)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
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

    private fun nextIntervalMillis(): Long {
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
