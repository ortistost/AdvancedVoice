package com.chavix.seskesici

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

class OverlayService : Service() {
    companion object {
        @Volatile var instance: OverlayService? = null
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CH = "seskesici"
        private const val SCALE = 2 // capture is real size / SCALE
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    // capture
    private var projection: MediaProjection? = null
    private var projCb: MediaProjection.Callback? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capThread: HandlerThread? = null
    private lateinit var cap: Handler
    private var capW = 0
    private var capH = 0

    // touched only on the capture thread
    private var raw = ByteArray(0)
    private var rowStride = 0
    private var pixStride = 4
    private var hasFrame = false
    private var scheduled = false
    private var ref: IntArray? = null
    private var region: Region? = null
    private var lastPct = -1
    private var lastOk = false

    // UI (main thread)
    private var panel: LinearLayout? = null
    private lateinit var panelLp: WLP
    private lateinit var handleBtn: TextView
    private lateinit var selBtn: TextView
    private lateinit var startBtn: TextView
    private lateinit var editBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var infoTv: TextView
    private var selector: View? = null
    private var startEnabled = false
    private var calibrated = false
    private var recording = false
    private var recorder: Recorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    @Suppress("DEPRECATION")
    private fun readData(i: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i?.getParcelableExtra(EXTRA_DATA)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = readData(intent)
        if (data == null || code != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Ses Kesici", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH)
            .setContentTitle("Ses Kesici çalışıyor")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(
            1, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        try {
            setupCapture(code, data)
            buildPanel()
        } catch (e: Exception) {
            toast("Başlatılamadı: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- capture

    private fun setupCapture(code: Int, data: Intent) {
        val b = wm.maximumWindowMetrics.bounds
        capW = b.width() / SCALE
        capH = b.height() / SCALE
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        capThread = HandlerThread("cap").also { it.start() }
        cap = Handler(capThread!!.looper)
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }
        projCb = cb
        p.registerCallback(cb, cap)
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, cap)
        vd = p.createVirtualDisplay(
            "seskesici", capW, capH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, cap
        )
    }

    private fun onFrame(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) return
        try {
            val pl = img.planes[0]
            val bb = pl.buffer
            val n = bb.remaining()
            if (raw.size != n) raw = ByteArray(n)
            bb.get(raw, 0, n)
            rowStride = pl.rowStride
            pixStride = pl.pixelStride
            hasFrame = true
        } finally {
            img.close()
        }
        if (!scheduled) {
            scheduled = true
            cap.postDelayed({ scheduled = false; evaluate() }, 200)
        }
    }

    private fun evaluate() {
        val rg = region ?: return
        val rf = ref ?: return
        if (!hasFrame) return
        val cur = ScreenMatcher.sample(raw, rowStride, pixStride, capW, capH, rg)
        val sim = ScreenMatcher.similarity(rf, cur)
        val pct = (sim * 100).toInt()
        val ok = sim >= ScreenMatcher.THRESHOLD
        if (pct != lastPct || ok != lastOk) {
            lastPct = pct
            lastOk = ok
            main.post { onMatch(pct, ok) }
        }
    }

    private fun finishSelection(r: Region) {
        removeSelector()
        setPanelVisible(false)
        val sc = Region(r.l / SCALE, r.t / SCALE, r.r / SCALE, r.b / SCALE)
        cap.postDelayed({
            if (!hasFrame) {
                toast("Ekran görüntüsü alınamadı")
                main.post { setPanelVisible(true) }
                return@postDelayed
            }
            region = sc
            ref = ScreenMatcher.sample(raw, rowStride, pixStride, capW, capH, sc)
            lastPct = -1
            main.post {
                calibrated = true
                setPanelVisible(true)
                cap.postDelayed({ evaluate() }, 400)
            }
        }, 500)
    }

    // ---------------------------------------------------------------- panel

    private fun circle(label: String, bg: Int): TextView = TextView(this).apply {
        text = label
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    }

    private fun buildPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        handleBtn = circle("☰", 0xCC202020.toInt())
        selBtn = circle("[ ]", 0xCC202020.toInt())
        startBtn = circle("▶", 0xCC202020.toInt())
        editBtn = circle("✂", 0xCC202020.toInt())
        stopBtn = circle("■", 0xCCC0392B.toInt())
        infoTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(dp(4), 0, dp(4), 0)
        }
        for (v in listOf(handleBtn, selBtn, startBtn, editBtn, infoTv, stopBtn)) root.addView(v)

        panelLp = WLP(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WLP.TYPE_APPLICATION_OVERLAY, WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(60) }

        attachDrag(handleBtn)
        attachDrag(root)
        selBtn.setOnClickListener { showSelector() }
        startBtn.setOnClickListener { if (startEnabled) startRecording() }
        stopBtn.setOnClickListener { stopRecording() }
        editBtn.setOnClickListener { openEditor() }

        wm.addView(root, panelLp)
        panel = root
        setStartEnabled(false)
        renderPanel()
    }

    private fun attachDrag(v: View) {
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = panelLp.x; sy = panelLp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    panelLp.x = sx + (e.rawX - tx).toInt()
                    panelLp.y = sy + (e.rawY - ty).toInt()
                    panel?.let { try { wm.updateViewLayout(it, panelLp) } catch (ex: Exception) {} }
                }
            }
            true
        }
    }

    private fun setStartEnabled(ok: Boolean) {
        startEnabled = ok
        startBtn.isClickable = ok
        (startBtn.background as GradientDrawable).setColor(if (ok) 0xFF2ECC71.toInt() else 0xCC1A1A1A.toInt())
        startBtn.setTextColor(if (ok) Color.BLACK else 0xFF666666.toInt())
    }

    private fun onMatch(pct: Int, ok: Boolean) {
        if (recording) return
        setStartEnabled(ok)
        infoTv.text = "%$pct"
    }

    private fun renderPanel() {
        val idle = !recording
        selBtn.visibility = if (idle) View.VISIBLE else View.GONE
        startBtn.visibility = if (idle) View.VISIBLE else View.GONE
        editBtn.visibility = if (idle && Store.pcm.isNotEmpty()) View.VISIBLE else View.GONE
        stopBtn.visibility = if (idle) View.GONE else View.VISIBLE
        if (idle && !calibrated) infoTv.text = "alan seç"
    }

    fun setPanelVisible(v: Boolean) {
        main.post {
            panel?.visibility = if (v) View.VISIBLE else View.GONE
            if (v) renderPanel()
        }
    }

    // ---------------------------------------------------------------- region selector

    private fun showSelector() {
        if (selector != null) return
        val area = RegionSelectView(this)
        val root = FrameLayout(this)
        root.addView(area, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val hint = TextView(this).apply {
            text = "Sol üst köşeye dokun, sağ alt köşeye sürükle (WhatsApp yazı çubuğu gibi sabit bir alan)"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(12), dp(40), dp(12), dp(12))
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun label(t: String, c: Int) = TextView(this).apply {
            text = t
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(c)
            setPadding(dp(24), dp(14), dp(24), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        }
        val ok = label("✓ Onayla", 0xFF1E8E4E.toInt())
        val no = label("✕ İptal", 0xFFC0392B.toInt())
        bar.addView(no)
        bar.addView(ok)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(48) })
        no.setOnClickListener { removeSelector() }
        ok.setOnClickListener {
            val r = area.region()
            if (r == null || r.w < 60 || r.h < 30) toast("Alan çok küçük, tekrar sürükle") else finishSelection(r)
        }
        val lp = WLP(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WLP.TYPE_APPLICATION_OVERLAY,
            WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(root, lp)
        selector = root
    }

    private fun removeSelector() {
        selector?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        selector = null
    }

    // ---------------------------------------------------------------- recording from the panel

    private val ticker = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            infoTv.text = secs(r.size) + " sn"
            main.postDelayed(this, 100)
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Mikrofon izni yok")
            return
        }
        val r = Recorder()
        if (!r.start()) {
            toast("Mikrofon açılamadı")
            return
        }
        recorder = r
        recording = true
        renderPanel()
        main.post(ticker)
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        recording = false
        main.removeCallbacks(ticker)
        r.stop()
        val pcm = r.snapshot()
        lastPct = -1
        if (pcm.size < RATE / 4) {
            toast("Kayıt çok kısa")
            renderPanel()
            return
        }
        var peak = 0
        for (s in pcm) peak = maxOf(peak, abs(s.toInt()))
        if (peak == 0) toast("Mikrofon sessiz geldi (başka uygulama mikrofonu tutuyor olabilir)")
        Store.pcm = pcm
        Store.clearUndo()
        renderPanel()
        openEditor()
    }

    private fun openEditor() {
        try {
            startActivity(Intent(this, EditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Düzenleyici açılamadı: ${e.message}")
        }
    }

    private fun toast(s: String) {
        main.post { Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        recorder?.stop()
        recorder = null
        removeSelector()
        panel?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        panel = null
        try { vd?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        projection?.let { p ->
            projCb?.let { try { p.unregisterCallback(it) } catch (e: Exception) {} }
            try { p.stop() } catch (e: Exception) {}
        }
        projection = null
        capThread?.quitSafely()
        super.onDestroy()
    }
}

class RegionSelectView(ctx: Context) : View(ctx) {
    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f
    private var has = false
    private val fill = Paint().apply { color = 0x3300FF66 }
    private val line = Paint().apply {
        color = 0xFF00FF66.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y; has = true }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> { x1 = e.x; y1 = e.y }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0x55000000)
        if (has) {
            val l = min(x0, x1)
            val t = min(y0, y1)
            val r = maxOf(x0, x1)
            val b = maxOf(y0, y1)
            c.drawRect(l, t, r, b, fill)
            c.drawRect(l, t, r, b, line)
        }
    }

    /** Selected rectangle in screen pixels, or null. */
    fun region(): Region? {
        if (!has) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Region(
            loc[0] + min(x0, x1).toInt(), loc[1] + min(y0, y1).toInt(),
            loc[0] + maxOf(x0, x1).toInt(), loc[1] + maxOf(y0, y1).toInt()
        )
    }
}
