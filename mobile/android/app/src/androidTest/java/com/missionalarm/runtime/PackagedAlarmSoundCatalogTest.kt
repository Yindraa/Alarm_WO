package com.missionalarm.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackagedAlarmSoundCatalogTest {
  @Test
  fun classicStableIdMapsToPrivatePackagedAssetPath() {
    val sound = checkNotNull(PackagedAlarmSoundCatalog.find("classic"))

    assertEquals("classic", sound.soundId)
    assertEquals("alarms/classic.ogg", sound.assetPath)
    assertTrue(!sound.assetPath.startsWith("/") && ".." !in sound.assetPath)
  }

  @Test
  fun unknownPersistedSoundDoesNotResolveToArbitraryAsset() {
    assertNull(PackagedAlarmSoundCatalog.find("../../external"))
    assertNull(PackagedAlarmSoundCatalog.find("unknown"))
  }
}
