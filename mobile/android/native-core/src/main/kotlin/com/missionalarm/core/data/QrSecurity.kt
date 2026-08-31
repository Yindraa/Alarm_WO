package com.missionalarm.core.data

import java.security.MessageDigest
import java.text.Normalizer

object QrDigestContract {
  const val VERSION = "qr-hmac-sha256-nfc-v1"
  const val DIGEST_SIZE_BYTES = 32
  const val MAX_PAYLOAD_BYTES = 4_096

  fun normalize(payload: String): ByteArray {
    require(payload.isNotEmpty()) { "QR payload must not be empty" }
    val normalized = Normalizer.normalize(payload, Normalizer.Form.NFC)
      .toByteArray(Charsets.UTF_8)
    require(normalized.size <= MAX_PAYLOAD_BYTES) { "QR payload exceeds supported size" }
    return normalized
  }
}

fun interface QrHmacProvider {
  /** Returns HMAC-SHA256 bytes without exposing the backing key. */
  fun digest(keyAlias: String, normalizedPayload: ByteArray): ByteArray
}

data class QrReferenceMaterial(
  val digest: ByteArray,
  val digestVersion: String,
  val keyAlias: String,
)

/** Keeps raw decoded content in memory only and compares references in constant time. */
class QrDigestService(
  private val hmacProvider: QrHmacProvider,
) {
  fun createReference(payload: String, keyAlias: String): QrReferenceMaterial {
    requireValidAlias(keyAlias)
    val normalized = QrDigestContract.normalize(payload)
    return try {
      val digest = hmacProvider.digest(keyAlias, normalized)
      try {
        require(digest.size == QrDigestContract.DIGEST_SIZE_BYTES) {
          "QR HMAC provider returned an invalid digest"
        }
        QrReferenceMaterial(digest.copyOf(), QrDigestContract.VERSION, keyAlias)
      } finally {
        digest.fill(0)
      }
    } finally {
      normalized.fill(0)
    }
  }

  fun matches(
    payload: String,
    expectedDigest: ByteArray,
    digestVersion: String,
    keyAlias: String,
  ): Boolean {
    require(digestVersion == QrDigestContract.VERSION) { "unsupported QR digest version" }
    require(expectedDigest.size == QrDigestContract.DIGEST_SIZE_BYTES) {
      "invalid QR reference digest"
    }
    requireValidAlias(keyAlias)
    val observed = createReference(payload, keyAlias).digest
    return try {
      MessageDigest.isEqual(expectedDigest, observed)
    } finally {
      observed.fill(0)
    }
  }

  private fun requireValidAlias(keyAlias: String) {
    require(keyAlias.length in 1..120 && keyAlias.all { it.isLetterOrDigit() || it in "._-" }) {
      "invalid QR key alias"
    }
  }
}
