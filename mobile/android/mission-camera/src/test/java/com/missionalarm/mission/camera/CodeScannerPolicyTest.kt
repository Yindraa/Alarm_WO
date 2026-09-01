package com.missionalarm.mission.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeScannerPolicyTest {
  @Test
  fun emptyFrameProducesNoEvidenceAndDistinctCodesAreRejected() {
    assertEquals(CodeFrameOutcome.None, CodeFramePolicy.select(emptyList()))
    assertEquals(
      CodeFrameOutcome.Multiple,
      CodeFramePolicy.select(
        listOf(DecodedCode("one", "QR_CODE"), DecodedCode("two", "EAN_13")),
      ),
    )
  }

  @Test
  fun duplicateResultsCollapseAndNeedTwoStableDetections() {
    val code = DecodedCode("product-123", "EAN_13")
    assertEquals(CodeFrameOutcome.Single(code), CodeFramePolicy.select(listOf(code, code)))
    val gate = StableCodeGate(2)
    assertNull(gate.observe(code))
    assertEquals(code, gate.observe(code))
  }

  @Test
  fun changingCodeRestartsStabilityEvidence() {
    val gate = StableCodeGate(2)
    val first = DecodedCode("first", "QR_CODE")
    val second = DecodedCode("second", "CODE_128")
    assertNull(gate.observe(first))
    assertNull(gate.observe(second))
    assertEquals(second, gate.observe(second))
  }
}
