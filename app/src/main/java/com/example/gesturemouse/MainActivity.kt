package com.example.gesturemouse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var startBtn: Button

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        button("① 授予相機 / 通知權限") { requestPerms() }
        button("② 開啟無障礙設定（找到「手勢滑鼠」並開啟）") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        startBtn = button("③ 啟動手勢滑鼠") { toggleService() }

        root.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = """
                手勢（服務啟動後預設是「停用」，先雙手攤開 1 秒，或按通知列的按鈕）

                • 雙手攤開 1 秒：啟動 / 停用
                • 食指伸出：游標跟著手移動
                • 拇指＋食指快速捏一下：點擊（連捏兩次＝連點兩下）
                • 拇指＋食指捏住不放：按住拖曳
                • 拇指＋中指捏一下：長按（對應電腦版的右鍵）
                • 食指＋中指（YA）上下移動：捲動
                • 比讚按住 0.6 秒：返回
                • 雙手握拳 2 秒：回桌面
                • 單手 C 形 0.8 秒：開啟 Chrome
                • 雙手各比 L 形圍成取景框 1 秒：截圖

                兩隻手同時入鏡時不會操作游標，只偵測雙手手勢。
            """.trimIndent()
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(list.toTypedArray())
    }

    private fun toggleService() {
        if (CameraService.running) {
            stopService(Intent(this, CameraService::class.java))
            startBtn.postDelayed({ refresh() }, 300)
            return
        }
        if (!hasCamera()) { requestPerms(); return }
        if (GestureAccessibilityService.instance == null) {
            Toast.makeText(this, "請先到無障礙設定開啟「手勢滑鼠」", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
        startBtn.postDelayed({ refresh() }, 500)
    }

    private fun refresh() {
        val cam = hasCamera()
        val acc = GestureAccessibilityService.instance != null
        val run = CameraService.running
        status.text = buildString {
            append(if (cam) "✅ 相機權限\n" else "❌ 相機權限（按 ①）\n")
            append(if (acc) "✅ 無障礙服務已開啟\n" else "❌ 無障礙服務未開啟（按 ②）\n")
            append(if (run) "✅ 手勢滑鼠執行中" else "⏹ 尚未啟動")
        }
        startBtn.text = if (run) "③ 停止手勢滑鼠" else "③ 啟動手勢滑鼠"
    }
}
