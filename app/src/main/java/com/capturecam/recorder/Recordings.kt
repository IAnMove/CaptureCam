package com.capturecam.recorder

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class Recording(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
) {
    val dateLabel: String
        get() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(dateAddedSec * 1000))

    /** Tamaño como lo escribe el sistema (por ejemplo «28 MB»). */
    fun sizeLabel(context: Context): String = Formatter.formatShortFileSize(context, sizeBytes)

    /** «00:28 · 28 MB» */
    fun infoLabel(context: Context): String =
        RecorderService.formatTime(durationMs) + " · " + sizeLabel(context)
}

/** Acceso al histórico: las grabaciones que la app ha guardado en Películas o Descargas. */
object Recordings {
    private val FOLDERS = arrayOf("Movies/CaptureCam/%", "Download/CaptureCam/%")

    /** Más recientes primero. Solo ve los archivos de esta app, así que no pide permisos. */
    fun list(context: Context): List<Recording> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val result = mutableListOf<Recording>()
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                FOLDERS,
                "${MediaStore.Video.Media.DATE_ADDED} DESC, ${MediaStore.Video.Media._ID} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    result += complete(
                        context, uri, c.getString(1) ?: "", c.getLong(2), c.getLong(3), c.getLong(4)
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("CaptureCam", "No se pudo leer el histórico", e)
        }

        // Recién guardada, MediaStore puede tardar un momento en listarla como vídeo: se añade a mano
        // para que aparezca al parar. En cuanto la lista la trae sola, ya no hace falta.
        RecorderState.lastSaved?.let { saved ->
            val id = ContentUris.parseId(saved)
            if (result.any { ContentUris.parseId(it.uri) == id }) {
                RecorderState.lastSaved = null
            } else {
                queryOne(context, saved)?.let { result.add(0, it) }
            }
        }
        return result
    }

    /** Compara por id: la URI de Descargas y la de Vídeo apuntan al mismo archivo con rutas distintas. */
    fun find(context: Context, uri: Uri): Recording? {
        val id = ContentUris.parseId(uri)
        return list(context).firstOrNull { ContentUris.parseId(it.uri) == id } ?: queryOne(context, uri)
    }

    /** Una grabación leída directamente por su URI, sin pasar por el listado. */
    private fun queryOne(context: Context, uri: Uri): Recording? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED
                ),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                complete(context, uri, c.getString(0) ?: "", c.getLong(1), c.getLong(2), c.getLong(3))
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Rellena la duración y el tamaño si MediaStore aún no los tiene (acaba de terminar la grabación y
     * no ha acabado de analizarla): se leen del propio archivo.
     */
    private fun complete(
        context: Context, uri: Uri, name: String, durationMs: Long, sizeBytes: Long, dateAddedSec: Long
    ): Recording {
        var duration = durationMs
        var size = sizeBytes
        if (size <= 0) {
            size = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
        if (duration <= 0) {
            val retriever = MediaMetadataRetriever()
            duration = try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                0L
            } finally {
                runCatching { retriever.release() }
            }
        }
        val date = if (dateAddedSec > 0) dateAddedSec else System.currentTimeMillis() / 1000
        return Recording(uri, name, duration, size, date)
    }

    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)))
    }

    fun openWith(context: Context, uri: Uri) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_player, Toast.LENGTH_SHORT).show()
        }
    }

    /** Pulsación larga sobre una grabación: compartir, comprimir o eliminar sin abrirla. */
    fun showActions(activity: Activity, recording: Recording, onDeleted: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(recording.dateLabel)
            .setItems(
                arrayOf(
                    activity.getString(R.string.btn_share),
                    activity.getString(R.string.btn_compress),
                    activity.getString(R.string.btn_delete),
                )
            ) { _, which ->
                when (which) {
                    0 -> share(activity, recording.uri)
                    1 -> confirmCompress(activity, recording, null)
                    2 -> confirmDelete(activity, recording.uri, onDeleted)
                }
            }
            .show()
    }

    /** Explica qué va a pasar y, si el usuario acepta, empieza a comprimir una copia. */
    fun confirmCompress(activity: Activity, recording: Recording, onStarted: (() -> Unit)?) {
        if (Compressor.running) {
            // Ya hay una en marcha: se vuelve a enseñar su avance.
            Compressor.listener?.invoke()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.compress_title)
            .setMessage(activity.getString(R.string.compress_message, recording.sizeLabel(activity)))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_compress) { _, _ ->
                onStarted?.invoke()
                Compressor.start(activity, recording)
            }
            .show()
    }

    /** Pide confirmación y borra; llama a [onDeleted] solo si se eliminó. */
    fun confirmDelete(activity: Activity, uri: Uri, onDeleted: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_message)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                val deleted = try {
                    activity.contentResolver.delete(uri, null, null) > 0
                } catch (e: Exception) {
                    false
                }
                Thumbs.forget(uri)
                Toast.makeText(
                    activity,
                    if (deleted) R.string.toast_deleted else R.string.toast_delete_failed,
                    Toast.LENGTH_SHORT
                ).show()
                if (deleted) onDeleted()
            }
            .show()
    }
}

/** Miniaturas de los vídeos, cargadas fuera del hilo principal y con caché en memoria. */
object Thumbs {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Uri, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }

    fun load(context: Context, uri: Uri, into: ImageView) {
        into.tag = uri
        val cached = cache.get(uri)
        if (cached != null) {
            into.setImageBitmap(cached)
            return
        }
        into.setImageDrawable(null)
        val resolver = context.applicationContext.contentResolver
        pool.execute {
            val bitmap = earlyFrame(context.applicationContext, uri)
                ?: try {
                    resolver.loadThumbnail(uri, Size(MAX_W, MAX_H), null)
                } catch (e: Exception) {
                    null
                }
            if (bitmap != null) cache.put(uri, bitmap)
            // El ImageView puede haberse reciclado para otra grabación mientras tanto.
            main.post { if (bitmap != null && into.tag == uri) into.setImageBitmap(bitmap) }
        }
    }

    fun forget(uri: Uri) {
        cache.remove(uri)
    }

    /**
     * Fotograma a un cuarto de la duración. El generador de miniaturas del sistema suele elegir uno
     * tardío, y al final de casi toda grabación se ve la pantalla con la que el usuario la detiene
     * (esta app o el panel de notificaciones), no lo que quería enseñar.
     */
    private fun earlyFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun meta(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            if (width <= 0 || height <= 0) return null
            val scale = minOf(MAX_W.toFloat() / width, MAX_H.toFloat() / height, 1f)
            retriever.getScaledFrameAtTime(
                durationMs / 4 * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1)
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private const val MAX_W = 360
    private const val MAX_H = 780
}
