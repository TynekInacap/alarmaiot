plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cl.alarmaiot"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cl.alarmaiot"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            // Para la demo se firma con la clave de depuración: así la versión release
            // (no depurable, con capturas bloqueadas) se instala desde Android Studio.
            // Para publicar en Google Play se reemplaza por una clave propia.
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.paho.mqtt)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
