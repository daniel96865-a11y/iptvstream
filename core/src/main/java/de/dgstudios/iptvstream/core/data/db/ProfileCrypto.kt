package de.dgstudios.iptvstream.core.data.db

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

/**
 * Verschlüsselt IPTV-Zugangsdaten mit einem Android-Keystore-Schlüssel.
 *
 * Ab Android 6 (API 23): AES/GCM-Schlüssel im Keystore (unverändert, Präfix `enc:v1:`).
 * Android 5.x (z. B. Fire OS 5) kennt keine AES-Schlüssel im Keystore; dort wird ein
 * RSA-Schlüsselpaar im Keystore verwendet (Präfix `enc:r1:`).
 */
object ProfileCrypto {
    private const val PREFIX = "enc:v1:"
    private const val PREFIX_RSA = "enc:r1:"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "iptvstream-profile-password"
    private const val ALIAS_RSA = "iptvstream-profile-password-rsa"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TRANSFORMATION_RSA = "RSA/ECB/PKCS1Padding"

    /** Wird in der Application gesetzt; nur für die RSA-Schlüsselerzeugung unter API 23 nötig. */
    @SuppressLint("StaticFieldLeak")
    @Volatile
    var appContext: Context? = null

    @RequiresApi(23)
    private object Aes {
        fun key(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
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
    }

    private object Rsa {
        private fun store(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        @Suppress("DEPRECATION")
        fun publicKey(): PublicKey {
            val ks = store()
            ks.getCertificate(ALIAS_RSA)?.publicKey?.let { return it }
            val ctx = appContext ?: error("Kein Context für Keystore")
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            val spec = KeyPairGeneratorSpec.Builder(ctx)
                .setAlias(ALIAS_RSA)
                .setSubject(X500Principal("CN=$ALIAS_RSA"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
            val gen = KeyPairGenerator.getInstance("RSA", KEYSTORE)
            gen.initialize(spec)
            return gen.generateKeyPair().public
        }

        fun privateKey(): PrivateKey? = store().getKey(ALIAS_RSA, null) as? PrivateKey
    }

    fun encrypt(value: String): String {
        if (value.isEmpty() || value.startsWith(PREFIX) || value.startsWith(PREFIX_RSA)) return value
        return if (Build.VERSION.SDK_INT >= 23) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, Aes.key())
            val payload = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        } else {
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION_RSA)
                cipher.init(Cipher.ENCRYPT_MODE, Rsa.publicKey())
                PREFIX_RSA + Base64.encodeToString(
                    cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
                    Base64.NO_WRAP,
                )
            } catch (_: Exception) {
                // Alter Keystore defekt/gesperrt: lieber unverschlüsselt speichern als abstürzen.
                value
            }
        }
    }

    fun decrypt(value: String): String {
        if (value.startsWith(PREFIX_RSA)) {
            return try {
                val key = Rsa.privateKey() ?: return value
                val cipher = Cipher.getInstance(TRANSFORMATION_RSA)
                cipher.init(Cipher.DECRYPT_MODE, key)
                String(cipher.doFinal(Base64.decode(value.removePrefix(PREFIX_RSA), Base64.NO_WRAP)), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                value
            }
        }
        if (!value.startsWith(PREFIX) || Build.VERSION.SDK_INT < 23) return value
        return try {
            val payload = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, 12)
            val ciphertext = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, Aes.key(), GCMParameterSpec(128, iv))
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
