package com.missionalarm.mission.camera

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidQrHmacProviderTest {
  private val alias = "mission_alarm_test_${UUID.randomUUID()}"

  @After
  fun tearDown() {
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
  }

  @Test
  fun keyRemainsNonExportableAndDigestIsDeterministic() {
    val provider = AndroidQrHmacProvider()
    val first = provider.digest(alias, "registered".toByteArray())
    val repeated = provider.digest(alias, "registered".toByteArray())
    val different = provider.digest(alias, "different".toByteArray())
    val storedKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      .getKey(alias, null)

    assertArrayEquals(first, repeated)
    assertFalse(first.contentEquals(different))
    assertNull(storedKey.encoded)
  }
}
