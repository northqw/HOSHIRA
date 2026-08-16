package app.hoshira.desktop.data

import com.sun.jna.platform.win32.Crypt32Util
import app.hoshira.desktop.platformDataDirectory
import java.nio.file.Path

internal fun platformProtectSession(payload: ByteArray): ByteArray =
    Crypt32Util.cryptProtectData(payload)

internal fun platformUnprotectSession(payload: ByteArray): ByteArray =
    Crypt32Util.cryptUnprotectData(payload)

internal fun platformConfigDirectory(): Path = platformDataDirectory()
