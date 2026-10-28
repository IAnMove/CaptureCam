import java.util.Properties

plugins {
    id("com.android.application")
}

// Firma de publicación: se lee de keystore.properties (no versionado). Sin ese archivo, por ejemplo
// al compilar desde un clon del repositorio, el build de release usa la clave de depuración.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.capturecam.recorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.iaen.capturecam"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("upload") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    // La app permite cambiar de idioma por separado (Ajustes > Idioma de la app). Con la división por
    // idiomas activada, Google Play podría no entregar el idioma elegido si no es el del sistema.
    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
        buildConfig = false
    }
}

// Sin dependencias a propósito: solo el framework de Android.
// Sin SDKs de publicidad, analíticas ni red.
