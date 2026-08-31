package com.missionalarm.core.data

import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.WallClock

data class JournalImportResult(
  val imported: Int,
  val quarantined: Int,
  val deferred: Int,
)

class DirectBootJournalImporter(
  private val bootDatabase: DirectBootDatabase,
  private val coordinator: TriggeredInstanceCoordinator,
  private val canonicalDatabase: MissionAlarmDatabase,
  private val effectIdGenerator: EffectIdGenerator,
  private val importedAtMs: () -> Long,
) {
  fun importPending(timingOverrides: Map<String, TriggerTiming> = emptyMap()): JournalImportResult {
    var imported = 0
    var quarantined = 0
    var deferred = 0
    bootDatabase.directBootDao().findPendingJournal()
      .sortedWith(
        compareBy<BootJournalEntity> { eventPriority(it.eventType) }
          .thenBy { it.occurredAtMs }
          .thenBy { it.id },
      )
      .forEach { entry ->
        when (importEntry(entry, timingOverrides)) {
          ImportDisposition.IMPORTED -> imported += 1
          ImportDisposition.QUARANTINED -> quarantined += 1
          ImportDisposition.DEFERRED -> deferred += 1
        }
      }
    return JournalImportResult(imported, quarantined, deferred)
  }

  private fun importEntry(
    entry: BootJournalEntity,
    timingOverrides: Map<String, TriggerTiming>,
  ): ImportDisposition =
    try {
      val occurrenceId = OccurrenceId.parse(entry.occurrenceId)
      when (entry.eventType) {
        "TRIGGERED" -> importTriggered(entry, occurrenceId, timingOverrides)
        "EMERGENCY_DISMISSED" -> importEmergencyDismissed(entry, occurrenceId)
        "RUNTIME_STOPPED" -> importRuntimeStopped(entry, occurrenceId)
        else -> ImportDisposition.DEFERRED
      }
    } catch (_: IllegalArgumentException) {
      quarantine(entry.id, "INVALID_JOURNAL_IDENTITY")
      ImportDisposition.QUARANTINED
    }

  private fun importTriggered(
    entry: BootJournalEntity,
    occurrenceId: OccurrenceId,
    timingOverrides: Map<String, TriggerTiming>,
  ): ImportDisposition =
    try {
      coordinator.getOrCreate(
        occurrenceId,
        timingOverrides[entry.occurrenceId] ?: TriggerTiming(entry.occurredAtMs),
      )
      markImported(entry.id)
      ImportDisposition.IMPORTED
    } catch (_: TriggeredInstanceException.OccurrenceNotFound) {
      quarantine(entry.id, "OCCURRENCE_NOT_FOUND")
      ImportDisposition.QUARANTINED
    } catch (_: TriggeredInstanceException.OccurrenceNotTriggerable) {
      quarantine(entry.id, "OCCURRENCE_NOT_TRIGGERABLE")
      ImportDisposition.QUARANTINED
    } catch (_: TriggeredInstanceException.AlarmUnavailable) {
      quarantine(entry.id, "ALARM_UNAVAILABLE")
      ImportDisposition.QUARANTINED
    }

  private fun importEmergencyDismissed(
    entry: BootJournalEntity,
    occurrenceId: OccurrenceId,
  ): ImportDisposition {
    if (canonicalDatabase.runtimeDao().findOccurrenceById(occurrenceId.value) == null) {
      quarantine(entry.id, "OCCURRENCE_NOT_FOUND")
      return ImportDisposition.QUARANTINED
    }
    val instance = canonicalDatabase.runtimeDao().findInstanceByOccurrence(occurrenceId.value)
      ?: return ImportDisposition.DEFERRED
    return try {
      EmergencyDismissCoordinator(
        canonicalDatabase,
        WallClock { entry.occurredAtMs },
        effectIdGenerator,
      ).dismiss(instance.id)
      markImported(entry.id)
      ImportDisposition.IMPORTED
    } catch (_: EmergencyDismissException.InstanceNotFound) {
      ImportDisposition.DEFERRED
    } catch (_: EmergencyDismissException.NotAttended) {
      quarantine(entry.id, "INSTANCE_NOT_ATTENDED")
      ImportDisposition.QUARANTINED
    } catch (_: EmergencyDismissException.AlreadyTerminal) {
      quarantine(entry.id, "TERMINAL_RESULT_CONFLICT")
      ImportDisposition.QUARANTINED
    } catch (_: EmergencyDismissException.StateConflict) {
      ImportDisposition.DEFERRED
    }
  }

  private fun importRuntimeStopped(
    entry: BootJournalEntity,
    occurrenceId: OccurrenceId,
  ): ImportDisposition {
    if (canonicalDatabase.runtimeDao().findOccurrenceById(occurrenceId.value) == null) {
      quarantine(entry.id, "OCCURRENCE_NOT_FOUND")
      return ImportDisposition.QUARANTINED
    }
    val instance = canonicalDatabase.runtimeDao().findInstanceByOccurrence(occurrenceId.value)
      ?: return ImportDisposition.DEFERRED
    if (instance.runtimeState != "TERMINAL") return ImportDisposition.DEFERRED
    markImported(entry.id)
    return ImportDisposition.IMPORTED
  }

  private fun eventPriority(eventType: String) = when (eventType) {
    "TRIGGERED" -> 0
    "EMERGENCY_DISMISSED" -> 1
    "RUNTIME_STOPPED" -> 2
    else -> 3
  }

  private fun markImported(id: String) {
    check(bootDatabase.directBootDao().markJournalImported(id, checkedNow()) == 1)
  }

  private fun quarantine(id: String, reason: String) {
    check(bootDatabase.directBootDao().quarantineJournal(id, reason) == 1)
  }

  private fun checkedNow() = importedAtMs().also { require(it >= 0) }

  private enum class ImportDisposition {
    IMPORTED,
    QUARANTINED,
    DEFERRED,
  }
}
