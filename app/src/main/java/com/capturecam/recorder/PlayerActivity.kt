package com.capturecam.recorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView

/** Vista previa de una grabación con acciones para compartirla, abrirla en otra app, comprimirla o borrarla. */
class PlayerActivity : Activity() {

    private lateinit var video: VideoView
    private var uri: Uri? = null
    private val compressUi = CompressUi(this) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        this.uri = uri
        setContentView(R.layout.activity_player)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        val recording = Recordings.find(this, uri)
        findViewById<TextView>(R.id.tvTitle).text = recording?.dateLabel ?: ""
        findViewById<TextView>(R.id.tvMeta).text = recording?.infoLabel(this) ?: ""
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShare).setOnClickListener { Recordings.share(this, uri) }
        findViewById<View>(R.id.btnOpenWith).setOnClickListener { Recordings.openWith(this, uri) }
        findViewById<View>(R.id.btnCompress).setOnClickListener {
            // Se vuelve a leer: el tamaño puede no estar completo si se abrió nada más grabar.
            val current = Recordings.find(this, uri) ?: return@setOnClickListener
            Recordings.confirmCompress(this, current) { video.pause() }
        }
        findViewById<View>(R.id.btnDelete).setOnClickListener {
            Recordings.confirmDelete(this, uri) { finish() }
        }

        video = findViewById(R.id.video)
        video.setMediaController(MediaController(this).also { it.setAnchorView(video) })
        video.setOnPreparedListener { video.start() }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.toast_play_failed, Toast.LENGTH_SHORT).show()
            true
        }
        video.setVideoURI(uri)
    }

    override fun onStart() {
        super.onStart()
        compressUi.attach()
    }

    override fun onStop() {
        compressUi.detach()
        super.onStop()
    }

    override fun onPause() {
        if (::video.isInitialized) video.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, PlayerActivity::class.java).setData(uri)
    }
}
