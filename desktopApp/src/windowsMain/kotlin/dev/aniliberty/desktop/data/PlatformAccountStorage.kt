package dev.aniliberty.desktop.data

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Path

internal fun platformProtectSession(payload: ByteArray): ByteArray =
    Crypt32Util.cryptProtectData(payload)

internal fun platformUnprotectSession(payload: ByteArray): ByteArray =
    Crypt32Util.cryptUnprotectData(payload)

internal fun platformConfigDirectory(): Path {
    val localData = System.getenv("LOCALAPPDATA")
        ?.takeIf(String::isNotBlank)
        ?: System.getProperty("user.home")
    return Path.of(localData, "Hoshira")
}
