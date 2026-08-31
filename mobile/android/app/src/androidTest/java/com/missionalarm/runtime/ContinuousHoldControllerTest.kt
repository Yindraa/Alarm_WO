package com.missionalarm.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContinuousHoldControllerTest {
  @Test
  fun completionRequiresFullContinuousFiveSeconds() {
    val clock = MutableClock(100)
    val controller = ContinuousHoldController(clock)

    controller.begin()
    clock.now = 5_099
    assertFalse(controller.progress().completed)
    clock.now = 5_100
    val complete = controller.progress()

    assertTrue(complete.completed)
    assertEquals(1f, complete.fraction)
    assertEquals(0L, complete.remainingMs)
  }

  @Test
  fun releaseResetsAllPriorProgress() {
    val clock = MutableClock(0)
    val controller = ContinuousHoldController(clock)
    controller.begin()
    clock.now = 4_999
    assertEquals(4_999L, controller.progress().elapsedMs)

    assertEquals(0L, controller.cancel().elapsedMs)
    controller.begin()
    clock.now = 9_998

    assertFalse(controller.progress().completed)
    assertEquals(4_999L, controller.progress().elapsedMs)
  }

  @Test
  fun duplicateDownDoesNotRestartOrShortenHold() {
    val clock = MutableClock(1_000)
    val controller = ContinuousHoldController(clock)
    controller.begin()
    clock.now = 3_000
    controller.begin()
    clock.now = 6_000

    assertTrue(controller.progress().completed)
  }

  private class MutableClock(var now: Long) : MonotonicClock {
    override fun nowMs() = now
  }
}
