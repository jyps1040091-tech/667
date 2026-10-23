package com.example.gesturemouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前景相機服務：用前鏡頭（電視則用外接鏡頭）持續抓畫面 → MediaPipe 手部關鍵點 → GestureEngine。
 * Android 11 起背景無法使用相機，所以一定要是前景服務（type=camera），會常駐一則通知。
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "GestureMouse"
        private const val CHANNEL = "gesture_mouse"
        private const val NOTIF_ID = 1
        const val ACTION_TOGGLE = "com.example.gesturemouse.TOGGLE"
        const val ACTION_STOP = "com.example.gesturemouse.STOP"
        const val ACTION_PREVIEW = "com.example.gesturemouse.PREVIEW"
        private const val MODEL = "hand_landmarker.task"

        // ===== 效能設定 =====
        // MediaPipe 內部只會把畫面縮成約 192~224 像素，所以 320x240 就夠用，比 640x480 快很多。
        // 如果覺得遠距離手抓不到，可以改回 640 x 480。
        private const val ANALYSIS_W = 320
        private const val ANALYSIS_H = 240
        // 想更快可以試 true（用 GPU 跑模型）。失敗會自動退回 CPU；如果 App 閃退就改回 false。
        private const val USE_GPU = false

        @Volatile var running = false
            private set
    }

    private lateinit var executor: ExecutorService
    private var landmarker: HandLandmarker? = null
    private var engine: GestureEngine? = null
    private var provider: ProcessCameraProvider? = null
    private var lastTs = 0L
    private var started = false

    // 預覽與統計
    @Volatile private var previewOn = true
    private val previewPending = AtomicBoolean(false)
    private var lastFrameNs = 0L
    private var fps = 0f
    private var msAvg = 0f

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE -> { engine?.requestToggle(); return START_NOT_STICKY }
            ACTION_PREVIEW -> {
                previewOn = !previewOn
                if (!previewOn) {
                    GestureAccessibilityService.instance
                        ?.preview(null, emptyList(), 1f, 1f, "") { }
                }
                return START_NOT_STICKY
            }
        }
        if (started) return START_NOT_STICKY
        started = true

        // 必須在收到 startForegroundService 後 5 秒內呼叫
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val svc = GestureAccessibilityService.instance
        if (svc == null) {
            toast("請先在「設定 → 無障礙」開啟手勢滑鼠")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            landmarker = createLandmarker()
        } catch (e: Exception) {
            Log.e(TAG, "載入手部模型失敗", e)
            toast("找不到 assets/$MODEL，請照 README 下載模型")
            stopSelf()
            return START_NOT_STICKY
        }
        val size = svc.screenSize()
        engine = GestureEngine(size.x, size.y, svc)
        bindCamera()
        running = true
        return START_NOT_STICKY
    }

    private fun createLandmarker(): HandLandmarker {
        if (USE_GPU) {
            try {
                return buildLandmarker(Delegate.GPU)
            } catch (e: Exception) {
                Log.w(TAG, "GPU 不可用，改用 CPU", e)
            }
        }
        return buildLandmarker(Delegate.CPU)
    }

    private fun buildLandmarker(delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            .setDelegate(delegate)
            .build()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            // 注意：numHands=2 時，畫面裡只有 1 隻手也會每幀重跑手掌偵測，比較慢。
            // 若不需要雙手手勢（啟動 / 回桌面 / 截圖），改成 1 會明顯變快。
            .setNumHands(2)
            .setRunningMode(RunningMode.VIDEO)
            .setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return HandLandmarker.createFromOptions(this, opts)
    }

    /** 手機用前鏡頭；電視或盒子沒有前鏡頭時，改用外接 USB 鏡頭或後鏡頭。 */
    private fun pickSelector(p: ProcessCameraProvider): CameraSelector {
        val candidates = listOf(
            CameraSelector.DEFAULT_FRONT_CAMERA,
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL).build(),
            CameraSelector.DEFAULT_BACK_CAMERA,
        )
        for (c in candidates) {
            try {
                if (p.hasCamera(c)) return c
            } catch (e: Exception) {
                Log.w(TAG, "檢查鏡頭失敗", e)
            }
        }
        // 都不符合就不限制方向，用系統第一個鏡頭
        return CameraSelector.Builder().build()
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(ANALYSIS_W, ANALYSIS_H),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                p.unbindAll()
                p.bindToLifecycle(this, pickSelector(p), analysis)
            } catch (e: Exception) {
                Log.e(TAG, "開啟鏡頭失敗", e)
                toast("開啟鏡頭失敗：${e.message}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val lmk = landmarker ?: return
            val eng = engine ?: return
            val rot = proxy.imageInfo.rotationDegrees
            var bmp = proxy.toBitmap()
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()

            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1  // VIDEO 模式時間戳必須遞增
            lastTs = ts

            val t0 = System.nanoTime()
            val result = lmk.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
            val t1 = System.nanoTime()
            // 前鏡頭畫面沒有鏡像，這裡手動左右翻轉（1 - x），手往右移游標才會往右
            val hands = result.landmarks().map { pts ->
                Array(21) { i -> Lm((1f - pts[i].x()) * w, pts[i].y() * h) }
            }
            eng.process(hands, w, h, System.nanoTime() / 1e9)

            // ---- 統計 + 左上角預覽 ----
            val ms = (t1 - t0) / 1e6f
            msAvg = if (msAvg == 0f) ms else msAvg * 0.9f + ms * 0.1f
            if (lastFrameNs != 0L) {
                val inst = 1e9f / (t1 - lastFrameNs).coerceAtLeast(1L)
                fps = if (fps == 0f) inst else fps * 0.9f + inst * 0.1f
            }
            lastFrameNs = t1
            if (previewOn && previewPending.compareAndSet(false, true)) {
                val info = "%.0f fps · %.0f ms · %d 隻手".format(fps, msAvg, hands.size)
                val svc = GestureAccessibilityService.instance
                if (svc == null) previewPending.set(false)
                else svc.preview(bmp, hands, w, h, info) { previewPending.set(false) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "分析失敗", e)
        } finally {
            proxy.close()
        }
    }

    // ================= 通知 =================

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "手勢滑鼠", NotificationManager.IMPORTANCE_LOW)
            )
        }
        fun action(a: String, code: Int): PendingIntent = PendingIntent.getService(
            this, code, Intent(this, CameraService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("手勢滑鼠執行中")
            .setContentText("雙手攤開 1 秒＝啟動 / 停用（也可按下方按鈕）")
            .setOngoing(true)
            .addAction(0, "啟動 / 停用", action(ACTION_TOGGLE, 1))
            .addAction(0, "預覽 開 / 關", action(ACTION_PREVIEW, 3))
            .addAction(0, "結束", action(ACTION_STOP, 2))
            .build()
    }

    private fun toast(msg: String) {
        android.os.Handler(mainLooper).post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        running = false
        try { provider?.unbindAll() } catch (_: Exception) {}
        engine?.release()
        GestureAccessibilityService.instance?.cursor(0f, 0f, false, false)
        GestureAccessibilityService.instance?.preview(null, emptyList(), 1f, 1f, "") { }
        executor.shutdown()
        try { landmarker?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
