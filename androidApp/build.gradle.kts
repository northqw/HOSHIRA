plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "dev.aniliberty.android"

val sharedDesktopSourceRoot =
    project.file("../desktopApp/src/main/kotlin").absoluteFile.normalize()
val desktopOnlySources = listOf(
    "dev/aniliberty/desktop/Main.kt",
    "dev/aniliberty/desktop/PortableMode.kt",
    "dev/aniliberty/desktop/WindowsWindowStyle.kt",
    "dev/aniliberty/desktop/ui/Theme.kt",
    "dev/aniliberty/desktop/ui/PlayerScreen.kt",
    "dev/aniliberty/desktop/ui/EmbeddedPlayerHost.kt",
).map { sharedDesktopSourceRoot.resolve(it).normalize() }.toSet()

android {
    namespace = "dev.aniliberty.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aniliberty.hoshira"
        minSdk = 26
        targetSdk = 36
        versionCode = 400
        versionName = "0.4.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/AL2.0",
            "/META-INF/LGPL2.1",
        )
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets.named("main") {
        kotlin.srcDir("../desktopApp/src/main/kotlin")
        kotlin.exclude { source -> source.file.absoluteFile.normalize() in desktopOnlySources }
    }
}

dependencies {
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.webkit:webkit:1.16.0")

    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
}
