package com.mom.conv

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

class MainActivity : Activity() {
    private val queue = ArrayList<Uri>()
    private val durs = ArrayList<Long>()
    private var idx = 0
    private var curDur = 0L
    private var transformer: Transformer? = null
    private var tmp: File? = null
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var est: TextView
    private lateinit var bar: ProgressBar
    private lateinit var convBtn: Button
    private lateinit var resSp: Spinner
    private lateinit var qSp: Spinner

    private val sizes = arrayOf(intArrayOf(176, 144), intArrayOf(128, 96), intArrayOf(352, 288))
    private val rates = intArrayOf(150, 250, 400)

    private val poll = object : Runnable {
        override fun run() {
            val ph = ProgressHolder()
            if (transformer?.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) bar.progress = ph.progress
            h.postDelayed(this, 500)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val pick = Button(this).apply { text = "ভিডিও বাছুন" }
        info = TextView(this).apply { text = "ভিডিও বাছা হয়নি"; textSize = 15f; setPadding(0, 24, 0, 24) }
        resSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("রেজোলিউশন: 176x144 (মায়ের ফোন)", "রেজোলিউশন: 128x96 (ছোট)", "রেজোলিউশন: 352x288 (বড়)"))
        }
        qSp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("কোয়ালিটি: ছোট সাইজ (150k)", "কোয়ালিটি: ভালো (250k)", "কোয়ালিটি: সেরা (400k)"))
            setSelection(1)
        }
        est = TextView(this).apply { textSize = 15f; setPadding(0, 16, 0, 16) }
        convBtn = Button(this).apply { text = "মায়ের ফোনের জন্য কনভার্ট"; isEnabled = false }
        status = TextView(this).apply { textSize = 18f; setPadding(0, 32, 0, 24) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        listOf(pick, info, resSp, qSp, est, convBtn, status, bar).forEach { root.addView(it) }
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
            }, 1)
        }
        convBtn.setOnClickListener {
            convBtn.isEnabled = false
            idx = 0
            h.removeCallbacks(poll); h.post(poll)
            startNext()
        }
    }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        super.onActivityResult(req, res, d)
        if (req != 1 || res != RESULT_OK || d == null) return
        queue.clear(); durs.clear()
        d.clipData?.let { c -> for (i in 0 until c.itemCount) queue.add(c.getItemAt(i).uri) }
            ?: d.data?.let { queue.add(it) }
        val sb = StringBuilder()
        queue.forEachIndexed { i, u ->
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
            sb.append("${i + 1}) বর্তমান ${w}x$hh • ${fmt(dur)} • ${sizeMb(u)}MB\n")
        }
        info.text = sb.toString().trim()
        status.text = "${queue.size}টি ভিডিও বাছা হয়েছে"
        convBtn.isEnabled = queue.isNotEmpty()
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

    private fun startNext() {
        if (idx >= queue.size) {
            h.removeCallbacks(poll)
            status.text = "শেষ! Movies/MomVideos ফোল্ডার দেখুন"
            bar.progress = 100
            convBtn.isEnabled = true
            return
        }
        curDur = durs.getOrElse(idx) { 0L }
        status.text = "ভিডিও ${idx + 1}/${queue.size} • ধাপ ১/২ (ছোট করা হচ্ছে)"
        bar.progress = 0
        val out = File(cacheDir, "o.mp4"); out.delete(); tmp = out
        val fx = Effects(
            emptyList(),
            listOf(
                FrameDropEffect.createDefaultFrameDropEffect(15f),
                Presentation.createForWidthAndHeight(352, 288, Presentation.LAYOUT_SCALE_TO_FIT)
            )
        )
        val item = EditedMediaItem.Builder(MediaItem.fromUri(queue[idx])).setEffects(fx).build()
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(this)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(1_000_000).build())
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { to3gp() }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    status.text = "ভুল: ${exportException.message}"; next()
                }
            }).build()
        transformer = t
        t.start(item, out.absolutePath)
    }

    private fun next() { idx++; startNext() }

    private fun run(cmd: String, done: (Boolean) -> Unit) {
        FFmpegKit.executeAsync(cmd,
            { s -> done(ReturnCode.isSuccess(s.returnCode)) },
            null,
            { st ->
                if (curDur > 0) {
                    val p = (st.time.toLong() * 100 / curDur).toInt().coerceIn(0, 100)
                    h.post { bar.progress = p }
                }
            })
    }

    private fun to3gp() {
        bar.progress = 0
        status.text = "ভিডিও ${idx + 1}/${queue.size} • ধাপ ২/২ (3GP বানানো হচ্ছে)"
        val z = sizes[resSp.selectedItemPosition]
        val r = rates[qSp.selectedItemPosition]
        val out = File(cacheDir, "o.3gp"); out.delete()
        val v = "-y -i ${tmp!!.absolutePath} -vf scale=${z[0]}:${z[1]}:flags=lanczos,unsharp=3:3:0.7 " +
                "-c:v h263 -pix_fmt yuv420p -r 15 -b:v ${r}k"
        val amr = "$v -c:a libopencore_amrnb -ar 8000 -ac 1 -b:a 12.2k ${out.absolutePath}"
        val aac = "$v -c:a aac -ar 16000 -ac 1 -b:a 24k ${out.absolutePath}"
        run(amr) { ok1 ->
            if (ok1) {
                h.post { save(out); next() }
            } else {
                run(aac) { ok2 ->
                    h.post {
                        if (ok2) save(out) else status.text = "3GP ভুল, পরেরটা দেখছি"
                        next()
                    }
                }
            }
        }
    }

    private fun save(f: File) {
        val sp = getSharedPreferences("m", 0)
        val n = sp.getInt("n", 0) + 1
        sp.edit().putInt("n", n).apply()
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "video$n.3gp")
            put(MediaStore.Video.Media.MIME_TYPE, "video/3gpp")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MomVideos")
        }
        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        f.delete(); tmp?.delete()
    }
}
