# Guía de Play Console para CaptureCam

Todo lo que hay que rellenar al publicar, con los textos listos para copiar.
Los datos de la ficha (títulos, descripciones, capturas, gráficos) están en `fastlane/metadata/android/<idioma>/`, con la
estructura de [fastlane supply](https://docs.fastlane.tools/actions/supply/): `title.txt`, `short_description.txt`,
`full_description.txt` e `images/` (`icon.png` y `featureGraphic.png` solo en el idioma por defecto, `en-US`;
`phoneScreenshots/` en cada idioma). Cualquier herramienta (fastlane, un script, un bot o tú a mano) puede leerla tal cual.

## 1. Antes de subir nada

1. Cuenta de desarrollador: 25 $ de pago único y verificación de identidad (documento oficial y tarjeta a tu nombre).
2. **Cuenta personal creada después del 13-nov-2023:** antes de poder publicar hay que hacer una **prueba cerrada con
   al menos 12 testers apuntados durante 14 días seguidos**. Con cuenta de organización no se pide.
3. **Web `iaen.es/android` publicada (11-oct-2026).** La política de privacidad y la página de la app viven en
   `https://iaen.es/android/capturecam/privacy/` y `https://iaen.es/android/capturecam/` (repo `IAnMove/home`,
   rama `main`, se despliega sola en ~90 s). Play exige la política en una URL pública antes de enviar, y la app
   enlaza a ella desde «Acerca de». Falta que el repositorio `https://github.com/IAnMove/CaptureCam` exista y sea
   público: la app y la web enlazan al código fuente (hoy ese enlace da 404).
4. Añadir un archivo `LICENSE` al repositorio. La ficha dice «gratuita y de código abierto».
5. El identificador `es.iaen.capturecam` es **permanente** una vez publicada la app.

## 2. Subir la app

- Archivo: `app/build/outputs/bundle/release/app-release.aab` (`./gradlew bundleRelease`).
- Activa **Play App Signing** cuando te lo pida: Google guarda la clave final y tú conservas la **clave de subida**
  (`~/keystores/capturecam-upload.jks` + `keystore.properties`). **Haz copia de seguridad de las dos cosas** en un sitio
  distinto de este ordenador (gestor de contraseñas o disco cifrado). Si perdieras la clave de subida habría que pedir
  un restablecimiento a Google.
- Cada subida nueva necesita un `versionCode` mayor (`app/build.gradle.kts`).

## 3. Ficha principal

| Campo | Valor |
| --- | --- |
| Nombre y descripciones | `fastlane/metadata/android/<idioma>/title.txt`, `short_description.txt`, `full_description.txt` (20 idiomas) |
| Idioma predeterminado | English (en-US) |
| Categoría | Herramientas (Tools) |
| Icono | `fastlane/metadata/android/en-US/images/icon.png` |
| Imagen destacada | `fastlane/metadata/android/en-US/images/featureGraphic.png` |
| Capturas de teléfono | `fastlane/metadata/android/en-US/images/phoneScreenshots/` (7, orden 1 a 7). Los demás idiomas tienen 4 capturas propias |
| Correo de contacto | El que prefieras; **es público** en la ficha |
| Política de privacidad | `https://iaen.es/android/capturecam/privacy/` (el texto original está en `PRIVACY.md`) |
| Sitio web | `https://iaen.es/android/capturecam/` |

No menciones otras apps de grabación en la ficha.

## 4. Contenido de la app

- **Anuncios:** la app no contiene anuncios.
- **Acceso a la app:** no hay restricciones, no hace falta cuenta ni credenciales.
- **Clasificación de contenido:** categoría «Utilidad, productividad, comunicación u otras». Todas las respuestas «No».
- **Público objetivo:** 13 años o más. No está dirigida a niños.
- **Seguridad de los datos:** no recopila ni comparte ningún dato. No hace falta declarar cifrado ni borrado.
- **Apps de noticias, gobierno, finanzas, salud:** ninguna.

## 5. Declaración de servicios en primer plano

Play pide una descripción y un **vídeo de demostración** (enlace a YouTube «no listado») por cada tipo.
El vídeo está en `store/demo/` (grabado antes de añadir el sonido de las apps; sigue siendo válido) y recorre: abrir la app → activar micrófono → pulsar grabar → aviso de captura →
cuenta atrás → grabación con notificación → detener → vista previa.

**Tipo `mediaProjection`** (caso de uso: grabación con MediaProjection)

> CaptureCam is a screen recorder. When the user taps Record and accepts Android's system screen-capture prompt, the
> app starts a foreground service of type mediaProjection that records the screen to an MP4 file until the user taps
> Stop. The service shows a persistent notification with Pause and Stop controls, and the Android capture indicator is
> visible while it runs. By default it also captures the sound played by the recorded apps (Android's audio playback
> capture, tied to the same screen-capture consent) and stores it in the video file.

*Si el sistema interrumpe la tarea:* «The recording ends early and the part already captured is saved as a video.
The user is notified and can start a new recording.»

**Tipo `microphone`**

> Optional feature. If the user turns on "Record microphone" and grants the microphone permission, the same foreground
> service also uses the microphone type so the app can keep capturing the user's voice while the screen is being
> recorded, even when the user is in another app. Audio is stored only inside the video file. Nothing is sent anywhere.

*Si el sistema interrumpe la tarea:* «The recording continues without audio or ends early; the captured part is saved.»

## 6. Permisos

| Permiso | Para qué | Texto de justificación |
| --- | --- | --- |
| `RECORD_AUDIO` | Sonido de las apps (activado por defecto) y micrófono, opcional | Required by Android to capture the sound played by the apps being recorded ("Record app sound", on by default) and, if the user enables "Record microphone", the microphone. Audio is stored only inside the video file and never leaves the device. |
| `POST_NOTIFICATIONS` | Controles de grabación y aviso de vídeo guardado | Shows the recording controls (Pause/Stop) and tells the user when a video is saved. |
| `SYSTEM_ALERT_WINDOW` | Cuenta atrás 3-2-1 en pantalla, opcional | Used only to draw a 3-second countdown over the app the user is about to record. It is optional: the app explains it before asking and works without it. |
| `FOREGROUND_SERVICE*` | Servicio de grabación | See section 5. |

No he podido comprobar si Play exige una declaración específica para `SYSTEM_ALERT_WINDOW`. Si el revisor lo cuestiona,
la alternativa es retirar ese permiso y la cuenta atrás en pantalla (quedaría solo en la notificación).

## 7. Prueba cerrada (cuenta personal)

1. Producción → pruebas → **Pruebas cerradas** → crear pista, subir el `.aab`.
2. Crear una lista de testers (correos de Google) o un Grupo de Google; **12 o más**.
3. Compartir el enlace de participación. Cada tester debe aceptar y **mantenerse apuntado 14 días seguidos**.
4. Pasados los 14 días: Panel → «Solicitar acceso a producción» y responder el cuestionario.
5. Después, crear la versión de producción con el mismo `.aab` (o uno nuevo).

## 8. Revisar antes de enviar

- Las traducciones de la app y de la ficha están hechas sin revisión de nativos: conviene que alguien revise los
  idiomas que más te importen antes de la versión de producción.
- Los nombres de permisos y carpetas (p. ej. «Aparecer encima») varían según el fabricante y la versión de Android.
