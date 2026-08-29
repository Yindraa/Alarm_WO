package com.missionalarm.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentifiersTest {
  @Test
  fun `accepts lowercase UUID v4`() {
    val id = AlarmId.parse("5a7464b0-77b6-4f75-8459-974dc6d44160")

    assertEquals("5a7464b0-77b6-4f75-8459-974dc6d44160", id.value)
  }

  @Test
  fun `rejects uppercase or non-v4 identifiers`() {
    assertThrows(IllegalArgumentException::class.java) {
      AlarmId.parse("5A7464B0-77B6-4F75-8459-974DC6D44160")
    }
    assertThrows(IllegalArgumentException::class.java) {
      AlarmId.parse("5a7464b0-77b6-3f75-8459-974dc6d44160")
    }
  }

  @Test
  fun `revision starts positive and increments monotonically`() {
    assertThrows(IllegalArgumentException::class.java) { Revision.of(0) }
    assertEquals(2, Revision.of(1).next().value)
  }
}
