package com.missionalarm.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionTest {
  @Test
  fun `mission targets enforce type-specific bounds`() {
    MissionConfig(MissionType.PUSH_UP, version = 1, target = 50)
    MissionConfig(MissionType.MATH, version = 1, target = 10)
    MissionConfig(MissionType.QR, version = 1, target = 1)

    assertThrows(IllegalArgumentException::class.java) {
      MissionConfig(MissionType.PUSH_UP, version = 1, target = 51)
    }
    assertThrows(IllegalArgumentException::class.java) {
      MissionConfig(MissionType.QR, version = 1, target = 2)
    }
  }

  @Test
  fun `verified progress is monotonic bounded and idempotent`() {
    val started = MissionProgress.start(target = 3)
    val first = started.commitVerified(1)

    assertSame(first, first.commitVerified(1))
    assertEquals(2, first.commitVerified(2).committed)
    assertThrows(IllegalArgumentException::class.java) { first.commitVerified(0) }
    assertThrows(IllegalArgumentException::class.java) { first.commitVerified(4) }
    assertTrue(first.commitVerified(3).isComplete)
  }
}
