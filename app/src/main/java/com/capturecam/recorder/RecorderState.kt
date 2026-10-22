package com.capturecam.recorder

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

enum class Phase { IDLE, COUNTDOWN, RECORDING, PAUSED }

/**
 * Estado de la grabación compartido entre el servicio y la interfaz.
 * Todo vive en el mismo proceso, así que basta un objeto con un único oyente.
 * Solo se modifica desde el hilo principal.
 */
object RecorderState {
    var phase = Phase.IDLE
        private set
    var countdownLeft = 0
        private set

    /** Lo registra la actividad principal mientras es visible. */
    var listener: (() -> Unit)? = null

    /** La última grabación terminada; las listas la enseñan aunque MediaStore aún no la devuelva. */
    var lastSaved: Uri? = null

    private var accumulatedMs = 0L
    private var segmentStart = 0L
    private val main = Handler(Looper.getMainLooper())

    fun elapsedMs(): Long =
        accumulatedMs + if (phase == Phase.RECORDING) SystemClock.elapsedRealtime() - segmentStart else 0L

    fun countdown(secondsLeft: Int) {
        phase = Phase.COUNTDOWN
        countdownLeft = secondsLeft
        changed()
    }

    fun recording() {
        accumulatedMs = 0L
        segmentStart = SystemClock.elapsedRealtime()
        phase = Phase.RECORDING
        changed()
    }

    fun paused() {
        accumulatedMs = elapsedMs()
        phase = Phase.PAUSED
        changed()
    }

    fun resumed() {
        segmentStart = SystemClock.elapsedRealtime()
        phase = Phase.RECORDING
        changed()
    }

    fun idle() {
        phase = Phase.IDLE
        accumulatedMs = 0L
        countdownLeft = 0
        changed()
    }

    private fun changed() {
        main.post { listener?.invoke() }
    }
}
