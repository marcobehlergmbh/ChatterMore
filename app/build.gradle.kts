import java.util.Properties

plugins {
    id("com.android.application")
    id("com.github.triplet.play")
}

val signingPropertiesFile = rootProject.file(".signing/keystore.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use(::load)
    }
}
val chatterMoreVersionCode = providers.gradleProperty("chattermoreVersionCode")
    .map(String::toInt)
    .getOrElse(2)
val chatterMoreVersionName = providers.gradleProperty("chattermoreVersionName")
    .getOrElse("0.1.1")

android {
    namespace = "com.marcobehler.chattermore"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.marcobehler.chattermore"
        minSdk = 29
        targetSdk = 36
        versionCode = chatterMoreVersionCode
        versionName = chatterMoreVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingPropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "BENCHMARK_FIXTURES", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("boolean", "BENCHMARK_FIXTURES", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "BENCHMARK_FIXTURES", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        buildConfig = true
    }
}

play {
    providers.environmentVariable("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON").orNull?.let { path ->
        serviceAccountCredentials.set(file(path))
    }
    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
