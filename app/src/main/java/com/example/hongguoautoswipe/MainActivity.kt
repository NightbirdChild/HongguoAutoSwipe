package com.example.hongguoautoswipe

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusView: TextView
    private lateinit var btnToggle: Button
    private lateinit var editInterval: TextInputEditText
    private lateinit var editPackage: TextInputEditText
    private lateinit var switchOnlyTarget: MaterialSwitch
    private lateinit var switchJitter: MaterialSwitch
    private lateinit var switchOverlay: MaterialSwitch
    private lateinit var switchAdWait: MaterialSwitch
    private lateinit var switchSmartEnd: MaterialSwitch

    private val handler = Handler(Looper.getMainLooper())
    private val uiRefresher = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(AutoSwipeService.PREFS, MODE_PRIVATE)

        statusView = findViewById(R.id.statusView)
        btnToggle = findViewById(R.id.btnToggle)
        editInterval = findViewById(R.id.editInterval)
        editPackage = findViewById(R.id.editPackage)
        switchOnlyTarget = findViewById(R.id.switchOnlyTarget)
        switchJitter = findViewById(R.id.switchJitter)
        switchOverlay = findViewById(R.id.switchOverlay)
        switchAdWait = findViewById(R.id.switchAdWait)
        switchSmartEnd = findViewById(R.id.switchSmartEnd)

        editInterval.setText(prefs.getInt(AutoSwipeService.KEY_INTERVAL, 20).toString())
        editPackage.setText(
            prefs.getString(
                AutoSwipeService.KEY_TARGET_PACKAGE,
                AutoSwipeService.DEFAULT_TARGET_PACKAGE
            )
        )
        switchOnlyTarget.isChecked = prefs.getBoolean(AutoSwipeService.KEY_ONLY_TARGET, true)
        switchJitter.isChecked = prefs.getBoolean(AutoSwipeService.KEY_JITTER, true)
        switchAdWait.isChecked = prefs.getBoolean(AutoSwipeService.KEY_AD_WAIT, true)
        switchSmartEnd.isChecked = prefs.getBoolean(AutoSwipeService.KEY_SMART_END, true)
        switchOverlay.isChecked = prefs.getBoolean(AutoSwipeService.KEY_OVERLAY_WANTED, true)

        editInterval.doAfterTextChanged { saveSettings() }
        editPackage.doAfterTextChanged { saveSettings() }
        switchOnlyTarget.setOnCheckedChangeListener { _, _ -> saveSettings() }
        switchJitter.setOnCheckedChangeListener { _, _ -> saveSettings() }
        switchAdWait.setOnCheckedChangeListener { _, _ -> saveSettings() }
        switchSmartEnd.setOnCheckedChangeListener { _, _ -> saveSettings() }

        switchOverlay.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AutoSwipeService.KEY_OVERLAY_WANTED, checked).apply()
            if (checked) {
                if (Settings.canDrawOverlays(this)) {
                    if (AutoSwipeService.instance?.showOverlay() != true) {
                        toast("请先开启无障碍服务")
                    }
                } else {
                    toast("需要悬浮窗权限，请在接下来的页面中允许本应用")
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            } else {
                AutoSwipeService.instance?.hideOverlay()
            }
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnToggle.setOnClickListener {
            if (AutoSwipeService.instance == null) {
                toast("请先开启无障碍服务")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            AutoSwipeService.swipeOn = !AutoSwipeService.swipeOn
            AutoSwipeService.instance?.refreshOverlayText()
            updateStatus()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val svc = AutoSwipeService.instance
            if (svc == null) {
                toast("请先开启无障碍服务")
            } else {
                svc.performSwipeNow()
                toast("已发送一次上滑手势")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(uiRefresher)
        // 从权限设置页返回后，若已授权则补上悬浮球
        if (switchOverlay.isChecked && Settings.canDrawOverlays(this)) {
            AutoSwipeService.instance?.showOverlay()
        }
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uiRefresher)
    }

    private fun saveSettings() {
        val interval = editInterval.text?.toString()?.toIntOrNull()?.coerceIn(2, 600) ?: 20
        val pkg = editPackage.text?.toString()?.trim()
            ?.takeUnless { it.isEmpty() } ?: AutoSwipeService.DEFAULT_TARGET_PACKAGE
        prefs.edit()
            .putInt(AutoSwipeService.KEY_INTERVAL, interval)
            .putString(AutoSwipeService.KEY_TARGET_PACKAGE, pkg)
            .putBoolean(AutoSwipeService.KEY_ONLY_TARGET, switchOnlyTarget.isChecked)
            .putBoolean(AutoSwipeService.KEY_JITTER, switchJitter.isChecked)
            .putBoolean(AutoSwipeService.KEY_AD_WAIT, switchAdWait.isChecked)
            .putBoolean(AutoSwipeService.KEY_SMART_END, switchSmartEnd.isChecked)
            .apply()
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus() {
        val accOn = isAccessibilityServiceEnabled()
        statusView.text = when {
            !accOn -> "● 无障碍服务未开启，请点击下方按钮前往开启"
            !AutoSwipeService.swipeOn -> "● 已停止：点击“开始自动翻页”后打开红果即可"
            AutoSwipeService.instance?.isTargetForeground() == true ->
                "● 运行中：目标应用在前台，将按设定间隔自动上滑"
            else -> "● 运行中：等待目标应用进入前台…"
        }
        statusView.setTextColor(
            when {
                !accOn -> 0xFFB71C1C.toInt()
                !AutoSwipeService.swipeOn -> 0xFF757575.toInt()
                else -> 0xFF2E7D32.toInt()
            }
        )
        btnToggle.text = if (AutoSwipeService.swipeOn) "停止自动翻页" else "开始自动翻页"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, AutoSwipeService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val flat = expected.flattenToString()
        val short = expected.flattenToShortString()
        return enabled.split(':').any { it.equals(flat, true) || it.equals(short, true) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
