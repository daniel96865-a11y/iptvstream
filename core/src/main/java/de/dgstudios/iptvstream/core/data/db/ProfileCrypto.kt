package de.dgstudios.iptvstream.core.data.db

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Verschlüsselt IPTV-Zugangsdaten mit einem Android-Keystore-Schlüssel. */
object ProfileCrypto {
    private const val PREFIX = "enc:v1:"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "iptvstream-profile-password"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = java.security.KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(value: String): String {
        if (value.isEmpty() || value.startsWith(PREFIX)) return value
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        return try {
            val payload = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, 12)
            val ciphertext = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // Bei einem verlorenen Keystore-Schlüssel nichts überschreiben; der Benutzer
            // kann das Profil weiterhin neu speichern und damit neu verschlüsseln.
            value
        }
    }

    fun fromStorage(profile: ProfileEntity): ProfileEntity =
        profile.copy(password = decrypt(profile.password))

    fun toStorage(profile: ProfileEntity): ProfileEntity =
        profile.copy(password = encrypt(profile.password))
}
