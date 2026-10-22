package com.capturecam.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servicio en primer plano que hace toda la grabación:
 * MediaProjection -> VirtualDisplay -> ScreenEncoder (H.264 + AAC) -> MP4 en MediaStore.
 */
class RecorderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var encoder: ScreenEncoder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputUri: Uri? = null
    private var outputFd: ParcelFileDescriptor? = null
    private var overlay: CountdownOverlay? = null

    /** Marca de los mensajes de la cuenta atrás, para poder cancelarlos sin tocar otros del handler. */
    private val countdownToken = Any()
    private val beginRunnable = Runnable { beginRecording() }
    private var micEnabled = false
    private var appAudioEnabled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopRecording()
            ACTION_TOGGLE_PAUSE -> togglePause()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Si el sistema nos mata a mitad de grabación, guarda lo que haya.
        if (RecorderState.phase != Phase.IDLE) finishRecording()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- inicio

    private fun handleStart(intent: Intent) {
        if (RecorderState.phase != Phase.IDLE) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return
        }

        // El sonido de las apps también exige RECORD_AUDIO, aunque no se use el micrófono.
        val audioGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micEnabled = settings.mic && audioGranted
        appAudioEnabled = settings.appAudio && audioGranted

        createChannels()

        // Android 14+: startForeground con el tipo mediaProjection va ANTES de getMediaProjection.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (micEnabled) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        RecorderState.countdown(if (settings.countdown) COUNTDOWN_SECONDS else 0)
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground falló", e)
            fail()
            return
        }

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val p = manager.getMediaProjection(resultCode, data)
                ?: error("getMediaProjection devolvió null")
            // Android 14+: hay que registrar el callback antes de crear el VirtualDisplay.
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    // El usuario paró la captura desde el sistema (chip de la barra de estado, etc.).
                    projectionCallback = null
                    handler.post { stopRecording() }
                }
            }
            p.registerCallback(cb, handler)
            projection = p
            projectionCallback = cb
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection falló", e)
            fail()
            return
        }

        if (settings.countdown) {
            startCountdown()
        } else {
            beginRecording()
        }
    }

    /**
     * 3, 2, 1 con mensajes programados en vez de CountDownTimer: este se salta el último tick
     * si llega con unos milisegundos de retraso, y entonces el "1" no llegaría a verse.
     */
    private fun startCountdown() {
        RecorderState.countdown(COUNTDOWN_SECONDS)
        // Sin el permiso "Aparecer encima" la cuenta atrás solo se ve en la notificación.
        if (android.provider.Settings.canDrawOverlays(this)) overlay = CountdownOverlay(this)

        for (step in 0 until COUNTDOWN_SECONDS) {
            val left = COUNTDOWN_SECONDS - step
            handler.postDelayed({
                RecorderState.countdown(left)
                overlay?.show(left)
                updateNotification()
            }, countdownToken, step * 1000L)
        }
        handler.postDelayed({
            // La cuenta atrás se retira antes de grabar para que no salga en el vídeo;
            // la pausa deja que el sistema deje de componerla.
            overlay?.hide()
            overlay = null
            handler.postDelayed(beginRunnable, countdownToken, OVERLAY_GONE_DELAY_MS)
        }, countdownToken, COUNTDOWN_SECONDS * 1000L)
    }

    private fun beginRecording() {
        val p = projection ?: return fail()
        try {
            val video = settings.videoParams(this)
            Log.i(TAG, "Grabando ${video.width}x${video.height} @${video.fps}fps " +
                "${video.bitrate / 1000}kbps mic=$micEnabled apps=$appAudioEnabled")

            val name = "CaptureCam_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val toDownloads = settings.storage == 1
            val collection = if (toDownloads) MediaStore.Downloads.EXTERNAL_CONTENT_URI
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val folder = (if (toDownloads) Environment.DIRECTORY_DOWNLOADS
                else Environment.DIRECTORY_MOVIES) + "/CaptureCam"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collection, values)
                ?: error("MediaStore no creó el archivo")
            outputUri = uri
            // Modo "rw": el escritor MP4 necesita poder leer y hacer seek.
            val fd = contentResolver.openFileDescriptor(uri, "rw") ?: error("No se pudo abrir $uri")
            outputFd = fd

            val audio = if (micEnabled || appAudioEnabled) {
                AudioCapture.create(p, appAudioEnabled, micEnabled)
            } else null
            val enc = try {
                ScreenEncoder(fd.fileDescriptor, video, audio) { handler.post { stopRecording() } }
            } catch (ex: Exception) {
                audio?.stop()
                throw ex
            }
            encoder = enc
            enc.start()

            virtualDisplay = p.createVirtualDisplay(
                "CaptureCam",
                video.width, video.height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                enc.surface, null, null
            )

            RecorderState.recording()
            updateNotification()
            refreshTile()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo empezar a grabar", e)
            fail()
        }
    }

    // ------------------------------------------------------- pausa y final

    private fun togglePause() {
        val e = encoder ?: return
        when (RecorderState.phase) {
            Phase.RECORDING -> {
                e.pause()
                RecorderState.paused()
            }
            Phase.PAUSED -> {
                e.resume()
                RecorderState.resumed()
            }
            else -> return
        }
        updateNotification()
    }

    private fun stopRecording() {
        if (RecorderState.phase == Phase.IDLE) {
            stopSelf()
            return
        }
        finishRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Libera todo y deja el archivo finalizado en MediaStore (o lo borra si quedó vacío). */
    private fun finishRecording() {
        handler.removeCallbacksAndMessages(countdownToken)
        overlay?.hide()
        overlay = null

        val hadRecording = RecorderState.phase == Phase.RECORDING || RecorderState.phase == Phase.PAUSED
        // El codificador se cierra siempre (también si falló al arrancar); solo cuenta si hubo grabación.
        val saved = encoder?.stop() == true && hadRecording
        encoder = null

        virtualDisplay?.release()
        virtualDisplay = null

        // Se desregistra antes de parar para no reentrar en onStop().
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projectionCallback = null
        projection?.stop()
        projection = null

        outputFd?.let { runCatching { it.close() } }
        outputFd = null

        val uri = outputUri
        outputUri = null
        if (uri != null) {
            if (saved) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(uri, done, null, null)
                RecorderState.lastSaved = uri
                notifySaved(uri)
            } else {
                runCatching { contentResolver.delete(uri, null, null) }
                if (hadRecording) toast(R.string.notif_nothing_captured)
            }
        }

        RecorderState.idle()
        refreshTile()
    }

    private fun fail() {
        toast(R.string.toast_error)
        // finishRecording borra el archivo a medio crear si lo hubiera.
        finishRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------- notificaciones

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, getString(R.string.channel_recording),
                NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SAVED, getString(R.string.channel_saved),
                NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun action(label: Int, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_record),
            getString(label), serviceIntent(action, requestCode)
        ).build()

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setColor(getColor(R.color.accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        when (RecorderState.phase) {
            Phase.COUNTDOWN -> {
                val left = RecorderState.countdownLeft
                b.setContentTitle(
                    if (left > 0) getString(R.string.notif_countdown_title, left)
                    else getString(R.string.state_countdown)
                )
                b.addAction(action(R.string.btn_cancel, ACTION_STOP, 1))
            }
            Phase.RECORDING -> {
                b.setContentTitle(getString(R.string.notif_recording_title))
                b.setUsesChronometer(true)
                b.setShowWhen(true)
                b.setWhen(System.currentTimeMillis() - RecorderState.elapsedMs())
                b.addAction(action(R.string.btn_pause, ACTION_TOGGLE_PAUSE, 2))
                b.addAction(action(R.string.btn_stop, ACTION_STOP, 1))
            }
            Phase.PAUSED -> {
                b.setContentTitle(getString(R.string.notif_paused_title))
                b.setContentText(formatTime(RecorderState.elapsedMs()))
                b.setShowWhen(false)
                b.addAction(action(R.string.btn_resume, ACTION_TOGGLE_PAUSE, 2))
                b.addAction(action(R.string.btn_stop, ACTION_STOP, 1))
            }
            Phase.IDLE -> b.setContentTitle(getString(R.string.app_name))
        }
        return b.build()
    }

    private fun notifySaved(uri: Uri) {
        // Duración y tamaño del archivo ya terminado, para que se vea al momento que se ha guardado.
        val info = Recordings.find(this, uri)?.infoLabel(this)
        if (info != null) {
            handler.post {
                Toast.makeText(
                    applicationContext, getString(R.string.notif_saved_title) + ": " + info, Toast.LENGTH_LONG
                ).show()
            }
        }
        val pi = PendingIntent.getActivity(
            this, 3, PlayerActivity.intent(this, uri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_SAVED)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setColor(getColor(R.color.accent))
            .setContentTitle(getString(R.string.notif_saved_title))
            .setContentText(getString(R.string.notif_saved_text))
            .setSubText(info)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_SAVED_ID, n)
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, RecordingTileService::class.java))
    }

    private fun toast(res: Int) {
        handler.post { Toast.makeText(applicationContext, res, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "CaptureCam"

        const val ACTION_START = "com.capturecam.recorder.START"
        const val ACTION_STOP = "com.capturecam.recorder.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.capturecam.recorder.TOGGLE_PAUSE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        private const val CH_RECORDING = "recording"
        private const val CH_SAVED = "saved"
        private const val NOTIF_ID = 1
        private const val NOTIF_SAVED_ID = 2
        private const val COUNTDOWN_SECONDS = 3
        private const val OVERLAY_GONE_DELAY_MS = 300L

        /** Arranca el servicio con el resultado del diálogo de consentimiento de captura. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
            )
        }

        /** Envía una acción (parar, pausar) a un servicio que ya está en marcha. */
        fun send(context: Context, action: String) {
            context.startService(Intent(context, RecorderService::class.java).setAction(action))
        }

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) {
                String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
            } else {
                String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
            }
        }
    }
}
