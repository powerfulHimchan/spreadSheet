package com.powerfulhimchan.familyledger

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RelayConnection(val endpoint: String = "", val key: String = "") {
    fun validate() {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(uri != null && uri.scheme == "https" && uri.host == "script.google.com" &&
            uri.port == -1 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.matches(Regex("/macros/s/[A-Za-z0-9_-]+/exec"))) {
            "Apps Script 웹 앱의 https://script.google.com/macros/s/…/exec 주소를 입력해 주세요."
        }
        require(key.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "연결키는 영문·숫자·하이픈·밑줄 32~128자여야 합니다." }
    }
    companion object {
        fun generateKey(): String = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

/** Relay credentials never enter the ledger database, backups, logs or source control. */
class RelaySettings(context: Context) {
    private val preferences = context.getSharedPreferences("relay-connection", Context.MODE_PRIVATE)
    private val alias = "familyledger-relay-v1"
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): RelayConnection {
        val saved = preferences.getString("encrypted", null) ?: return RelayConnection()
        return runCatching {
            val data = Base64.decode(saved, Base64.NO_WRAP)
            require(data.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
            val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
            RelayConnection(json.getString("endpoint"), json.getString("key")).also { it.validate() }
        }.getOrElse { RelayConnection() }
    }
    fun save(connection: RelayConnection) {
        connection.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val json = JSONObject().put("endpoint", connection.endpoint).put("key", connection.key).toString().toByteArray(Charsets.UTF_8)
        check(preferences.edit().putString("encrypted", Base64.encodeToString(cipher.iv + cipher.doFinal(json), Base64.NO_WRAP)).commit()) { "연결 설정을 저장하지 못했습니다." }
    }
    fun clear() { preferences.edit().clear().apply() }
}
