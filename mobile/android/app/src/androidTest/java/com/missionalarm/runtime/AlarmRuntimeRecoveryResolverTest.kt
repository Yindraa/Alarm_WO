package com.missionalarm.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmRuntimeRecoveryResolverTest {
  @Test
  fun stickyRestartWithoutIntentRecoversCanonicalAttendedInstance() {
    assertEquals(ACTIVE, AlarmRuntimeRecoveryResolver.resolve(null, null, ACTIVE))
  }

  @Test
  fun staleTerminalRequestFallsBackToCurrentAttendedInstance() {
    assertEquals(
      ACTIVE,
      AlarmRuntimeRecoveryResolver.resolve(OLD_ID, TERMINAL_OLD, ACTIVE),
    )
  }

  @Test
  fun missingOrQueuedRequestFallsBackToCurrentAttendedInstance() {
    assertEquals(ACTIVE, AlarmRuntimeRecoveryResolver.resolve(OLD_ID, null, ACTIVE))
    assertEquals(ACTIVE, AlarmRuntimeRecoveryResolver.resolve(OLD_ID, QUEUED_OLD, ACTIVE))
  }

  @Test
  fun eligibleRequestedOwnerIsRetained() {
    assertEquals(ACTIVE, AlarmRuntimeRecoveryResolver.resolve(ACTIVE_ID, ACTIVE, ACTIVE))
  }

  @Test
  fun noEligibleCanonicalOwnerReturnsNull() {
    assertNull(AlarmRuntimeRecoveryResolver.resolve(null, null, null))
    assertNull(AlarmRuntimeRecoveryResolver.resolve(OLD_ID, TERMINAL_OLD, QUEUED_OLD))
  }

  private companion object {
    const val ACTIVE_ID = "92d9035c-adfe-422e-949b-b96877cec786"
    const val OLD_ID = "14d0ccba-72ea-4fef-b49b-845725a45604"
    val ACTIVE = RuntimeOwnerCandidate(ACTIVE_ID, "TRIGGERED", 1)
    val TERMINAL_OLD = RuntimeOwnerCandidate(OLD_ID, "TERMINAL", null)
    val QUEUED_OLD = RuntimeOwnerCandidate(OLD_ID, "PENDING_ATTENTION", null)
  }
}
