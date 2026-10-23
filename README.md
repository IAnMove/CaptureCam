# CaptureCam

A simple, free screen recorder for Android. **No ads, no accounts, no internet access.**

Un grabador de pantalla sencillo y gratuito para Android. **Sin anuncios, sin cuentas y sin acceso a internet.**

## Features

- Record the whole screen or a single app
- Pause / resume, controls in the notification and a Quick Settings tile
- Optional on-screen 3-2-1 countdown
- Resolution, frame rate, quality and orientation settings
- Records the sound of the apps you are recording (on by default) and, optionally, the microphone
- Saves standard MP4 files to `Downloads/CaptureCam` or `Movies/CaptureCam`
- Built-in gallery: browse recent recordings (with duration and size in MB), preview, share, open with another app or delete (with confirmation)
- Compress: saves a smaller copy next to the original (same resolution, lower bitrate, audio untouched); the original is never deleted automatically
- 20 languages

## Privacy

The app does not declare the `INTERNET` permission, contains no ads, analytics or third-party SDKs, and
keeps everything on your device. See [PRIVACY.md](PRIVACY.md) (also published at https://iaen.es/android/capturecam/privacy/).

## Build

Requirements: JDK 17 and the Android SDK (platform 36, build-tools 36.0.0).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease        # app/build/outputs/bundle/release/app-release.aab
```

Release builds are signed with the key described in `keystore.properties` (not versioned). Without that
file the release build falls back to the debug key, so anyone can build it from a clone.

The app has no dependencies beyond the Android framework.

## Project layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/com/capturecam/recorder` | App code (Kotlin) |
| `app/src/main/res/values-*` | Translations |
| `fastlane/metadata/android/` | Google Play listing in [fastlane supply](https://docs.fastlane.tools/actions/supply/) layout: texts, screenshots and graphics for 20 languages |
| `store/PLAY_CONSOLE.md` | Step-by-step guide to the Play Console (in Spanish) |
| `tools/check_i18n.py` | Validates translations and Play listing length limits |
| `tools/make_graphics.sh` | Regenerates the Play icon and feature graphic |
| `LICENSE`, `NOTICE` | Apache 2.0 license and the attribution notice that must be kept |

## Known limitations

- App sound depends on each app: apps that opt out of audio capture, DRM-protected audio and some browsers' audio come out silent. Android also asks for the microphone permission for this, although the microphone is not used.
- Apps that block screen capture (some banking or video apps) are recorded as black.
- The frame-rate setting is a hint: the recorder follows the display refresh rate.
- Requires Android 11 or newer.

## AI usage

CaptureCam was written with substantial help from AI coding assistants (code generation, translations and
store texts). I am the developer: I reviewed every change, tested the app on real devices and maintain the
code. The app itself contains no AI features, models or network calls.

Esta app se ha programado con ayuda importante de asistentes de IA. Yo, el desarrollador, he revisado cada
cambio, la he probado en móviles reales y mantengo el código. La app no incluye funciones de IA ni se conecta a internet.

## License

[Apache License 2.0](LICENSE). You are free to use, modify and redistribute the code, including commercially,
as long as you keep the copyright notice and the [NOTICE](NOTICE) file (credit to IAnMove) in your copies and
derivative works, and state any changes you made.

## Contact

[@theinaog on X](https://x.com/theinaog) · More apps: [iaen.es/android](https://iaen.es/android)
