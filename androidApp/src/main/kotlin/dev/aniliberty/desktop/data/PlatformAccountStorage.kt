package dev.aniliberty.desktop.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.aniliberty.desktop.platformDataDirectory
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun platformProtectSession(payload: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, accountKey())
    val encrypted = cipher.doFinal(payload)
    return ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size)
        .put(STORAGE_VERSION)
        .put(cipher.iv.size.toByte())
        .put(cipher.iv)
        .put(encrypted)
        .array()
}

internal fun platformUnprotectSession(payload: ByteArray): ByteArray {
    val buffer = ByteBuffer.wrap(payload)
    require(buffer.get() == STORAGE_VERSION) { "Unsupported account storage version" }
    val ivSize = buffer.get().toInt() and 0xff
    require(ivSize in 12..32 && buffer.remaining() > ivSize) { "Invalid account storage payload" }
    val iv = ByteArray(ivSize).also(buffer::get)
    val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, accountKey(), GCMParameterSpec(128, iv))
    return cipher.doFinal(encrypted)
}

internal fun platformConfigDirectory(): Path = platformDataDirectory()

private fun accountKey(): SecretKey {
    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
        init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generateKey()
    }
}

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "hoshira.account.session"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val STORAGE_VERSION: Byte = 1
