package com.capturecam.recorder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var btnRecord: ImageButton
    private lateinit var btnPause: Button
    private lateinit var tvTimer: TextView
    private lateinit var tvState: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvSavedIn: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnAll: View
    private lateinit var recentGrid: LinearLayout
    private lateinit var settingsViews: List<View>

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            if (RecorderState.phase == Phase.RECORDING) handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        instance = WeakReference(this)

        // Con targetSdk 35+ la app va de borde a borde: hay que respetar las barras del sistema.
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        btnRecord = findViewById(R.id.btnRecord)
        btnPause = findViewById(R.id.btnPause)
        tvTimer = findViewById(R.id.tvTimer)
        tvState = findViewById(R.id.tvState)
        tvSummary = findViewById(R.id.tvSummary)
        tvSavedIn = findViewById(R.id.tvSavedIn)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnAll = findViewById(R.id.btnAll)
        recentGrid = findViewById(R.id.recentGrid)

        btnRecord.setOnClickListener {
            if (RecorderState.phase == Phase.IDLE) {
                startRecordingFlow()
            } else {
                RecorderService.send(this, RecorderService.ACTION_STOP)
            }
        }
        btnPause.setOnClickListener {
            RecorderService.send(this, RecorderService.ACTION_TOGGLE_PAUSE)
        }
        btnAll.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        findViewById<View>(R.id.tvAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        val spResolution = bindSpinner(R.id.spResolution, R.array.resolution_labels,
            settings.resolution) { settings.resolution = it }
        val spFps = bindSpinner(R.id.spFps, R.array.fps_labels,
            settings.fps) { settings.fps = it }
        val spQuality = bindSpinner(R.id.spQuality, R.array.quality_labels,
            settings.quality) { settings.quality = it }
        val spOrientation = bindSpinner(R.id.spOrientation, R.array.orientation_labels,
            settings.orientation) { settings.orientation = it }
        val spStorage = bindSpinner(R.id.spStorage, R.array.storage_labels,
            settings.storage) { settings.storage = it }

        val swMic = findViewById<Switch>(R.id.swMic).apply {
            isChecked = settings.mic
            setOnCheckedChangeListener { _, checked -> settings.mic = checked }
        }
        val swAppAudio = findViewById<Switch>(R.id.swAppAudio).apply {
            isChecked = settings.appAudio
            setOnCheckedChangeListener { _, checked -> settings.appAudio = checked }
        }
        val swCountdown = findViewById<Switch>(R.id.swCountdown).apply {
            isChecked = settings.countdown
            setOnCheckedChangeListener { _, checked ->
                settings.countdown = checked
                if (checked && !canDrawOverlay()) askOverlayPermission(onSkip = null)
            }
        }
        settingsViews = listOf(spResolution, spFps, spQuality, spOrientation, spStorage, swAppAudio, swMic, swCountdown)
    }

    override fun onStart() {
        super.onStart()
        RecorderState.listener = {
            render()
            handler.removeCallbacks(ticker)
            if (RecorderState.phase == Phase.RECORDING) handler.post(ticker)
            // Al terminar una grabación aparece en Recientes.
            if (RecorderState.phase == Phase.IDLE) renderRecents()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        renderRecents()
        handler.removeCallbacks(ticker)
        if (RecorderState.phase == Phase.RECORDING) handler.post(ticker)
    }

    override fun onStop() {
        RecorderState.listener = null
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    private fun canDrawOverlay() = android.provider.Settings.canDrawOverlays(this)

    /** Empieza el flujo de grabación; la primera vez, antes explica el permiso de la cuenta atrás. */
    private fun startRecordingFlow() {
        if (settings.countdown && !canDrawOverlay() && !settings.overlayPrompted) {
            settings.overlayPrompted = true
            askOverlayPermission(onSkip = { CaptureActivity.launch(this) })
        } else {
            CaptureActivity.launch(this)
        }
    }

    private fun askOverlayPermission(onSkip: (() -> Unit)?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setMessage(R.string.overlay_message)
            .setPositiveButton(R.string.overlay_grant) { _, _ ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton(R.string.overlay_skip) { _, _ -> onSkip?.invoke() }
            .setOnCancelListener { onSkip?.invoke() }
            .show()
    }

    private fun bindSpinner(
        id: Int, labels: Int, selected: Int, onSelected: (Int) -> Unit
    ): Spinner {
        val spinner = findViewById<Spinner>(id)
        spinner.adapter = ArrayAdapter.createFromResource(
            this, labels, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinner.setSelection(selected, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, rowId: Long) {
                onSelected(pos)
                renderSummary()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        return spinner
    }

    /** Pinta el estado actual de la grabación. */
    private fun render() {
        val phase = RecorderState.phase
        val idle = phase == Phase.IDLE

        btnRecord.setImageResource(if (idle) R.drawable.ic_record else R.drawable.ic_stop)
        btnRecord.contentDescription = getString(if (idle) R.string.btn_record else R.string.btn_stop)

        tvTimer.text = RecorderService.formatTime(RecorderState.elapsedMs())
        tvState.text = when (phase) {
            Phase.IDLE -> getString(R.string.state_idle)
            Phase.COUNTDOWN -> RecorderState.countdownLeft.let {
                if (it > 0) getString(R.string.notif_countdown_title, it) else getString(R.string.state_countdown)
            }
            Phase.RECORDING -> getString(R.string.state_recording)
            Phase.PAUSED -> getString(R.string.state_paused)
        }

        val canPause = phase == Phase.RECORDING || phase == Phase.PAUSED
        btnPause.visibility = if (canPause) View.VISIBLE else View.GONE
        btnPause.setText(if (phase == Phase.PAUSED) R.string.btn_resume else R.string.btn_pause)

        settingsViews.forEach { it.isEnabled = idle }
        renderSummary()
    }

    /** Muestra la resolución real con la que se grabará y la carpeta de destino. */
    private fun renderSummary() {
        val video = settings.videoParams(this)
        tvSummary.text = getString(R.string.summary_resolution, video.width, video.height)
        tvSavedIn.setText(
            if (settings.storage == 1) R.string.saved_in_downloads else R.string.saved_in_movies
        )
    }

    /** Hasta 6 grabaciones recientes en filas de 3. */
    private fun renderRecents() {
        val recordings = Recordings.list(this)
        recentGrid.removeAllViews()
        tvEmpty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
        btnAll.visibility = if (recordings.isEmpty()) View.GONE else View.VISIBLE

        val gap = (6 * resources.displayMetrics.density).toInt()
        recordings.take(RECENT_COUNT).chunked(COLUMNS).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until COLUMNS) {
                val cell = rowItems.getOrNull(col)?.let { recording ->
                    RecordingCell.bind(this, row, null, recording).apply {
                        setOnClickListener { startActivity(PlayerActivity.intent(this@MainActivity, recording.uri)) }
                    }
                } ?: View(this) // hueco para que la última fila mantenga el ancho de las celdas
                row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (col > 0) marginStart = gap })
            }
            recentGrid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (rowIndex > 0) topMargin = gap })
        }
    }

    companion object {
        private const val COLUMNS = 3
        private const val RECENT_COUNT = 6

        private var instance: WeakReference<MainActivity>? = null

        /**
         * Aparta la ventana de CaptureCam para que no salga en la grabación. Si la app a grabar ya
         * está delante (una sola app elegida en el diálogo), mover nuestra tarea al fondo no cambia
         * nada de lo que se ve; si CaptureCam estaba delante, deja ver lo que había debajo.
         */
        fun sendToBack() {
            instance?.get()?.moveTaskToBack(true)
        }
    }
}
