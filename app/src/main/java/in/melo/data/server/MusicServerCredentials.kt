package `in`.melo.data.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** The queue can be backed up. The server password lives in this device's Keystore instead. */
internal class MusicServerCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("music_server_credentials", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): MusicServerAccount? {
        val saved = prefs.getString("account", null) ?: return null
        return runCatching {
            val (iv, encrypted) = saved.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv.decode()))
            val json = JSONObject(cipher.doFinal(encrypted.decode()).decodeToString())
            MusicServerAccount(json.getString("url").toHttpUrl(), json.getString("username"), json.getString("password"))
        }.getOrNull()
    }

    @Synchronized
    fun write(account: MusicServerAccount) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val json = JSONObject().put("url", account.url.toString())
            .put("username", account.username).put("password", account.password)
        val encrypted = cipher.doFinal(json.toString().encodeToByteArray())
        check(prefs.edit().putString("account", "${cipher.iv.encode()}:${encrypted.encode()}").commit())
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().remove("account").commit())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_NAME, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_NAME, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun ByteArray.encode(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decode(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val KEY_NAME = "melo-music-server"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
