package com.missionalarm.scheduling

import android.content.Context
import com.missionalarm.core.data.CurrentZoneProvider
import com.missionalarm.core.data.AlarmScheduleReconciler
import com.missionalarm.core.data.DirectBootDatabaseFactory
import com.missionalarm.core.data.DirectBootJournalImporter
import com.missionalarm.core.data.DirectBootMirrorEffectRunner
import com.missionalarm.core.data.EffectIdGenerator
import com.missionalarm.core.data.InstanceIdGenerator
import com.missionalarm.core.data.LeaseOwnerGenerator
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import com.missionalarm.core.data.OccurrenceIdGenerator
import com.missionalarm.core.data.PresentationEffectRunner
import com.missionalarm.core.data.RoomDirectBootMirrorStore
import com.missionalarm.core.data.RuntimeEffectRunner
import com.missionalarm.core.data.RuntimeStopEffectRunner
import com.missionalarm.core.data.SchedulingEffectRunner
import com.missionalarm.core.data.SchedulingCapabilityReconciler
import com.missionalarm.core.data.TriggerTiming
import com.missionalarm.core.data.TriggeredInstanceCoordinator
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.WallClock
import com.missionalarm.runtime.AndroidAlarmRuntimeStarter
import com.missionalarm.runtime.AndroidAlarmHostPresenter
import com.missionalarm.runtime.AndroidAlarmRuntimeStopper
import java.time.ZoneId
import java.util.UUID

object UnlockedAlarmReconciler {
  fun reconcile(
    context: Context,
    timingOverrides: Map<String, TriggerTiming> = emptyMap(),
    scheduleReconciliationId: String? = null,
    exactAlarmCapabilityGranted: Boolean? = null,
    capabilityObservationId: String? = null,
  ) {
    val canonical = MissionAlarmDatabaseFactory.persistent(context)
    val boot = DirectBootDatabaseFactory.persistent(context)
    try {
      val clock = WallClock { System.currentTimeMillis() }
      val effectIdGenerator = EffectIdGenerator { UUID.randomUUID().toString() }
      val coordinator = TriggeredInstanceCoordinator(
        database = canonical,
        wallClock = clock,
        currentZoneProvider = CurrentZoneProvider { ZoneId.systemDefault() },
        instanceIdGenerator = InstanceIdGenerator { UUID.randomUUID().toString() },
        occurrenceIdGenerator = OccurrenceIdGenerator {
          OccurrenceId.parse(UUID.randomUUID().toString())
        },
        effectIdGenerator = effectIdGenerator,
      )
      DirectBootJournalImporter(
        boot,
        coordinator,
        canonical,
        effectIdGenerator,
      ) { System.currentTimeMillis() }
        .importPending(timingOverrides)

      if (scheduleReconciliationId != null) {
        val nowMs = System.currentTimeMillis()
        canonical.runtimeDao().findDueOccurrenceIds(nowMs).forEach { occurrenceId ->
          coordinator.getOrCreate(
            OccurrenceId.parse(occurrenceId),
            TriggerTiming(occurredAtMs = nowMs),
          )
        }
        AlarmScheduleReconciler(
          database = canonical,
          wallClock = clock,
          currentZoneProvider = CurrentZoneProvider { ZoneId.systemDefault() },
          occurrenceIdGenerator = OccurrenceIdGenerator {
            OccurrenceId.parse(UUID.randomUUID().toString())
          },
          effectIdGenerator = effectIdGenerator,
        ).reconcile(scheduleReconciliationId)
      }

      if (exactAlarmCapabilityGranted != null) {
        SchedulingCapabilityReconciler(
          canonical,
          clock,
          effectIdGenerator,
        ).observe(
          exactAlarmCapabilityGranted,
          checkNotNull(capabilityObservationId),
        )
      }

      val owner = LeaseOwnerGenerator { UUID.randomUUID().toString() }
      drainFully { RuntimeStopEffectRunner(
        canonical,
        clock,
        owner,
        AndroidAlarmRuntimeStopper(context),
      ).drain() }
      drainFully { RuntimeEffectRunner(
        canonical,
        clock,
        owner,
        AndroidAlarmRuntimeStarter(context),
      ).drain() }
      drainFully { PresentationEffectRunner(
        canonical,
        clock,
        owner,
        AndroidAlarmHostPresenter(context),
      ).drain() }
      drainFully { SchedulingEffectRunner(
        canonical,
        clock,
        owner,
        AndroidExactAlarmScheduler(context),
      ).drain() }
      drainFully { DirectBootMirrorEffectRunner(
        canonical,
        clock,
        owner,
        RoomDirectBootMirrorStore(boot),
      ).drain() }
    } finally {
      boot.close()
      canonical.close()
    }
  }

  private fun drainFully(drainBatch: () -> Int) {
    repeat(MAX_DRAIN_BATCHES) {
      if (drainBatch() == 0) return
    }
    error("reconciliation effect drain exceeded safety bound")
  }

  private const val MAX_DRAIN_BATCHES = 64
}
