package com.example.hongguoautoswipe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
import android.widget.Button
import android.widget.Toast
import kotlin.math.abs
import kotlin.random.Random

/**
 * 核心服务：红果短剧会自动连播剧集（包括自动播放下一集/下一页广告），
 * 本服务唯一职责：在广告可以划走时自动上滑跳过广告。
 *
 * 主信号（横竖屏通用，实测确认）：广告可划走时屏幕会出现
 * “上滑继续观看短剧”（竖屏底部）/ “上滑继续观看剧集”（横屏右上）提示。
 * 每秒读屏一次：见到该提示且不在剧集页 → 立即上滑；倒计时中 → 等待；其余 → 不干预。
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
        const val KEY_SMART_END = "smart_end"

        /** 红果短剧（国内版）包名；海外版为 com.phoenix.read.oversea.gp，可在主界面修改 */
        const val DEFAULT_TARGET_PACKAGE = "com.phoenix.read"

        /** 广告倒计时文案特征，如“2秒后可继续上滑观看短剧” */
        private const val AD_TEXT_1 = "秒后可继续上滑"
        private const val AD_TEXT_2 = "秒后可继续观看"

        /** 广告结束提示（横竖屏通用）：竖屏“上滑继续观看短剧”/横屏“上滑继续观看剧集” */
        private const val AD_PROMPT = "上滑继续观看"

        /** 广告标识文字 */
        private const val AD_LABEL_TEXT = "广告"

        /** 剧集页标识（用于排除广告标识误判）：选集栏 / 集数标题 */
        private const val DRAMA_MARKER_1 = "选集"
        private val EPISODE_REGEX = Regex("第\\d+集|全\\d+集")

        /** 智能跳广告模式下两次上滑之间的冷却 */
        private const val SMART_COOLDOWN_MS = 3000L

        /** 由主界面或悬浮球置为 true / false，决定是否自动跳广告 */
        @Volatile
        var swipeOn = false

        /** 无障碍服务连接成功后才有值 */
        @Volatile
        var instance: AutoSwipeService? = null
            private set
    }

    /** 单次读屏的结果 */
    private class ScreenScan {
        var promptReady = false        // “上滑继续观看…”已出现：广告结束，可划走
        var adSeconds: Int? = null     // “N秒后可继续上滑”倒计时剩余秒数
        var adLabel = false            // “广告”标识可见
        var dramaMarker = false        // “选集/第N集”可见：当前是剧集页
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastScrollAt = 0L
    private var nextSwipeAt = 0L
    private var countdownSeenLastTick = false // 上一秒是否见到“N秒后可继续上滑”倒计时
    private var promptArmed = true            // 提示信号单次触发：触发后须见到提示消失一次才重新武装
    private var lastPromptToastAt = 0L        // 调试反馈节流：提示“检测到可跳过广告”

    private var windowManager: WindowManager? = null
    private var overlayButton: Button? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var tickCount = 0L

    /** 每秒轮询一次读屏；每 15 秒补一次悬浮球（权限后授的场景） */
    private val loop = object : Runnable {
        override fun run() {
            try {
                tickCount++
                maybeRetryOverlay()
                tick()
            } catch (t: Throwable) {
                Log.e(TAG, "tick failed", t)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private fun maybeRetryOverlay() {
        if (tickCount % 15 != 0L) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OVERLAY_WANTED, true) &&
            Settings.canDrawOverlays(this) &&
            overlayButton == null
        ) {
            showOverlay()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.postDelayed(loop, 1000L)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OVERLAY_WANTED, true)) {
            if (Settings.canDrawOverlays(this)) {
                showOverlay()
            } else {
                handler.post {
                    Toast.makeText(
                        this,
                        "悬浮球需要“悬浮窗”权限：请打开本应用主界面，打开“显示悬浮球”开关按提示授权",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录最近一次滚动（用户手动滑或上一次自动划走），避免短时间内连续触发
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
        // 1.5 秒内发生过滚动（用户手动滑或刚划走一个广告）就先等界面稳定
        if (System.currentTimeMillis() - lastScrollAt < 1500) return

        if (prefs.getBoolean(KEY_SMART_END, true)) {
            // 智能跳广告：只对广告出手，剧集连播完全不干预。
            // 提示文字“上滑继续观看短剧/剧集”会出现在两处：①广告可划走时（伴随“广告”标识）
            // ②新一集开头（红果的引导提示，无广告标识）。所以提示必须搭配广告上下文
            // （“广告”标识在、或刚见过倒计时）才是真广告，且“剧集页”判定只统计可见节点。
            // 另外：被划走的广告页会作为离屏邻居残留在节点树里，其提示文字仍在——
            // 因此提示信号是“单次触发”的：触发后必须先见到提示消失一次，才允许再次触发，
            // 否则冷却期一过就会对残留文字再滑一次（跳到下一集）。
            val scan = scanScreen()
            val adWaitOn = prefs.getBoolean(KEY_AD_WAIT, true)
            val hadCountdown = countdownSeenLastTick
            when {
                // 真广告结束：提示 + 广告标识/刚见过倒计时，且不在（可见的）剧集页
                scan.promptReady && promptArmed && (scan.adLabel || hadCountdown) &&
                    !scan.dramaMarker -> {
                    val now = System.currentTimeMillis()
                    if (now - lastPromptToastAt > 8000) {
                        lastPromptToastAt = now
                        Toast.makeText(this, “检测到可跳过广告”, Toast.LENGTH_SHORT).show()
                    }
                    promptArmed = false
                    swipeAndSchedule()
                }
                // 兜底：倒计时刚刚结束（上一秒在、这一秒没了），广告标识还在，不在剧集页
                hadCountdown && scan.adSeconds == null &&
                    scan.adLabel && !scan.dramaMarker ->
                    swipeAndSchedule()
                adWaitOn && scan.adSeconds != null -> Unit   // 倒计时中：等待
                else -> Unit                                 // 剧集连播中：不干预
            }
            if (!scan.promptReady) promptArmed = true // 提示消失过一次，重新武装
            countdownSeenLastTick = scan.adSeconds != null
            return
        }

        // 传统定时模式：按间隔盲翻
        if (System.currentTimeMillis() < nextSwipeAt) return
        swipeAndSchedule()
    }

    /**
     * 遍历当前窗口文字，识别广告的三种信号与剧集页标识。
     */
    private fun scanScreen(): ScreenScan {
        val res = ScreenScan()
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return res

        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++

            val text = buildString {
                node.text?.let { append(it); append(' ') }
                node.contentDescription?.let { append(it) }
            }
            if (text.isNotBlank()) {
                if (!res.promptReady && text.contains(AD_PROMPT)) res.promptReady = true
                if (res.adSeconds == null &&
                    (text.contains(AD_TEXT_1) || text.contains(AD_TEXT_2))
                ) {
                    res.adSeconds = Regex("\\d+").find(text)?.value?.toIntOrNull()
                        ?.coerceIn(0, 120) ?: 2
                }
                if (!res.adLabel && text.contains(AD_LABEL_TEXT)) res.adLabel = true
                // 剧集页标识只统计“可见”节点：广告弹出时被盖住的底层剧集视图不算数
                if (!res.dramaMarker && node.isVisibleToUser &&
                    (text.contains(DRAMA_MARKER_1) || EPISODE_REGEX.containsMatchIn(text))
                ) res.dramaMarker = true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return res
    }

    private fun swipeAndSchedule() {
        performSwipe()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        nextSwipeAt = System.currentTimeMillis() +
            if (prefs.getBoolean(KEY_SMART_END, true)) SMART_COOLDOWN_MS else nextIntervalMillis()
    }

    fun performSwipeNow() = performSwipe()

    private fun performSwipe() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path().apply {
            // 上滑（横屏广告的提示也是“上滑继续观看”），起止点带少量随机
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
        Toast.makeText(this, if (swipeOn) "开始自动跳广告" else "已停止", Toast.LENGTH_SHORT).show()
    }
}
