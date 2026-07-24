package dev.aniliberty.desktop.data

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun platformProtectSession(payload: ByteArray): ByteArray {
    val nonce = ByteArray(GCM_NONCE_SIZE).also(SECURE_RANDOM::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(loadOrCreateSessionKey(), "AES"),
        GCMParameterSpec(GCM_TAG_BITS, nonce),
    )
    val encrypted = cipher.doFinal(payload)
    return ByteBuffer.allocate(SESSION_MAGIC.size + nonce.size + encrypted.size)
        .put(SESSION_MAGIC)
        .put(nonce)
        .put(encrypted)
        .array()
}

internal fun platformUnprotectSession(payload: ByteArray): ByteArray {
    require(payload.size > SESSION_MAGIC.size + GCM_NONCE_SIZE) {
        "Invalid Hoshira session payload"
    }
    require(payload.copyOfRange(0, SESSION_MAGIC.size).contentEquals(SESSION_MAGIC)) {
        "Unsupported Hoshira session payload"
    }
    val nonceStart = SESSION_MAGIC.size
    val nonce = payload.copyOfRange(nonceStart, nonceStart + GCM_NONCE_SIZE)
    val encrypted = payload.copyOfRange(nonceStart + GCM_NONCE_SIZE, payload.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(loadOrCreateSessionKey(), "AES"),
        GCMParameterSpec(GCM_TAG_BITS, nonce),
    )
    return cipher.doFinal(encrypted)
}

internal fun platformConfigDirectory(): Path {
    val xdgConfig = System.getenv("XDG_CONFIG_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
    val home = System.getProperty("user.home")
    return (xdgConfig ?: Path.of(home, ".config")).resolve("hoshira")
}

private fun loadOrCreateSessionKey(): ByteArray {
    val keyFile = platformConfigDirectory()
        .resolve("account")
        .resolve("session.key")
    if (Files.isRegularFile(keyFile)) {
        return Files.readAllBytes(keyFile).also {
            require(it.size == SESSION_KEY_SIZE) { "Invalid Hoshira session key" }
        }
    }

    Files.createDirectories(keyFile.parent)
    val key = ByteArray(SESSION_KEY_SIZE).also(SECURE_RANDOM::nextBytes)
    Files.write(keyFile, key)
    runCatching {
        Files.setPosixFilePermissions(
            keyFile,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )
    }
    return key
}

private const val GCM_NONCE_SIZE = 12
private const val GCM_TAG_BITS = 128
private const val SESSION_KEY_SIZE = 32
private val SESSION_MAGIC = byteArrayOf(0x48, 0x53, 0x4C, 0x31)
private val SECURE_RANDOM = SecureRandom()
