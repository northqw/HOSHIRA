import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "app.hoshira.android"

val sharedDesktopSourceRoot =
    project.file("../desktopApp/src/main/kotlin").absoluteFile.normalize()
val desktopOnlySources = listOf(
    "app/hoshira/desktop/Main.kt",
    "app/hoshira/desktop/PortableMode.kt",
    "app/hoshira/desktop/WindowsWindowStyle.kt",
    "app/hoshira/desktop/ui/Theme.kt",
    "app/hoshira/desktop/ui/PlayerScreen.kt",
    "app/hoshira/desktop/ui/EmbeddedPlayerHost.kt",
).map { sharedDesktopSourceRoot.resolve(it).normalize() }.toSet()

val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("secrets.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(name: String): String? =
    releaseSigningProperties.getProperty(name)?.takeIf(String::isNotBlank)
        ?: providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)

val releaseStoreFile = releaseSigningValue("HOSHIRA_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("HOSHIRA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("HOSHIRA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("HOSHIRA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

android {
    namespace = "app.hoshira.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hoshira"
        minSdk = 26
        targetSdk = 36
        versionCode = 407
        versionName = "0.4.1"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
}
