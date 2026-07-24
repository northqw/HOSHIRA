import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "dev.aniliberty.desktop"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    implementation("net.java.dev.jna:jna:5.6.0")
    implementation("net.java.dev.jna:jna-platform:5.6.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "dev.aniliberty.desktop.MainKt"
        // Coil discovers network fetchers through ServiceLoader, while JNA
        // reflects native interface signatures. ProGuard shrinking/optimization
        // breaks both mechanisms, so desktop releases favor correctness here.
        buildTypes.release.proguard.isEnabled.set(false)
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Hoshira"
            packageVersion = "0.2.4"
            description = "Hoshira — неофициальный desktop-клиент для просмотра аниме"
            vendor = "Hoshira Community"
            modules("java.net.http", "jdk.crypto.ec")

            windows {
                iconFile.set(project.file("src/main/resources/icons/hoshira.ico"))
                shortcut = true
                menu = true
                menuGroup = "Hoshira"
                dirChooser = true
                upgradeUuid = "6a7125ad-4baa-4e21-b9a1-32a664ccf60c"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
