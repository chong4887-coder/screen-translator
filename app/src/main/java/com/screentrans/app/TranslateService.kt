package com.screentrans.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 화면 번역 서비스
 *  - 떠 있는 동그란 버튼: 탭 = 지금 화면 번역(다시 탭하면 번역 지우기)
 *                          두 번 탭 = Claude로 번역, 길게 누르기 = 설정 열기
 *  - 화면 위 투명한 층에 번역을 겹쳐 그림
 */
class TranslateService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val NOTI_ID = 7
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var sw = 0; private var sh = 0

    private var overlay: OverlayView? = null
    private var bubble: TextView? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams

    private val free = FreeTranslator()
    private lateinit var claude: ClaudeTranslator
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        prefs = Prefs(this)
        claude = ClaudeTranslator(prefs)
        startInForeground()
        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            @Suppress("DEPRECATION")
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent?.getParcelableExtra(EXTRA_DATA)
            if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
            try { setupCapture(code, data) } catch (e: Exception) { toast("화면 캡처를 시작하지 못했어요: ${e.message}"); stopSelf(); return START_NOT_STICKY }
            addViews()
            running = true
            toast("버튼을 탭하면 번역, 두 번 탭은 Claude, 길게 누르면 설정")
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("st", "화면 번역", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, TranslateService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "st")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("화면 번역 실행 중")
            .setContentText("탭: 번역 · 두 번 탭: Claude · 길게: 설정")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "끄기", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTI_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun setupCapture(code: Int, data: Intent) {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds; sw = b.width(); sh = b.height()
            dm.densityDpi = resources.displayMetrics.densityDpi
        } else { wm.defaultDisplay.getRealMetrics(dm); sw = dm.widthPixels; sh = dm.heightPixels }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("권한이 없어요")
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        projection = p
        val r = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay("screen-translate", sw, sh, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main)
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun addViews() {
        // 번역을 그리는 투명한 층 (터치는 통과)
        val ov = OverlayView(this)
        val olp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        olp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) olp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(ov, olp)
        overlay = ov

        // 떠 있는 버튼
        val dp = resources.displayMetrics.density
        val size = (54 * dp).toInt()
        val b = TextView(this)
        b.text = "번"; b.textSize = 18f; b.setTextColor(Color.WHITE); b.gravity = Gravity.CENTER
        b.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(220, 255, 77, 46)); setStroke((2 * dp).toInt(), Color.WHITE) }
        b.elevation = 8 * dp
        bubbleLp = WindowManager.LayoutParams(size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        bubbleLp.gravity = Gravity.TOP or Gravity.START
        bubbleLp.x = if (prefs.bubbleX >= 0) prefs.bubbleX else sw - size - (8 * dp).toInt()
        bubbleLp.y = prefs.bubbleY
        wm.addView(b, bubbleLp)
        bubble = b
        b.setOnTouchListener(BubbleTouch())
    }

    /** 버튼 조작: 끌어서 옮기기 / 탭 / 두 번 탭 / 길게 누르기 */
    private inner class BubbleTouch : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var moved = false; private var longFired = false; private var lastTap = 0L
        private val longPress = Runnable { longFired = true; openSettings() }
        private val singleTap = Runnable { onTap(false) }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bubbleLp.x; startY = bubbleLp.y
                    moved = false; longFired = false
                    main.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > 12 || abs(dy) > 12)) { moved = true; main.removeCallbacks(longPress) }
                    if (moved) { bubbleLp.x = (startX + dx).toInt(); bubbleLp.y = (startY + dy).toInt(); wm.updateViewLayout(v, bubbleLp) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (moved) { prefs.bubbleX = bubbleLp.x; prefs.bubbleY = bubbleLp.y; return true }
                    if (longFired || e.actionMasked == MotionEvent.ACTION_CANCEL) return true
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTap < 300) { main.removeCallbacks(singleTap); lastTap = 0; onTap(true) }
                    else { lastTap = now; main.postDelayed(singleTap, 300) }
                }
            }
            return true
        }
    }

    private fun onTap(claudeTap: Boolean) {
        if (busy) return
        val ov = overlay ?: return
        if (!claudeTap && ov.showing) { ov.clear(); return }   // 다시 탭하면 번역 지우기
        if (claudeTap && !prefs.hasClaude) { toast("Claude 번역은 앱 설정에서 API 키를 넣어야 써요"); return }
        scope.launch { translateNow(claudeTap || prefs.engine == "claude") }
    }

    private fun setBubble(text: String, color: Int) {
        bubble?.text = text
        (bubble?.background as? GradientDrawable)?.setColor(color)
    }
    private fun idleBubble() = setBubble("번", Color.argb(220, 255, 77, 46))

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 번역 층과 버튼을 잠깐 숨기고 원래 화면만 캡처 */
    private suspend fun captureClean(): Bitmap? {
        overlay?.visibility = View.INVISIBLE
        bubble?.visibility = View.INVISIBLE
        reader?.acquireLatestImage()?.close()   // 예전 프레임 버리기
        var bmp: Bitmap? = null
        for (i in 0 until 6) {
            delay(if (i == 0) 160 else 80)
            bmp = grab()
            if (bmp != null) break
        }
        bubble?.visibility = View.VISIBLE
        return bmp
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val ps = plane.pixelStride; val rs = plane.rowStride
            val w = img.width; val h = img.height
            val full = Bitmap.createBitmap(w + (rs - ps * w) / ps, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            return if (full.width == w) full else Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
        } finally { img.close() }
    }

    private suspend fun translateNow(useClaude: Boolean) {
        if (busy) return
        busy = true
        setBubble("…", Color.argb(230, 90, 90, 90))
        try {
            val bmp = captureClean() ?: run { toast("화면을 가져오지 못했어요"); overlay?.visibility = View.VISIBLE; return }
            val bubbleRect = Rect(bubbleLp.x, bubbleLp.y, bubbleLp.x + bubbleLp.width, bubbleLp.y + bubbleLp.height)
            val blocks = Ocr.recognize(bmp, bubbleRect)
            if (blocks.isEmpty()) {
                overlay?.clear(); overlay?.visibility = View.VISIBLE
                toast("번역할 글자를 찾지 못했어요")
                bmp.recycle(); return
            }
            var claudeErr = false
            if (useClaude && prefs.hasClaude) {
                setBubble("C", Color.argb(230, 200, 120, 60))
                try { claude.translate(blocks, if (prefs.sendImage) bmp else null) }
                catch (e: Exception) { claudeErr = true; toast((e.message ?: "Claude 오류") + " — 무료 번역으로 대신해요") }
            }
            val rest = blocks.filter { it.ko.isNullOrBlank() }
            // Claude가 번역하지 않은(거절 등) 칸은 무료 번역으로 채움
            if (claudeErr) Unit
            else if (useClaude && prefs.hasClaude && rest.isNotEmpty() && rest.size < blocks.size) toast("Claude가 번역하지 않은 ${rest.size}곳은 무료 번역으로 채웠어요")
            else if (useClaude && prefs.hasClaude && rest.size == blocks.size) toast("Claude가 이 화면을 번역하지 않아서 무료 번역으로 보여줘요")
            if (rest.isNotEmpty()) {
                setBubble("↓", Color.argb(230, 90, 90, 90))   // 무료 번역 (처음엔 언어 모델을 받느라 조금 걸림)
                free.translate(rest)
            }
            bmp.recycle()
            overlay?.show(blocks)
            overlay?.visibility = View.VISIBLE
        } catch (e: Exception) {
            overlay?.visibility = View.VISIBLE
            toast("번역 실패: ${e.message}")
        } finally {
            busy = false
            idleBubble()
        }
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        running = false
        scope.cancel()
        runCatching { overlay?.let { wm.removeView(it) } }
        runCatching { bubble?.let { wm.removeView(it) } }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        free.close()
        super.onDestroy()
    }
}
