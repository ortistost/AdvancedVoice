package com.chavix.seskesici

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class EditorActivity : Activity() {
    private lateinit var wave: WaveView
    private lateinit var info: TextView
    private lateinit var normalBox: LinearLayout
    private lateinit var takeBox: LinearLayout
    private lateinit var takeTimer: TextView
    private lateinit var takeInfo: TextView
    private lateinit var takeRecBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val player = Player()
    private val rec = Recorder()

    private var inTake = false
    private var takeBase = ShortArray(0)
    private var insertAt = 0
    private var limitTenths = -1 // -1: no limit (modes 1 and 2)
    private var exporting = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 50)
        }
    }

    // ------------------------------------------------------------ UI building

    private fun btn(label: String, color: Int = 0xFF2B2B2B.toInt(), onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            setOnClickListener { onClick() }
        }

    private fun row(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in v) {
            addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFFAAAAAA.toInt())
        setPadding(dp(4), dp(10), 0, dp(2))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(28), dp(12), dp(16))
        }
        info = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        wave = WaveView(this)
        wave.onTouched = { player.stop() }
        wave.pcm = Store.pcm
        root.addView(info)
        root.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        normalBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        normalBox.addView(row(btn("▶ Dinle") { play() }, btn("■ Dur") { player.stop() }, btn("↶ Geri al") { undo() }))
        normalBox.addView(label("Kesilecek aralık (dalgada uçları sürükleyebilirsin)"))
        normalBox.addView(row(
            btn("Baş = imleç") { setSelStart() },
            btn("Bit = imleç") { setSelEnd() },
            btn("Bit = son") { setSelToEnd() }
        ))
        normalBox.addView(row(btn("Seçimi kaldır") { clearSel() }, btn("✂ Seçimi sil", 0xFFB9770E.toInt()) { deleteSel() }))
        normalBox.addView(row(btn("Hepsini sil", 0xFFC0392B.toInt()) { deleteAll() }))
        normalBox.addView(label("Konuşmaya devam et"))
        normalBox.addView(btn("1  En sondan devam et") { beginTake(1) })
        normalBox.addView(btn("2  İmleçten başlayıp araya ekle") { beginTake(2) })
        normalBox.addView(btn("3  Seçili aralığı değiştir") { beginTake(3) })
        normalBox.addView(label(""))
        normalBox.addView(btn("✓  Bitir ve WhatsApp'a gönder", 0xFF1E8E4E.toInt()) { finishAndShare() }.apply { textSize = 17f })
        root.addView(normalBox)

        takeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        takeInfo = TextView(this).apply { setTextColor(0xFFAAAAAA.toInt()); setPadding(0, dp(8), 0, 0) }
        takeTimer = TextView(this).apply {
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        takeRecBtn = btn("● Kayda başla", 0xFFC0392B.toInt()) { takeToggle() }.apply { textSize = 17f }
        takeBox.addView(takeInfo)
        takeBox.addView(takeTimer)
        takeBox.addView(takeRecBtn)
        takeBox.addView(row(btn("▶ Dinle") { takePlay() }, btn("✓ Onayla", 0xFF1E8E4E.toInt()) { confirmTake() }, btn("✕ Vazgeç") { cancelTake() }))
        root.addView(takeBox)

        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0B0B0B.toInt()); addView(root) })
    }

    override fun onStart() {
        super.onStart()
        OverlayService.instance?.setPanelVisible(false)
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        player.stop()
        rec.stop()
        OverlayService.instance?.setPanelVisible(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inTake) cancelTake() else super.onBackPressed()
    }

    // ------------------------------------------------------------ normal mode

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun syncWave() {
        wave.pcm = Store.pcm
        wave.cursor = wave.cursor.coerceIn(0, Store.pcm.size)
        wave.selStart = -1
        wave.selEnd = -1
    }

    private fun play() {
        val pcm = Store.pcm
        if (pcm.isEmpty()) return
        var from = wave.cursor
        if (from >= pcm.size - 1) from = 0
        player.play(pcm, from, { p -> runOnUiThread { wave.cursor = p } }, {})
    }

    private fun undo() {
        player.stop()
        if (Store.undo()) syncWave() else toast("Geri alınacak bir şey yok")
    }

    private fun setSelStart() {
        val n = Store.pcm.size
        wave.selStart = wave.cursor
        if (wave.selEnd <= wave.selStart) wave.selEnd = n
        if (wave.selEnd <= wave.selStart) { wave.selStart = -1; wave.selEnd = -1 }
    }

    private fun setSelEnd() {
        wave.selEnd = wave.cursor
        if (wave.selStart < 0 || wave.selStart >= wave.selEnd) wave.selStart = 0
        if (wave.selEnd <= wave.selStart) { wave.selStart = -1; wave.selEnd = -1 }
    }

    private fun setSelToEnd() {
        if (wave.selStart < 0) wave.selStart = wave.cursor
        wave.selEnd = Store.pcm.size
        if (wave.selEnd <= wave.selStart) { wave.selStart = -1; wave.selEnd = -1 }
    }

    private fun clearSel() {
        wave.selStart = -1
        wave.selEnd = -1
    }

    private fun deleteSel() {
        if (!wave.hasSel) { toast("Önce başlangıç ve bitiş seç"); return }
        player.stop()
        val a = wave.selStart
        val b = wave.selEnd
        Store.pushUndo(Store.pcm)
        Store.pcm = Store.pcm.cut(a, b)
        wave.cursor = a
        syncWave()
    }

    private fun deleteAll() {
        if (Store.pcm.isEmpty()) return
        player.stop()
        Store.pushUndo(Store.pcm)
        Store.pcm = ShortArray(0)
        wave.cursor = 0
        syncWave()
    }

    // ------------------------------------------------------------ take mode (continue recording)

    private fun beginTake(kind: Int) {
        if (exporting) return
        player.stop()
        val cur = Store.pcm
        when (kind) {
            1 -> { takeBase = cur; insertAt = cur.size; limitTenths = -1 }
            2 -> { takeBase = cur; insertAt = wave.cursor.coerceIn(0, cur.size); limitTenths = -1 }
            else -> {
                if (!wave.hasSel) { toast("Önce başlangıç ve bitiş seç"); return }
                val a = wave.selStart
                val b = wave.selEnd
                takeBase = cur.cut(a, b)
                insertAt = a
                limitTenths = Math.round((b - a) * 10.0 / RATE).toInt()
            }
        }
        rec.stop()
        rec.reset()
        inTake = true
        wave.pcm = takeBase
        wave.cursor = insertAt
        clearSel()
        takeInfo.text = "Eklenecek yer: ${secs(insertAt)} sn" +
            if (limitTenths >= 0) " · seçilen süre: ${limitTenths / 10}.${limitTenths % 10} sn (yeşil: içinde, kırmızı: aştı)" else ""
        normalBox.visibility = View.GONE
        takeBox.visibility = View.VISIBLE
    }

    private fun takeToggle() {
        if (rec.isRunning) {
            rec.stop()
            return
        }
        player.stop()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Mikrofon izni yok")
            return
        }
        if (!rec.start()) toast("Mikrofon açılamadı")
    }

    private fun takePlay() {
        if (rec.isRunning) rec.stop()
        val t = rec.snapshot()
        if (t.isEmpty()) { toast("Henüz kayıt yok"); return }
        player.stop()
        player.play(t, 0, {}, {})
    }

    private fun confirmTake() {
        rec.stop()
        player.stop()
        val take = rec.snapshot()
        if (take.isEmpty()) { toast("Kayıt boş, önce konuş"); return }
        Store.pushUndo(Store.pcm)
        Store.pcm = takeBase.insertAt(insertAt, take)
        rec.reset()
        inTake = false
        wave.pcm = Store.pcm
        wave.cursor = insertAt + take.size
        clearSel()
        normalBox.visibility = View.VISIBLE
        takeBox.visibility = View.GONE
    }

    private fun cancelTake() {
        rec.stop()
        player.stop()
        rec.reset()
        inTake = false
        wave.pcm = Store.pcm
        wave.cursor = insertAt.coerceIn(0, Store.pcm.size)
        clearSel()
        normalBox.visibility = View.VISIBLE
        takeBox.visibility = View.GONE
    }

    private fun refresh() {
        if (inTake) {
            val tenths = (rec.size.toLong() * 10 / RATE).toInt()
            takeTimer.text = "${tenths / 10}.${tenths % 10} sn"
            takeTimer.setTextColor(
                when {
                    limitTenths < 0 -> Color.WHITE
                    tenths <= limitTenths -> 0xFF2ECC71.toInt()
                    else -> 0xFFE74C3C.toInt()
                }
            )
            takeRecBtn.text = when {
                rec.isRunning -> "■ Durdur"
                rec.size > 0 -> "● Devam et"
                else -> "● Kayda başla"
            }
        } else {
            var t = "İmleç ${secs(wave.cursor)} / ${secs(Store.pcm.size)} sn"
            if (wave.hasSel) t += "\nSeçim ${secs(wave.selStart)} – ${secs(wave.selEnd)} (${secs(wave.selEnd - wave.selStart)} sn)"
            info.text = t
        }
    }

    // ------------------------------------------------------------ export and share

    private fun finishAndShare() {
        if (exporting) return
        player.stop()
        val pcm = Store.pcm
        if (pcm.isEmpty()) { toast("Kayıt boş"); return }
        exporting = true
        toast("Hazırlanıyor…")
        Thread {
            try {
                val dir = File(cacheDir, "shared")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val r = Exporter.export(pcm, dir)
                runOnUiThread { exporting = false; share(r) }
            } catch (e: Exception) {
                runOnUiThread { exporting = false; toast("Dışa aktarma hatası: ${e.message}") }
            }
        }.start()
    }

    private fun share(r: Exporter.Result) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fp", r.file)
        val base = Intent(Intent.ACTION_SEND)
            .setType(r.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        toast("Biçim: ${r.label}")
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                startActivity(Intent(base).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
            }
        }
        startActivity(Intent.createChooser(base, "Gönder"))
    }
}
