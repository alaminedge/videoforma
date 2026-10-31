package com.mom.conv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
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
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

class ConvService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var progress = 0
        @Volatile var status = ""
    }

    private val h = Handler(Looper.getMainLooper())
    private var uris = listOf<Uri>()
    private var durs = LongArray(0)
    private var direct = BooleanArray(0)
    private var ow = 176
    private var oh = 144
    private var fps = 15
    private var rate = 250
    private var idx = 0
    private var curDur = 0L
    private var lastP = -1
    private var transformer: Transformer? = null
    private var tmp: File? = null
    private var wl: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(i: Intent?): IBinder? = null

    private val poll = object : Runnable {
        override fun run() {
            val ph = ProgressHolder()
            if (transformer?.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) progress = ph.progress
            if (progress != lastP) {
                lastP = progress
                nm.notify(1, notif(status, progress))
            }
            h.postDelayed(this, 700)
        }
    }

    private fun notif(text: String, p: Int): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "conv")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ভিডিও কনভার্ট হচ্ছে")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setProgress(100, p, false)
            .build()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (running || i == null) return START_NOT_STICKY
        running = true
        uris = (i.getStringArrayExtra("uris") ?: emptyArray()).map { Uri.parse(it) }
        durs = i.getLongArrayExtra("durs") ?: LongArray(0)
        direct = i.getBooleanArrayExtra("direct") ?: BooleanArray(0)
        ow = i.getIntExtra("w", 176)
        oh = i.getIntExtra("h", 144)
        fps = i.getIntExtra("fps", 15)
        rate = i.getIntExtra("rate", 250)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("conv", "Conversion", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notif("শুরু হচ্ছে...", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wl = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mom:conv").apply { acquire(6 * 60 * 60 * 1000L) }
        idx = 0
        h.post(poll)
        startNext()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startNext() {
        if (idx >= uris.size) { finish(); return }
        curDur = durs.getOrElse(idx) { 0L }
        progress = 0
        if (direct.getOrElse(idx) { false }) {
            status = "ভিডিও ${idx + 1}/${uris.size} • সরাসরি 3GP"
            val inp = FFmpegKitConfig.getSafParameterForRead(this, uris[idx])
            to3gp(inp) { stage1() }
        } else stage1()
    }

    private fun next() { idx++; startNext() }

    private fun stage1() {
        status = "ভিডিও ${idx + 1}/${uris.size} • ধাপ ১/২ (ছোট করা হচ্ছে)"
        progress = 0
        val out = File(cacheDir, "o.mp4"); out.delete(); tmp = out
        val fx = Effects(
            emptyList(),
            listOf(
                FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()),
                Presentation.createForWidthAndHeight(352, 288, Presentation.LAYOUT_SCALE_TO_FIT)
            )
        )
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uris[idx])).setEffects(fx).build()
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(this)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(1_000_000).build())
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    to3gp(out.absolutePath) { status = "ভিডিও ${idx + 1} এ ভুল হয়েছে"; next() }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    status = "ভুল: ${exportException.message}"; next()
                }
            }).build()
        transformer = t
        t.start(item, out.absolutePath)
    }

    private fun run(cmd: String, done: (Boolean) -> Unit) {
        FFmpegKit.executeAsync(cmd,
            { s -> done(ReturnCode.isSuccess(s.returnCode)) },
            null,
            { st ->
                if (curDur > 0) progress = (st.time.toLong() * 100 / curDur).toInt().coerceIn(0, 100)
            })
    }

    private fun tryRun(cmds: List<String>, i: Int, ok: () -> Unit, fail: () -> Unit) {
        if (i >= cmds.size) { h.post(fail); return }
        run(cmds[i]) { good -> if (good) h.post(ok) else tryRun(cmds, i + 1, ok, fail) }
    }

    private fun to3gp(input: String, onFail: () -> Unit) {
        transformer = null
        progress = 0
        if (!direct.getOrElse(idx) { false } || input.startsWith("/")) {
            status = "ভিডিও ${idx + 1}/${uris.size} • ধাপ ২/২ (3GP বানানো হচ্ছে)"
        }
        val out = File(cacheDir, "o.3gp"); out.delete()
        val vf = "scale=$ow:$oh:force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos," +
                "pad=$ow:$oh:(ow-iw)/2:(oh-ih)/2,unsharp=3:3:0.7"
        val cmds = ArrayList<String>()
        for (f in (if (fps == 15) listOf(15) else listOf(fps, 15))) {
            val v = "-y -i $input -vf $vf -c:v h263 -pix_fmt yuv420p -r $f -b:v ${rate}k"
            cmds.add("$v -c:a libopencore_amrnb -ar 8000 -ac 1 -b:a 12.2k ${out.absolutePath}")
            cmds.add("$v -c:a aac -ar 16000 -ac 1 -b:a 24k ${out.absolutePath}")
        }
        tryRun(cmds, 0, { save(out); next() }, onFail)
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

    private fun finish() {
        h.removeCallbacks(poll)
        progress = 100
        status = "শেষ! Movies/MomVideos ফোল্ডার দেখুন"
        wl?.let { if (it.isHeld) it.release() }
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.notify(2, Notification.Builder(this, "conv")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("কনভার্ট শেষ")
            .setContentText(status)
            .build())
        stopSelf()
    }
}
