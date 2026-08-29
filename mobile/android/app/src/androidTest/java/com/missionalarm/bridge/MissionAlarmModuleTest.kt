package com.missionalarm.bridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.PromiseImpl
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
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

  @Before
  fun setUp() {
    val application = ApplicationProvider.getApplicationContext<android.content.Context>()
    application.deleteDatabase(DATABASE_NAME)
    context = BridgeReactContext(application)
    module = MissionAlarmModule(context) { true }
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
    assertEquals("PENDING", alarm.getString("scheduleHealth"))
    assertTrue(alarm.hasKey("nextOccurrenceAtUtcMs") && !alarm.isNull("nextOccurrenceAtUtcMs"))
  }

  @Test
  fun missingCriticalCapabilityRejectsEnableWithoutMutatingDraft() {
    module.invalidate()
    module = MissionAlarmModule(context) { false }
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
    module = MissionAlarmModule(context) { false }
    val input = enable("not-an-alarm-id")

    val rejected = invoke { promise -> module.enableAlarm(input, promise) }

    assertEquals("INVALID_ARGUMENT", rejected.error?.getString("code"))
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

  private data class PromiseResult(val value: Any?, val error: ReadableMap?)

  private companion object {
    const val DATABASE_NAME = "mission-alarm.db"
    const val COMMAND_ID = "126baf63-80fb-4449-89ac-37667b33ff44"
    const val ENABLE_COMMAND_ID = "dc1457ab-ef8d-49c1-a67c-4cafc5063c22"
  }
}
