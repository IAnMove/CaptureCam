package com.capturecam.recorder

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.TextView

/** Todas las grabaciones en una cuadrícula de 3 columnas. */
class HistoryActivity : Activity() {

    private val items = mutableListOf<Recording>()
    private lateinit var empty: TextView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].uri.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            RecordingCell.bind(this@HistoryActivity, parent, convertView, items[position])
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        empty = findViewById(R.id.tvEmpty)

        findViewById<GridView>(R.id.grid).apply {
            adapter = this@HistoryActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                startActivity(PlayerActivity.intent(this@HistoryActivity, items[position].uri))
            }
            setOnItemLongClickListener { _, _, position, _ ->
                showActions(items[position])
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items.clear()
        items += Recordings.list(this)
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Pulsación larga: compartir o eliminar sin tener que abrir la grabación. */
    private fun showActions(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle(recording.dateLabel)
            .setItems(arrayOf(getString(R.string.btn_share), getString(R.string.btn_delete))) { _, which ->
                when (which) {
                    0 -> Recordings.share(this, recording.uri)
                    1 -> Recordings.confirmDelete(this, recording.uri) { reload() }
                }
            }
            .show()
    }
}
