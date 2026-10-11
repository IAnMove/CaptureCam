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

/** Vista previa de una grabación con acciones para compartirla, abrirla en otra app o borrarla. */
class PlayerActivity : Activity() {

    private lateinit var video: VideoView
    private var uri: Uri? = null

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

        findViewById<TextView>(R.id.tvTitle).text = Recordings.find(this, uri)?.dateLabel ?: ""
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShare).setOnClickListener { Recordings.share(this, uri) }
        findViewById<View>(R.id.btnOpenWith).setOnClickListener { Recordings.openWith(this, uri) }
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
