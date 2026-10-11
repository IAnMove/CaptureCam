package com.capturecam.recorder

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import android.widget.Toast

/** Versión, enlaces al código, a la política de privacidad, a más apps del autor y a su cuenta de X. */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.about_version, version)

        findViewById<TextView>(R.id.subSource).text =
            getString(R.string.url_source).removePrefix("https://")
        findViewById<TextView>(R.id.subMore).text =
            getString(R.string.url_more).removePrefix("https://")
        findViewById<TextView>(R.id.subX).text = getString(R.string.handle_x)

        findViewById<View>(R.id.rowSource).setOnClickListener { open(R.string.url_source) }
        findViewById<View>(R.id.rowPrivacy).setOnClickListener { open(R.string.url_privacy) }
        findViewById<View>(R.id.rowMore).setOnClickListener { open(R.string.url_more) }
        findViewById<View>(R.id.rowX).setOnClickListener { open(R.string.url_x) }
    }

    /** Se abre en el navegador: la app no necesita (ni tiene) permiso de internet. */
    private fun open(urlRes: Int) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlRes))))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
