package com.shiva9ro.ssbloginhelper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(
    context: Context
) {

    data class Credentials(
        val loginId: String,
        val password: String
    )

    private data class EncryptedValue(
        val encryptedText: String,
        val initializationVector: String
    )

    companion object {
        private const val KEY_ALIAS =
            "ssb_login_helper_credentials_key"

        private const val KEYSTORE_PROVIDER =
            "AndroidKeyStore"

        private const val TRANSFORMATION =
            "AES/GCM/NoPadding"

        private const val GCM_TAG_LENGTH_BITS =
            128

        private const val PREFERENCES_NAME =
            "encrypted_credentials"

        private const val LOGIN_ID_VALUE =
            "login_id_value"

        private const val LOGIN_ID_IV =
            "login_id_iv"

        private const val PASSWORD_VALUE =
            "password_value"

        private const val PASSWORD_IV =
            "password_iv"
    }

    private val preferences =
        context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )

    fun save(
        loginId: String,
        password: String
    ) {
        require(loginId.isNotBlank()) {
            "ログインIDが空です"
        }

        require(password.isNotBlank()) {
            "パスワードが空です"
        }

        val encryptedLoginId =
            encrypt(loginId)

        val encryptedPassword =
            encrypt(password)

        preferences
            .edit()
            .putString(
                LOGIN_ID_VALUE,
                encryptedLoginId.encryptedText
            )
            .putString(
                LOGIN_ID_IV,
                encryptedLoginId.initializationVector
            )
            .putString(
                PASSWORD_VALUE,
                encryptedPassword.encryptedText
            )
            .putString(
                PASSWORD_IV,
                encryptedPassword.initializationVector
            )
            .apply()
    }

    fun load(): Credentials? {
        val encryptedLoginId =
            preferences.getString(
                LOGIN_ID_VALUE,
                null
            ) ?: return null

        val loginIdIv =
            preferences.getString(
                LOGIN_ID_IV,
                null
            ) ?: return null

        val encryptedPassword =
            preferences.getString(
                PASSWORD_VALUE,
                null
            ) ?: return null

        val passwordIv =
            preferences.getString(
                PASSWORD_IV,
                null
            ) ?: return null

        return try {
            Credentials(
                loginId = decrypt(
                    encryptedLoginId,
                    loginIdIv
                ),
                password = decrypt(
                    encryptedPassword,
                    passwordIv
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    fun hasCredentials(): Boolean {
        return load() != null
    }

    fun clear() {
        preferences
            .edit()
            .clear()
            .apply()
    }

    private fun encrypt(
        plainText: String
    ): EncryptedValue {
        val cipher =
            Cipher.getInstance(
                TRANSFORMATION
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateSecretKey()
        )

        val encryptedBytes =
            cipher.doFinal(
                plainText.toByteArray(
                    Charsets.UTF_8
                )
            )

        return EncryptedValue(
            encryptedText =
                Base64.encodeToString(
                    encryptedBytes,
                    Base64.NO_WRAP
                ),
            initializationVector =
                Base64.encodeToString(
                    cipher.iv,
                    Base64.NO_WRAP
                )
        )
    }

    private fun decrypt(
        encryptedText: String,
        initializationVector: String
    ): String {
        val cipher =
            Cipher.getInstance(
                TRANSFORMATION
            )

        val iv =
            Base64.decode(
                initializationVector,
                Base64.NO_WRAP
            )

        val parameterSpec =
            GCMParameterSpec(
                GCM_TAG_LENGTH_BITS,
                iv
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            parameterSpec
        )

        val encryptedBytes =
            Base64.decode(
                encryptedText,
                Base64.NO_WRAP
            )

        val decryptedBytes =
            cipher.doFinal(
                encryptedBytes
            )

        return decryptedBytes.toString(
            Charsets.UTF_8
        )
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore =
            KeyStore.getInstance(
                KEYSTORE_PROVIDER
            ).apply {
                load(null)
            }

        val existingKey =
            keyStore.getKey(
                KEY_ALIAS,
                null
            )

        if (existingKey is SecretKey) {
            return existingKey
        }

        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )

        val specification =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()

        keyGenerator.init(
            specification
        )

        return keyGenerator.generateKey()
    }
}
