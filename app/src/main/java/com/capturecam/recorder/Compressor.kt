package com.capturecam.recorder

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Crea una copia más pequeña de una grabación. El original no se toca: lo borra el usuario si quiere.
 * Solo se comprime un vídeo a la vez. El trabajo sigue aunque la pantalla se cierre; si al terminar no
 * hay ninguna pantalla de la app delante, avisa con una notificación.
 * Todo el estado se lee y se escribe desde el hilo principal.
 */
object Compressor {
    enum class Outcome { DONE, NO_GAIN, CANCELLED, FAILED }

    class Result(
        val outcome: Outcome,
        val inBytes: Long = 0,
        val outBytes: Long = 0,
        val outUri: Uri? = null,
    )

    var running = false
        private set
    var progress = 0
        private set

    /** Cierto desde que el usuario cancela hasta que el trabajo termina de recoger. */
    var cancelling = false
        private set

    /** Lo registra la pantalla visible para pintar el progreso y el resultado. */
    var listener: (() -> Unit)? = null

    private var pending: Result? = null
    @Volatile private var cancelRequested = false
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context, recording: Recording) {
        if (running) return
        running = true
        cancelling = false
        cancelRequested = false
        progress = 0
        pending = null
        val app = context.applicationContext
        Thread({
            val result = try {
                work(app, recording)
            } catch (e: Throwable) {
                Log.e(TAG, "La compresión falló", e)
                Result(Outcome.FAILED)
            }
            main.post { finished(app, result) }
        }, "cc-compress").start()
        listener?.invoke()
    }

    fun cancel() {
        cancelRequested = true
        cancelling = true
    }

    /** El resultado que aún no se ha enseñado, una sola vez. */
    fun takePending(): Result? = pending.also { pending = null }

    private fun finished(app: Context, result: Result) {
        running = false
        cancelling = false
        val ui = listener
        if (ui != null) {
            pending = result
            ui.invoke()
        } else {
            notify(app, result)
        }
    }

    private fun work(app: Context, rec: Recording): Result {
        val resolver = app.contentResolver
        val relativePath = relativePathOf(app, rec.uri)
            ?: (Environment.DIRECTORY_MOVIES + "/CaptureCam/")
        val toDownloads = relativePath.startsWith(Environment.DIRECTORY_DOWNLOADS)
        val collection = if (toDownloads) MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val base = rec.name.removeSuffix(".mp4").ifBlank {
            "CaptureCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, base + "_compressed.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val outUri = resolver.insert(collection, values) ?: return Result(Outcome.FAILED)

        var pfd: ParcelFileDescriptor? = null
        var kept = false
        try {
            pfd = resolver.openFileDescriptor(outUri, "rw") ?: return Result(Outcome.FAILED)
            VideoCompressor(
                app, rec.uri, rec.sizeBytes, pfd.fileDescriptor,
                { percent -> main.post { if (running) { progress = percent; listener?.invoke() } } },
                { cancelRequested }
            ).run()
            val outBytes = pfd.statSize
            // Una copia con la duración equivocada sería peor que no tenerla.
            val outMs = durationOf(pfd)
            pfd.close()
            pfd = null
            if (rec.durationMs > 0 && abs(outMs - rec.durationMs) > max(1500L, rec.durationMs / 20)) {
                Log.e(TAG, "La copia dura $outMs ms y el original ${rec.durationMs} ms: se descarta")
                return Result(Outcome.FAILED)
            }

            // Si no gana espacio, la copia solo estorba.
            if (rec.sizeBytes > 0 && outBytes >= rec.sizeBytes * NO_GAIN_RATIO) {
                return Result(Outcome.NO_GAIN, rec.sizeBytes, outBytes)
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(outUri, done, null, null)
            kept = true
            return Result(Outcome.DONE, rec.sizeBytes, outBytes, outUri)
        } catch (e: VideoCompressor.CancelledException) {
            return Result(Outcome.CANCELLED)
        } finally {
            runCatching { pfd?.close() }
            if (!kept) runCatching { resolver.delete(outUri, null, null) }
        }
    }

    private fun durationOf(pfd: ParcelFileDescriptor): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(pfd.fileDescriptor)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun relativePathOf(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------- textos

    fun doneMessage(context: Context, r: Result): String = context.getString(
        R.string.compress_done_message,
        Formatter.formatShortFileSize(context, r.inBytes),
        Formatter.formatShortFileSize(context, r.outBytes)
    )

    private fun notify(app: Context, r: Result) {
        if (r.outcome == Outcome.CANCELLED) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SAVED, app.getString(R.string.channel_saved), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val b = Notification.Builder(app, CH_SAVED)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setColor(app.getColor(R.color.accent))
            .setAutoCancel(true)
        when (r.outcome) {
            Outcome.DONE -> {
                b.setContentTitle(app.getString(R.string.compress_done_title))
                b.setContentText(doneMessage(app, r))
                r.outUri?.let {
                    b.setContentIntent(PendingIntent.getActivity(
                        app, 4, PlayerActivity.intent(app, it),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    ))
                }
            }
            Outcome.NO_GAIN -> b.setContentTitle(app.getString(R.string.compress_no_gain))
            else -> b.setContentTitle(app.getString(R.string.compress_failed))
        }
        nm.notify(NOTIF_ID, b.build())
    }

    private const val TAG = "CaptureCam"
    private const val CH_SAVED = "saved"
    private const val NOTIF_ID = 3

    /** Una copia que ocupa más de este tanto del original no merece la pena. */
    private const val NO_GAIN_RATIO = 0.9
}

/**
 * Lo que una pantalla necesita para enseñar la compresión: un cuadro con el avance y, al terminar, el
 * resultado. Hay que llamar a [attach] en onStart y a [detach] en onStop.
 * [onChanged] se llama cuando aparece una copia nueva, para que la pantalla recargue su lista.
 */
class CompressUi(private val activity: Activity, private val onChanged: () -> Unit) {
    private var dialog: AlertDialog? = null
    private var message: TextView? = null
    private var bar: ProgressBar? = null
    private val listener: () -> Unit = { update() }

    fun attach() {
        Compressor.listener = listener
        update()
    }

    fun detach() {
        // La pantalla siguiente se registra antes de que esta termine de pararse: no pisar su registro.
        if (Compressor.listener === listener) Compressor.listener = null
        dismiss()
    }

    private fun update() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (Compressor.running && !Compressor.cancelling) {
            if (dialog == null) show()
            message?.text = activity.getString(R.string.compress_progress, Compressor.progress)
            bar?.progress = Compressor.progress
        } else {
            dismiss()
        }
        val result = Compressor.takePending() ?: return
        showResult(result)
        if (result.outcome == Compressor.Outcome.DONE) onChanged()
    }

    private fun show() {
        val density = activity.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val text = TextView(activity).apply {
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(text, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(progressBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 })
        }
        message = text
        bar = progressBar
        // Mientras trabaja, la pantalla no debe apagarse.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> Compressor.cancel() }
            .show()
    }

    private fun dismiss() {
        dialog?.let { runCatching { it.dismiss() } }
        dialog = null
        message = null
        bar = null
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showResult(r: Compressor.Result) {
        when (r.outcome) {
            Compressor.Outcome.DONE -> AlertDialog.Builder(activity)
                .setTitle(R.string.compress_done_title)
                .setMessage(Compressor.doneMessage(activity, r))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            Compressor.Outcome.NO_GAIN -> AlertDialog.Builder(activity)
                .setMessage(R.string.compress_no_gain)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            Compressor.Outcome.CANCELLED -> toast(R.string.compress_cancelled)
            Compressor.Outcome.FAILED -> toast(R.string.compress_failed)
        }
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_LONG).show()
}
