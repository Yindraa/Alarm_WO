package com.missionalarm.mission.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class QrDecodePolicyTest {
  @Test
  fun emptyAndMalformedValuesDoNotProduceEvidence() {
    assertEquals(QrDecodeOutcome.None, QrDecodePolicy.select(emptyList()))
    assertEquals(QrDecodeOutcome.None, QrDecodePolicy.select(listOf("", "")))
  }

  @Test
  fun duplicateFramesCollapseToOnePayloadButDistinctCodesAreRejected() {
    assertEquals(
      QrDecodeOutcome.Single("wake-reference"),
      QrDecodePolicy.select(listOf("wake-reference", "wake-reference")),
    )
    assertEquals(
      QrDecodeOutcome.Multiple,
      QrDecodePolicy.select(listOf("wake-reference", "other-reference")),
    )
  }
}
