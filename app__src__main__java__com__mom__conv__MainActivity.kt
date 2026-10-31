package com.mom.conv

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private val queue = ArrayList<Uri>()
    private val durs = ArrayList<Long>()
    private val direct = ArrayList<Boolean>()
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var est: TextView
    private lateinit var bar: ProgressBar
    private lateinit var convBtn: Button
    private lateinit var resSp: Spinner
    private lateinit var qSp: Spinner
    private lateinit var fpsSp: Spinner

    private val sizes = arrayOf(intArrayOf(176, 144), intArrayOf(128, 96), intArrayOf(352, 288))
    private val rates = intArrayOf(150, 250, 400)
    private val fpsList = intArrayOf(15, 12, 10)

    private val ui = object : Runnable {
        override fun run() {
            if (ConvService.status.isNotEmpty()) status.text = ConvService.status
            bar.progress = ConvService.progress
            convBtn.isEnabled = !ConvService.running && queue.isNotEmpty()
            h.postDelayed(this, 500)
        }
    }

    private fun spinner(items: List<String>, sel: Int = 0) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val pick = Button(this).apply { text = "ভিডিও বাছুন" }
        info = TextView(this).apply { text = "ভিডিও বাছা হয়নি"; textSize = 15f; setPadding(0, 24, 0, 24) }
        resSp = spinner(listOf("রেজোলিউশন: 176x144 (মায়ের ফোন)", "রেজোলিউশন: 128x96 (ছোট)", "রেজোলিউশন: 352x288 (বড়)"))
        qSp = spinner(listOf("কোয়ালিটি: ছোট সাইজ (150k)", "কোয়ালিটি: ভালো (250k)", "কোয়ালিটি: সেরা (400k)"), 1)
        fpsSp = spinner(listOf("ফ্রেম রেট: 15 (স্বাভাবিক)", "ফ্রেম রেট: 12 (দ্রুত)", "ফ্রেম রেট: 10 (আরও দ্রুত)"))
        est = TextView(this).apply { textSize = 15f; setPadding(0, 16, 0, 16) }
        convBtn = Button(this).apply { text = "মায়ের ফোনের জন্য কনভার্ট"; isEnabled = false }
        status = TextView(this).apply { textSize = 18f; setPadding(0, 32, 0, 24) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        listOf(pick, info, resSp, qSp, fpsSp, est, convBtn, status, bar).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })

        val l = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateEst() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        resSp.onItemSelectedListener = l
        qSp.onItemSelectedListener = l

        pick.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 1)
        }
        convBtn.setOnClickListener {
            convBtn.isEnabled = false
            val z = sizes[resSp.selectedItemPosition]
            startForegroundService(Intent(this, ConvService::class.java).apply {
                putExtra("uris", Array(queue.size) { queue[it].toString() })
                putExtra("durs", durs.toLongArray())
                putExtra("direct", direct.toBooleanArray())
                putExtra("w", z[0])
                putExtra("h", z[1])
                putExtra("fps", fpsList[fpsSp.selectedItemPosition])
                putExtra("rate", rates[qSp.selectedItemPosition])
            })
            status.text = "শুরু হচ্ছে... অ্যাপ বন্ধ করলেও কাজ চলবে"
        }
    }

    override fun onResume() { super.onResume(); h.post(ui) }
    override fun onPause() { h.removeCallbacks(ui); super.onPause() }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        super.onActivityResult(req, res, d)
        if (req != 1 || res != RESULT_OK || d == null) return
        queue.clear(); durs.clear(); direct.clear()
        d.clipData?.let { c -> for (i in 0 until c.itemCount) queue.add(c.getItemAt(i).uri) }
            ?: d.data?.let { queue.add(it) }
        val sb = StringBuilder()
        queue.forEachIndexed { i, u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            var w = 0; var hh = 0; var dur = 0L; var rot = 0
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, u)
                w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                hh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                r.release()
            } catch (e: Exception) {}
            if (rot == 90 || rot == 270) { val t = w; w = hh; hh = t }
            durs.add(dur)
            val small = minOf(w, hh) in 1..360
            direct.add(small)
            sb.append("${i + 1}) বর্তমান ${w}x$hh • ${fmt(dur)} • ${sizeMb(u)}MB${if (small) " • দ্রুত পথ" else ""}\n")
        }
        info.text = sb.toString().trim()
        status.text = "${queue.size}টি ভিডিও বাছা হয়েছে"
        convBtn.isEnabled = queue.isNotEmpty() && !ConvService.running
        updateEst()
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun sizeMb(u: Uri): Long = try {
        contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) / 1048576 else 0L
        } ?: 0L
    } catch (e: Exception) { 0L }

    private fun updateEst() {
        val sec = durs.sum() / 1000
        val mb = sec * (rates[qSp.selectedItemPosition] + 12) * 1000 / 8 / 1048576
        val z = sizes[resSp.selectedItemPosition]
        est.text = if (sec > 0) "আউটপুট ${z[0]}x${z[1]} • আনুমানিক ${mb}MB" else ""
    }
}
