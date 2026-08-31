package com.missionalarm.bridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.PromiseImpl
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.missionalarm.core.data.ExactAlarmScheduler
import com.missionalarm.core.data.DirectBootMirrorStore
import com.missionalarm.core.data.CurrentZoneProvider
import com.missionalarm.core.data.EffectIdGenerator
import com.missionalarm.core.data.InstanceIdGenerator
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import com.missionalarm.core.data.OccurrenceIdGenerator
import com.missionalarm.core.data.TriggeredInstanceCoordinator
import com.missionalarm.core.data.TriggerTiming
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.WallClock
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MissionAlarmModuleTest {
  private lateinit var context: ReactApplicationContext
  private lateinit var module: MissionAlarmModule
  private val presentedInstances = mutableListOf<String>()

  @Before
  fun setUp() {
    val application = ApplicationProvider.getApplicationContext<android.content.Context>()
    application.deleteDatabase(DATABASE_NAME)
    presentedInstances.clear()
    context = BridgeReactContext(application)
    module = testModule(capability = true)
  }

  @After
  fun tearDown() {
    module.invalidate()
    context.applicationContext.deleteDatabase(DATABASE_NAME)
  }

  @Test
  fun saveThenQueryRoundTripsThroughGeneratedTurboModuleSurface() {
    val saved = invoke { promise -> module.saveAlarmConfiguration(draft(), promise) }
    assertNull(saved.error)
    val ack = saved.value as ReadableMap
    val alarmId = checkNotNull(ack.getString("aggregateId"))
    assertEquals(1, ack.getInt("revision"))
    assertFalse(ack.getBoolean("replayed"))

    val queried = invoke { promise -> module.getAlarmEditorSnapshot(1.0, alarmId, promise) }
    assertNull(queried.error)
    val snapshot = queried.value as ReadableMap
    val alarm = checkNotNull(snapshot.getMap("alarm"))
    assertFalse(snapshot.getBoolean("isNewDraft"))
    assertEquals("Wake up", alarm.getString("label"))
    assertEquals("MATH", alarm.getMap("mission")!!.getString("missionType"))
    assertEquals("DISABLED", alarm.getString("scheduleHealth"))
  }

  @Test
  fun homeSnapshotReturnsPersistedAlarmFromCanonicalDatabase() {
    val saved = invoke { promise -> module.saveAlarmConfiguration(draft(), promise) }
    val alarmId = (saved.value as ReadableMap).getString("aggregateId")!!

    val queried = invoke { promise -> module.getHomeSnapshot(1.0, promise) }

    assertNull(queried.error)
    val snapshot = queried.value as ReadableMap
    val alarms = checkNotNull(snapshot.getArray("alarms"))
    assertEquals(1, alarms.size())
    val alarm = checkNotNull(alarms.getMap(0))
    assertEquals(alarmId, alarm.getString("id"))
    assertEquals("Wake up", alarm.getString("label"))
    assertFalse(alarm.getBoolean("enabled"))
    assertEquals("DISABLED", alarm.getString("scheduleHealth"))
    assertTrue(snapshot.isNull("active"))
    assertEquals(0, checkNotNull(snapshot.getArray("recentHistory")).size())
  }

  @Test
  fun reusedCommandIdMapsToStableContractError() {
    assertNull(invoke { promise -> module.saveAlarmConfiguration(draft(), promise) }.error)
    val changed = draft().apply { putString("label", "Different") }

    val rejected = invoke { promise -> module.saveAlarmConfiguration(changed, promise) }

    assertNull(rejected.value)
    assertEquals("IDEMPOTENCY_KEY_REUSED", rejected.error?.getString("code"))
  }

  @Test
  fun enableThenQueryExposesPersistedPendingSchedule() {
    val saved = invoke { promise -> module.saveAlarmConfiguration(draft(), promise) }
    val alarmId = (saved.value as ReadableMap).getString("aggregateId")!!

    val enabled = invoke { promise -> module.enableAlarm(enable(alarmId), promise) }

    assertNull(enabled.error)
    val ack = enabled.value as ReadableMap
    assertEquals(2, ack.getInt("revision"))
    assertEquals(alarmId, ack.getString("aggregateId"))
    val queried = invoke { promise -> module.getAlarmEditorSnapshot(1.0, alarmId, promise) }
    val alarm = (queried.value as ReadableMap).getMap("alarm")!!
    assertTrue(alarm.getBoolean("enabled"))
    assertEquals("HEALTHY", alarm.getString("scheduleHealth"))
    assertTrue(alarm.hasKey("nextOccurrenceAtUtcMs") && !alarm.isNull("nextOccurrenceAtUtcMs"))
  }

  @Test
  fun missingCriticalCapabilityRejectsEnableWithoutMutatingDraft() {
    module.invalidate()
    module = testModule(capability = false)
    val saved = invoke { promise -> module.saveAlarmConfiguration(draft(), promise) }
    val alarmId = (saved.value as ReadableMap).getString("aggregateId")!!

    val rejected = invoke { promise -> module.enableAlarm(enable(alarmId), promise) }

    assertEquals("CAPABILITY_REQUIRED", rejected.error?.getString("code"))
    val queried = invoke { promise -> module.getAlarmEditorSnapshot(1.0, alarmId, promise) }
    assertFalse((queried.value as ReadableMap).getMap("alarm")!!.getBoolean("enabled"))
  }

  @Test
  fun malformedEnableInputIsRejectedBeforeCapabilityInspection() {
    module.invalidate()
    module = testModule(capability = false)
    val input = enable("not-an-alarm-id")

    val rejected = invoke { promise -> module.enableAlarm(input, promise) }

    assertEquals("INVALID_ARGUMENT", rejected.error?.getString("code"))
  }

  @Test
  fun editEnabledThenDisableAndDeleteRoundTripsThroughContract() {
    val saved = invoke { promise -> module.saveAlarmConfiguration(draft(), promise) }
    val alarmId = (saved.value as ReadableMap).getString("aggregateId")!!
    assertNull(invoke { promise -> module.enableAlarm(enable(alarmId), promise) }.error)

    val edit = draft().apply {
      putString("commandId", EDIT_COMMAND_ID)
      putString("alarmId", alarmId)
      putInt("expectedRevision", 2)
      putString("label", "Updated")
      putInt("localTimeMinutes", 480)
      putInt("target", 5)
    }
    val edited = invoke { promise -> module.saveAlarmConfiguration(edit, promise) }
    assertNull(edited.error)
    assertEquals(3, (edited.value as ReadableMap).getInt("revision"))
    val editedAlarm = (
      invoke { promise -> module.getAlarmEditorSnapshot(1.0, alarmId, promise) }.value as ReadableMap
      ).getMap("alarm")!!
    assertTrue(editedAlarm.getBoolean("enabled"))
    assertEquals("Updated", editedAlarm.getString("label"))
    assertEquals("HEALTHY", editedAlarm.getString("scheduleHealth"))

    val disabled = invoke { promise ->
      module.disableAlarm(aggregateCommand(DISABLE_COMMAND_ID, alarmId, 3), promise)
    }
    assertNull(disabled.error)
    assertEquals(4, (disabled.value as ReadableMap).getInt("revision"))
    val disabledAlarm = (
      invoke { promise -> module.getAlarmEditorSnapshot(1.0, alarmId, promise) }.value as ReadableMap
      ).getMap("alarm")!!
    assertFalse(disabledAlarm.getBoolean("enabled"))
    assertEquals("DISABLED", disabledAlarm.getString("scheduleHealth"))

    val deleted = invoke { promise ->
      module.deleteAlarm(aggregateCommand(DELETE_COMMAND_ID, alarmId, 4), promise)
    }
    assertNull(deleted.error)
    assertEquals(5, (deleted.value as ReadableMap).getInt("revision"))
    val missing = invoke { promise -> module.getAlarmEditorSnapshot(1.0, alarmId, promise) }
    assertEquals("NOT_FOUND", missing.error?.getString("code"))
  }

  @Test
  fun activeRuntimeQueryReturnsExplicitEmptySnapshot() {
    val queried = invoke { promise -> module.getActiveRuntimeSnapshot(1.0, promise) }

    assertNull(queried.error)
    val snapshot = queried.value as ReadableMap
    assertFalse(snapshot.getBoolean("found"))
    assertTrue(snapshot.isNull("instanceId"))
    assertTrue(snapshot.isNull("revision"))
    assertEquals(0, snapshot.getInt("queuedCount"))
  }

  @Test
  fun activeRuntimeLaunchIsRevisionCheckedAndProcessDebounced() {
    val saved = invoke { promise -> module.saveAlarmConfiguration(draft(), promise) }
    val alarmId = (saved.value as ReadableMap).getString("aggregateId")!!
    assertNull(invoke { promise -> module.enableAlarm(enable(alarmId), promise) }.error)
    seedTriggeredInstance(alarmId)

    val queried = invoke { promise -> module.getActiveRuntimeSnapshot(1.0, promise) }
    assertNull(queried.error)
    val snapshot = queried.value as ReadableMap
    assertTrue(snapshot.getBoolean("found"))
    assertEquals(INSTANCE_ID, snapshot.getString("instanceId"))
    assertEquals(1, snapshot.getInt("revision"))
    assertEquals("MATH", snapshot.getString("missionType"))
    assertEquals(3, snapshot.getMap("mathQuestion")!!.getInt("total"))
    val home = invoke { promise -> module.getHomeSnapshot(1.0, promise) }
    assertNull(home.error)
    val activeSummary = (home.value as ReadableMap).getMap("active")!!
    assertEquals(INSTANCE_ID, activeSummary.getString("instanceId"))
    assertEquals(1, activeSummary.getInt("revision"))

    val stale = invoke { promise ->
      module.launchActiveInstance(launch(INSTANCE_ID, 2), promise)
    }
    assertEquals("CONFLICT_REVISION", stale.error?.getString("code"))
    assertTrue(presentedInstances.isEmpty())

    val first = invoke { promise ->
      module.launchActiveInstance(launch(INSTANCE_ID, 1), promise)
    }
    val replay = invoke { promise ->
      module.launchActiveInstance(launch(INSTANCE_ID, 1), promise)
    }
    assertNull(first.error)
    assertNull(replay.error)
    assertEquals(
      (first.value as ReadableMap).getString("sessionId"),
      (replay.value as ReadableMap).getString("sessionId"),
    )
    assertEquals(listOf(INSTANCE_ID), presentedInstances)
  }

  private fun invoke(call: (PromiseImpl) -> Unit): PromiseResult {
    val latch = CountDownLatch(1)
    var value: Any? = null
    var error: ReadableMap? = null
    val promise = PromiseImpl(
      Callback { args ->
        value = args.firstOrNull()
        latch.countDown()
      },
      Callback { args ->
        error = args.firstOrNull() as ReadableMap
        latch.countDown()
      },
    )
    call(promise)
    assertTrue("native promise timed out", latch.await(10, TimeUnit.SECONDS))
    return PromiseResult(value, error)
  }

  private fun draft() = Arguments.createMap().apply {
    putInt("contractVersion", 1)
    putString("commandId", COMMAND_ID)
    putNull("alarmId")
    putNull("expectedRevision")
    putString("label", "Wake up")
    putString("scheduleKind", "WEEKLY")
    putInt("localTimeMinutes", 420)
    putInt("repeatDaysMask", 31)
    putNull("oneTimeAtUtcMs")
    putString("configuredTimezoneId", "Asia/Makassar")
    putString("soundId", "classic")
    putString("missionType", "MATH")
    putInt("target", 3)
    putNull("pushupProfileVersion")
    putInt("mathOperationsMask", 7)
    putString("mathGeneratorVersion", "math-v1")
  }

  private fun enable(alarmId: String) = Arguments.createMap().apply {
    putInt("contractVersion", 1)
    putString("commandId", ENABLE_COMMAND_ID)
    putString("aggregateId", alarmId)
    putInt("expectedRevision", 1)
  }

  private fun aggregateCommand(commandId: String, alarmId: String, revision: Int) =
    Arguments.createMap().apply {
      putInt("contractVersion", 1)
      putString("commandId", commandId)
      putString("aggregateId", alarmId)
      putInt("expectedRevision", revision)
    }

  private fun launch(instanceId: String, revision: Int) = Arguments.createMap().apply {
    putInt("contractVersion", 1)
    putString("requestId", LAUNCH_REQUEST_ID)
    putString("aggregateId", instanceId)
    putInt("expectedRevision", revision)
  }

  private fun seedTriggeredInstance(alarmId: String) {
    val database = MissionAlarmDatabaseFactory.persistent(context.applicationContext)
    try {
      val occurrence = checkNotNull(database.runtimeDao().findNextOccurrence(alarmId))
      var effectSequence = 0
      TriggeredInstanceCoordinator(
        database = database,
        wallClock = WallClock { NOW_MS },
        currentZoneProvider = CurrentZoneProvider { ZoneId.of("UTC") },
        instanceIdGenerator = InstanceIdGenerator { INSTANCE_ID },
        occurrenceIdGenerator = OccurrenceIdGenerator {
          OccurrenceId.parse(NEXT_OCCURRENCE_ID)
        },
        effectIdGenerator = EffectIdGenerator { "test-effect-${++effectSequence}" },
      ).getOrCreate(
        OccurrenceId.parse(occurrence.id),
        TriggerTiming(NOW_MS),
      )
    } finally {
      database.close()
    }
  }

  private fun testModule(capability: Boolean) = MissionAlarmModule(
    reactContext = context,
    criticalSchedulingCapabilityOverride = { capability },
    exactAlarmSchedulerOverride = ExactAlarmScheduler { _, _ -> },
    directBootMirrorStoreOverride = DirectBootMirrorStore { _, _ -> },
    alarmHostPresenterOverride = { instanceId -> presentedInstances += instanceId },
  )

  private data class PromiseResult(val value: Any?, val error: ReadableMap?)

  private companion object {
    const val DATABASE_NAME = "mission-alarm.db"
    const val COMMAND_ID = "126baf63-80fb-4449-89ac-37667b33ff44"
    const val ENABLE_COMMAND_ID = "dc1457ab-ef8d-49c1-a67c-4cafc5063c22"
    const val EDIT_COMMAND_ID = "725d43c5-ac42-42df-bade-a752b3f532ff"
    const val DISABLE_COMMAND_ID = "89927654-58ae-47d4-aaf8-50122651f698"
    const val DELETE_COMMAND_ID = "957c85c3-1292-46ec-a714-35a7bded0781"
    const val LAUNCH_REQUEST_ID = "4a0977be-9c83-46d2-8b55-d605b389f0cb"
    const val INSTANCE_ID = "95bc545a-c392-4c47-b5b5-d69eb0bb037d"
    const val NEXT_OCCURRENCE_ID = "056b5bed-8c7e-46b4-bdb1-b66337bf9e92"
    const val NOW_MS = 1_777_507_200_000L
  }
}
