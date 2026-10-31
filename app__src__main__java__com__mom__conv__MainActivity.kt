package com.mom.conv

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private var idx = 0
    private var transformer: Transformer? = null
    private var tmp: File? = null
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var convBtn: Button

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
        convBtn = Button(this).apply { text = "মায়ের ফোনের জন্য কনভার্ট"; isEnabled = false }
        status = TextView(this).apply { text = "প্রথমে ভিডিও বাছুন"; textSize = 18f; setPadding(0, 48, 0, 24) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(pick); root.addView(convBtn); root.addView(status); root.addView(bar)
        setContentView(root)

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
        queue.clear()
        d.clipData?.let { c -> for (i in 0 until c.itemCount) queue.add(c.getItemAt(i).uri) }
            ?: d.data?.let { queue.add(it) }
        status.text = "${queue.size}টি ভিডিও বাছা হয়েছে"
        convBtn.isEnabled = queue.isNotEmpty()
    }

    private fun startNext() {
        if (idx >= queue.size) {
            h.removeCallbacks(poll)
            status.text = "শেষ! Movies/MomVideos ফোল্ডার দেখুন"
            bar.progress = 100
            convBtn.isEnabled = true
            return
        }
        status.text = "কনভার্ট হচ্ছে ${idx + 1}/${queue.size}"
        bar.progress = 0
        val out = File(cacheDir, "o.mp4"); out.delete(); tmp = out
        val fx = Effects(
            emptyList(),
            listOf(
                FrameDropEffect.createDefaultFrameDropEffect(15f),
                Presentation.createForWidthAndHeight(176, 144, Presentation.LAYOUT_SCALE_TO_FIT)
            )
        )
        val item = EditedMediaItem.Builder(MediaItem.fromUri(queue[idx])).setEffects(fx).build()
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(this)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(300_000).build())
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    to3gp()
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    status.text = "ভুল: ${exportException.message}"; idx++; startNext()
                }
            }).build()
        transformer = t
        t.start(item, out.absolutePath)
    }

    private fun next() { idx++; startNext() }

    private fun to3gp() {
        bar.progress = 100
        status.text = "3GP বানানো হচ্ছে ${idx + 1}/${queue.size}"
        val out = File(cacheDir, "o.3gp"); out.delete()
        val v = "-y -i ${tmp!!.absolutePath} -c:v h263 -pix_fmt yuv420p -s 176x144 -r 15 -b:v 100k"
        val amr = "$v -c:a libopencore_amrnb -ar 8000 -ac 1 -b:a 12.2k ${out.absolutePath}"
        val aac = "$v -c:a aac -ar 16000 -ac 1 -b:a 24k ${out.absolutePath}"
        FFmpegKit.executeAsync(amr) { s1 ->
            if (ReturnCode.isSuccess(s1.returnCode)) {
                h.post { save(out); next() }
            } else {
                FFmpegKit.executeAsync(aac) { s2 ->
                    h.post {
                        if (ReturnCode.isSuccess(s2.returnCode)) save(out) else status.text = "3GP ভুল, পরেরটা দেখছি"
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
