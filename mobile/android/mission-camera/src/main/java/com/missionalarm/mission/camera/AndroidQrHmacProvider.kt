package com.missionalarm.mission.camera

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.missionalarm.core.data.QrHmacProvider
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** HMAC authority whose key material is generated and retained inside Android Keystore. */
class AndroidQrHmacProvider : QrHmacProvider {
  override fun digest(keyAlias: String, normalizedPayload: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(getOrCreateKey(keyAlias))
    return mac.doFinal(normalizedPayload)
  }

  private fun getOrCreateKey(keyAlias: String): SecretKey = synchronized(KEY_LOCK) {
    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    (keyStore.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator
      .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
      .run {
        init(
          KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
          )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build(),
        )
        generateKey()
      }
  }

  private companion object {
    const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val HMAC_ALGORITHM = "HmacSHA256"
    val KEY_LOCK = Any()
  }
}
