import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "app.hoshira.desktop"

val hostOs = System.getProperty("os.name").lowercase()
val targetOs = providers.gradleProperty("hoshira.targetOs")
    .orElse(
        when {
            hostOs.contains("win") -> "windows"
            hostOs.contains("linux") -> "linux"
            hostOs.contains("mac") -> "macos"
            else -> error("Unsupported desktop operating system: $hostOs")
        },
    )
    .get()

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        when (targetOs) {
            "windows" -> kotlin.srcDir("src/windowsMain/kotlin")
            "linux" -> {
                kotlin.srcDir("src/linuxMain/kotlin")
                kotlin.exclude(
                    "app/hoshira/desktop/WindowsWindowStyle.kt",
                )
            }
            else -> error("Desktop target '$targetOs' is not configured yet")
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.components.resources)

    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    if (targetOs == "windows") {
        implementation("net.java.dev.jna:jna:5.6.0")
        implementation("net.java.dev.jna:jna-platform:5.6.0")
        implementation("org.openjfx:javafx-base:21.0.12:win")
        implementation("org.openjfx:javafx-controls:21.0.12:win")
        implementation("org.openjfx:javafx-graphics:21.0.12:win")
        implementation("org.openjfx:javafx-media:21.0.12:win")
        implementation("org.openjfx:javafx-swing:21.0.12:win")
    }
    if (targetOs == "linux") {
        implementation("org.eclipse.platform:org.eclipse.swt.gtk.linux.x86_64:3.128.0") {
            // The platform jar already contains SWT's Java classes. Its Maven
            // POM also references an unresolved ${osgi.platform} placeholder,
            // which Gradle cannot substitute and which is unnecessary here.
            exclude(group = "org.eclipse.platform", module = "org.eclipse.swt")
        }
    }

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "app.hoshira.desktop.MainKt"
        // Coil discovers network fetchers through ServiceLoader, while JNA
        // reflects native interface signatures. ProGuard shrinking/optimization
        // breaks both mechanisms, so desktop releases favor correctness here.
        buildTypes.release.proguard.isEnabled.set(false)
        nativeDistributions {
            when (targetOs) {
                "windows" -> targetFormats(TargetFormat.Msi, TargetFormat.Exe)
                "linux" -> targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            }
            packageName = "Hoshira"
            packageVersion = "0.4.0"
            description = "Hoshira — неофициальный desktop-клиент для просмотра аниме"
            vendor = "Hoshira Community"
            modules(
                "java.instrument",
                "java.net.http",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.unsupported",
                "jdk.unsupported.desktop",
            )
            if (targetOs == "windows") {
                windows {
                    iconFile.set(project.file("src/main/resources/icons/hoshira.ico"))
                    shortcut = true
                    menu = true
                    menuGroup = "Hoshira"
                    dirChooser = true
                    upgradeUuid = "6a7125ad-4baa-4e21-b9a1-32a664ccf60c"
                }
            }
            if (targetOs == "linux") {
                linux {
                    iconFile.set(project.file("src/main/resources/icons/hoshira.png"))
                    packageName = "hoshira"
                    debMaintainer = "northqw@users.noreply.gitverse.ru"
                    menuGroup = "AudioVideo"
                    appCategory = "video"
                    rpmLicenseType = "Proprietary"
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
    val stalePortableFlag = layout.buildDirectory
        .file("compose/binaries/main-release/app/Hoshira/portable.flag")
    outputs.upToDateWhen { !stalePortableFlag.get().asFile.exists() }
    doLast {
        stalePortableFlag.get().asFile.delete()
    }
}

val createPortableDistributable by tasks.registering(Sync::class) {
    dependsOn("createReleaseDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main-release/app")) {
        exclude("**/portable.flag")
    }
    into(layout.buildDirectory.dir("compose/binaries/main-release/portable"))

    doLast {
        val appRoot = layout.buildDirectory
            .dir("compose/binaries/main-release/portable/Hoshira")
            .get()
            .asFile
        appRoot.mkdirs()
        appRoot.resolve("portable.flag").writeText(
            "Hoshira portable mode 0.4.0\n",
        )
    }
}
