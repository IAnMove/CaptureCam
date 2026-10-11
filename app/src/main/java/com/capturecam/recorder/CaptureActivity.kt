package com.capturecam.recorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Actividad transparente que pide los permisos y el consentimiento de captura de pantalla
 * y después arranca el servicio. La usan el botón de la app y el ajuste rápido.
 */
class CaptureActivity : Activity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        if (RecorderState.phase != Phase.IDLE) {
            finish()
            return
        }
        // Tras una rotación la actividad se recrea y ya hay un diálogo en pantalla.
        if (savedInstanceState != null) return

        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // El sonido de las apps también exige RECORD_AUDIO, aunque el micrófono no se use.
            if ((settings.mic || settings.appAudio) && !granted(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (missing.isEmpty()) askConsent() else requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode != REQ_PERMISSIONS) return
        val micDenied = permissions.indices.any {
            permissions[it] == Manifest.permission.RECORD_AUDIO &&
                grantResults[it] != PackageManager.PERMISSION_GRANTED
        }
        if (micDenied) toast(R.string.toast_mic_denied)
        // Si deniega las notificaciones, grabar sigue funcionando; solo no verá los controles.
        askConsent()
    }

    private fun askConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // El diálogo deja elegir cada vez entre toda la pantalla y una sola app.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            RecorderService.start(this, resultCode, data)
            // Si el usuario eligió "una sola app", el sistema ya la ha puesto delante y no hay
            // que tocarla. Solo se aparta CaptureCam para que no salga en la grabación.
            MainActivity.sendToBack()
        } else {
            toast(R.string.toast_no_consent)
        }
        finish()
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_CAPTURE = 2

        fun launch(context: android.content.Context) {
            context.startActivity(
                Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
