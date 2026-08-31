package com.missionalarm.scheduling

import android.content.Context
import com.missionalarm.core.data.CurrentZoneProvider
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
  fun reconcile(context: Context, timingOverrides: Map<String, TriggerTiming> = emptyMap()) {
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

      val owner = LeaseOwnerGenerator { UUID.randomUUID().toString() }
      RuntimeStopEffectRunner(
        canonical,
        clock,
        owner,
        AndroidAlarmRuntimeStopper(context),
      ).drain()
      RuntimeEffectRunner(
        canonical,
        clock,
        owner,
        AndroidAlarmRuntimeStarter(context),
      ).drain()
      PresentationEffectRunner(
        canonical,
        clock,
        owner,
        AndroidAlarmHostPresenter(context),
      ).drain()
      SchedulingEffectRunner(
        canonical,
        clock,
        owner,
        AndroidExactAlarmScheduler(context),
      ).drain()
      DirectBootMirrorEffectRunner(
        canonical,
        clock,
        owner,
        RoomDirectBootMirrorStore(boot),
      ).drain()
    } finally {
      boot.close()
      canonical.close()
    }
  }
}
