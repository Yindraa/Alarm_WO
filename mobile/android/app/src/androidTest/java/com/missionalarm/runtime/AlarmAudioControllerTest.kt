package com.missionalarm.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmAudioControllerTest {
  @Test
  fun duplicateInstanceStartDoesNotCreateOrStartAnotherSession() {
    val events = mutableListOf<String>()
    val sessions = mutableListOf<FakeSession>()
    val controller = AlarmAudioController { soundId ->
      FakeSession(soundId, events).also(sessions::add)
    }

    assertTrue(controller.start(INSTANCE_1, "classic"))
    assertFalse(controller.start(INSTANCE_1, "classic"))

    assertEquals(INSTANCE_1, controller.activeInstanceId())
    assertEquals(1, sessions.size)
    assertEquals(listOf("start:classic"), events)
  }

  @Test
  fun replacementStopsOldSessionBeforeStartingNewSession() {
    val events = mutableListOf<String>()
    val controller = AlarmAudioController { FakeSession(it, events) }
    controller.start(INSTANCE_1, "classic")

    assertTrue(controller.start(INSTANCE_2, "urgent"))

    assertEquals(
      listOf("start:classic", "stop:classic", "start:urgent"),
      events,
    )
    assertEquals(INSTANCE_2, controller.activeInstanceId())
    controller.stop()
    assertNull(controller.activeInstanceId())
    assertEquals("stop:urgent", events.last())
  }

  @Test
  fun staleStopCannotTerminateNewNotificationOwnerWhenAudioIsUnavailable() {
    assertFalse(ownsStopRequest(INSTANCE_1, null, INSTANCE_2))
    assertTrue(ownsStopRequest(INSTANCE_2, null, INSTANCE_2))
    assertTrue(ownsStopRequest(INSTANCE_1, null, null))
  }

  private class FakeSession(
    private val name: String,
    private val events: MutableList<String>,
  ) : AlarmAudioSession {
    override fun start() { events += "start:$name" }
    override fun stop() { events += "stop:$name" }
  }

  private companion object {
    const val INSTANCE_1 = "92d9035c-adfe-422e-949b-b96877cec786"
    const val INSTANCE_2 = "1d833849-5cbb-4c62-a98b-934592938a9e"
  }
}
