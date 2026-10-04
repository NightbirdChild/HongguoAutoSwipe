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
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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
    private lateinit var intervalLayout: View

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
        intervalLayout = findViewById(R.id.intervalLayout)

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
        switchSmartEnd.setOnCheckedChangeListener { _, _ ->
            saveSettings()
            updateSmartVisibility()
        }

        switchOverlay.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AutoSwipeService.KEY_OVERLAY_WANTED, checked).apply()
            if (checked) {
                if (Settings.canDrawOverlays(this)) {
                    if (AutoSwipeService.instance?.showOverlay() != true) {
                        toast("请先开启无障碍服务")
                    }
                } else {
                    toast("需要悬浮窗权限，请在接下来的页面中允许本应用")
                    openOverlayPermissionPage()
                }
            } else {
                AutoSwipeService.instance?.hideOverlay()
            }
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                AutoSwipeService.instance?.showOverlay()
                toast("悬浮窗权限已授予")
            } else {
                openOverlayPermissionPage()
            }
        }

        findViewById<Button>(R.id.btnAppDetails).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
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

        updateSmartVisibility()
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

    /** 智能跳广告模式下，翻页间隔/随机浮动与定时翻页无关，直接隐藏 */
    private fun updateSmartVisibility() {
        val smart = switchSmartEnd.isChecked
        intervalLayout.isVisible = !smart
        switchJitter.isVisible = !smart
    }

    private fun openOverlayPermissionPage() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
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

    private fun installedVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus() {
        val accOn = isAccessibilityServiceEnabled()
        val overlayPerm = Settings.canDrawOverlays(this)
        statusView.text = buildString {
            append("● 当前安装版本：v${installedVersion()}\n")
            append(
                if (accOn) "● 无障碍服务：已开启\n"
                else "● 无障碍服务：未开启（点下方按钮开启）\n"
            )
            append(
                if (overlayPerm) "● 悬浮窗权限：已授予"
                else "● 悬浮窗权限：未授予（点下方按钮授予）"
            )
            if (accOn && overlayPerm) {
                append("\n● 自动跳广告：")
                append(if (AutoSwipeService.swipeOn) "运行中" else "已停止，点「开始」")
            }
        }
        statusView.setTextColor(
            if (accOn && overlayPerm) 0xFF2E7D32.toInt() else 0xFFB71C1C.toInt()
        )
        btnToggle.text = if (AutoSwipeService.swipeOn) "停止" else "开始自动跳广告"
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
