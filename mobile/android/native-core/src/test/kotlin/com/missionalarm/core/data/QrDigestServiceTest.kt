package com.missionalarm.core.data

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QrDigestServiceTest {
  private val service = QrDigestService { _, payload ->
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec("test-only-secret".toByteArray(), "HmacSHA256"))
      doFinal(payload)
    }
  }

  @Test
  fun canonicallyEquivalentUnicodeProducesExactMatch() {
    val reference = service.createReference("Cafe\u0301", KEY_ALIAS)

    assertEquals(QrDigestContract.VERSION, reference.digestVersion)
    assertTrue(service.matches("Café", reference.digest, reference.digestVersion, KEY_ALIAS))
  }

  @Test
  fun whitespaceAndCaseRemainSignificant() {
    val reference = service.createReference("Mission Alarm", KEY_ALIAS)

    assertFalse(service.matches("mission alarm", reference.digest, reference.digestVersion, KEY_ALIAS))
    assertFalse(service.matches("Mission Alarm ", reference.digest, reference.digestVersion, KEY_ALIAS))
  }

  @Test
  fun emptyOversizedAndUnknownVersionAreRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      service.createReference("", KEY_ALIAS)
    }
    assertThrows(IllegalArgumentException::class.java) {
      service.createReference("x".repeat(QrDigestContract.MAX_PAYLOAD_BYTES + 1), KEY_ALIAS)
    }
    assertThrows(IllegalArgumentException::class.java) {
      service.matches("value", ByteArray(32), "future-v2", KEY_ALIAS)
    }
  }

  private companion object {
    const val KEY_ALIAS = "mission_alarm_qr_hmac_v1"
  }
}
