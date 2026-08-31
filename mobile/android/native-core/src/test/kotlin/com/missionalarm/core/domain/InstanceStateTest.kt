package com.missionalarm.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InstanceStateTest {
  @Test
  fun `success requires in-progress mission and completed verified progress`() {
    val state = InstanceState.triggered().lockMission().startMission()

    assertThrows(IllegalStateException::class.java) {
      state.completeVerified(MissionProgress.restore(target = 3, committed = 2))
    }

    val terminal = state.completeVerified(MissionProgress.restore(target = 3, committed = 3))
    assertEquals(InstanceRuntimeState.TERMINAL, terminal.runtime)
    assertEquals(TerminalResult.SUCCESS, terminal.terminalResult)
  }

  @Test
  fun `recovery never implies success and retains emergency path`() {
    val recovery = InstanceState.triggered()
      .lockMission()
      .startMission()
      .requireRecovery()

    assertThrows(IllegalStateException::class.java) {
      recovery.completeVerified(MissionProgress.restore(target = 1, committed = 1))
    }

    assertEquals(TerminalResult.EMERGENCY_DISMISSED, recovery.emergencyDismiss().terminalResult)
  }

  @Test
  fun `terminal result cannot be changed`() {
    val terminal = InstanceState.triggered().lockMission().emergencyDismiss()

    assertThrows(IllegalStateException::class.java) { terminal.failSafely() }
    assertThrows(IllegalStateException::class.java) { terminal.lockMission() }
  }

  @Test
  fun `attended recovery shell can emergency dismiss before mission starts`() {
    val terminal = InstanceState.triggered().emergencyDismiss()

    assertEquals(InstanceRuntimeState.TERMINAL, terminal.runtime)
    assertEquals(TerminalResult.EMERGENCY_DISMISSED, terminal.terminalResult)
  }

  @Test
  fun `queued instance follows FIFO attention transition shape`() {
    val locked = InstanceState.triggered().queue().lockMission()

    assertEquals(InstanceRuntimeState.MISSION_LOCKED, locked.runtime)
  }
}
