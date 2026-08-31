package com.missionalarm.bridge

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReactApplicationContext
import com.missionalarm.app.BuildConfig
import com.missionalarm.core.data.AlarmDraftRepository
import com.missionalarm.core.data.AlarmDraftRepositoryException
import com.missionalarm.core.data.AlarmIdGenerator
import com.missionalarm.core.data.AlarmSchedulingRepository
import com.missionalarm.core.data.AlarmSchedulingRepositoryException
import com.missionalarm.core.data.AlarmRuntimeStarter
import com.missionalarm.core.data.AlarmRuntimeStopper
import com.missionalarm.core.data.AlarmHostPresenter
import com.missionalarm.core.data.AlarmHistoryEntity
import com.missionalarm.core.data.AlarmWithMission
import com.missionalarm.core.data.ActiveRuntimeSnapshot
import com.missionalarm.core.data.CurrentZoneProvider
import com.missionalarm.core.data.DirectBootDatabaseFactory
import com.missionalarm.core.data.DirectBootMirrorEffectRunner
import com.missionalarm.core.data.DirectBootMirrorStore
import com.missionalarm.core.data.EffectIdGenerator
import com.missionalarm.core.data.EnableAlarmCommand
import com.missionalarm.core.data.ExactAlarmScheduler
import com.missionalarm.core.data.LeaseOwnerGenerator
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import com.missionalarm.core.data.OccurrenceIdGenerator
import com.missionalarm.core.data.PresentationEffectRunner
import com.missionalarm.core.data.RoomDirectBootMirrorStore
import com.missionalarm.core.data.RuntimeEffectRunner
import com.missionalarm.core.data.RuntimeStopEffectRunner
import com.missionalarm.core.data.SaveAlarmDraftCommand
import com.missionalarm.core.data.SchedulingEffectRunner
import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.MissionType
import com.missionalarm.core.domain.OccurrenceId
import com.missionalarm.core.domain.Revision
import com.missionalarm.specs.NativeMissionAlarmSpec
import com.missionalarm.scheduling.AndroidExactAlarmScheduler
import com.missionalarm.runtime.AndroidAlarmRuntimeStarter
import com.missionalarm.runtime.AndroidAlarmHostPresenter
import com.missionalarm.runtime.AndroidAlarmRuntimeStopper
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors

class MissionAlarmModule(
  private val reactContext: ReactApplicationContext,
  private val criticalSchedulingCapabilityOverride: (() -> Boolean)? = null,
  private val exactAlarmSchedulerOverride: ExactAlarmScheduler? = null,
  private val directBootMirrorStoreOverride: DirectBootMirrorStore? = null,
  private val alarmRuntimeStarterOverride: AlarmRuntimeStarter? = null,
  private val alarmHostPresenterOverride: AlarmHostPresenter? = null,
  private val alarmRuntimeStopperOverride: AlarmRuntimeStopper? = null,
) : NativeMissionAlarmSpec(reactContext) {
  private val launchReceipts = mutableMapOf<String, LaunchReceipt>()
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "mission-alarm-native").apply { isDaemon = true }
  }
  private val databaseDelegate = lazy {
    MissionAlarmDatabaseFactory.persistent(reactContext.applicationContext)
  }
  private val repositoryDelegate = lazy {
    AlarmDraftRepository(
      database = databaseDelegate.value,
      wallClock = { System.currentTimeMillis() },
      alarmIdGenerator = AlarmIdGenerator { AlarmId.parse(UUID.randomUUID().toString()) },
    )
  }
  private val schedulingRepositoryDelegate = lazy {
    AlarmSchedulingRepository(
      database = databaseDelegate.value,
      wallClock = { System.currentTimeMillis() },
      currentZoneProvider = CurrentZoneProvider { ZoneId.systemDefault() },
      occurrenceIdGenerator = OccurrenceIdGenerator {
        OccurrenceId.parse(UUID.randomUUID().toString())
      },
      effectIdGenerator = EffectIdGenerator { UUID.randomUUID().toString() },
    )
  }
  private val scheduleEffectRunnerDelegate = lazy {
    SchedulingEffectRunner(
      database = databaseDelegate.value,
      wallClock = { System.currentTimeMillis() },
      leaseOwnerGenerator = LeaseOwnerGenerator { UUID.randomUUID().toString() },
      scheduler = exactAlarmSchedulerOverride
        ?: AndroidExactAlarmScheduler(reactContext.applicationContext),
    )
  }
  private val directBootDatabaseDelegate = lazy {
    DirectBootDatabaseFactory.persistent(reactContext.applicationContext)
  }
  private val mirrorEffectRunnerDelegate = lazy {
    DirectBootMirrorEffectRunner(
      database = databaseDelegate.value,
      wallClock = { System.currentTimeMillis() },
      leaseOwnerGenerator = LeaseOwnerGenerator { UUID.randomUUID().toString() },
      mirrorStore = directBootMirrorStoreOverride
        ?: RoomDirectBootMirrorStore(directBootDatabaseDelegate.value),
    )
  }
  private val runtimeEffectRunnerDelegate = lazy {
    RuntimeEffectRunner(
      database = databaseDelegate.value,
      wallClock = { System.currentTimeMillis() },
      leaseOwnerGenerator = LeaseOwnerGenerator { UUID.randomUUID().toString() },
      runtimeStarter = alarmRuntimeStarterOverride
        ?: AndroidAlarmRuntimeStarter(reactContext.applicationContext),
    )
  }
  private val presentationEffectRunnerDelegate = lazy {
    PresentationEffectRunner(
      database = databaseDelegate.value,
      wallClock = { System.currentTimeMillis() },
      leaseOwnerGenerator = LeaseOwnerGenerator { UUID.randomUUID().toString() },
      presenter = alarmHostPresenterOverride
        ?: AndroidAlarmHostPresenter(reactContext.applicationContext),
    )
  }
  private val runtimeStopEffectRunnerDelegate = lazy {
    RuntimeStopEffectRunner(
      database = databaseDelegate.value,
      wallClock = { System.currentTimeMillis() },
      leaseOwnerGenerator = LeaseOwnerGenerator { UUID.randomUUID().toString() },
      runtimeStopper = alarmRuntimeStopperOverride
        ?: AndroidAlarmRuntimeStopper(reactContext.applicationContext),
    )
  }
  private val database by databaseDelegate
  private val repository by repositoryDelegate
  private val schedulingRepository by schedulingRepositoryDelegate
  private val scheduleEffectRunner by scheduleEffectRunnerDelegate
  private val mirrorEffectRunner by mirrorEffectRunnerDelegate
  private val runtimeEffectRunner by runtimeEffectRunnerDelegate
  private val presentationEffectRunner by presentationEffectRunnerDelegate
  private val runtimeStopEffectRunner by runtimeStopEffectRunnerDelegate

  override fun getName(): String = NAME

  override fun getContractInfo(promise: Promise) {
    promise.resolve(
      Arguments.createMap().apply {
        putInt("contractVersion", CONTRACT_VERSION)
        putInt("minimumClientContractVersion", MINIMUM_CLIENT_CONTRACT_VERSION)
        putString("moduleName", NAME)
        putString("nativeBuildVersion", BuildConfig.VERSION_NAME)
      },
    )
  }

  override fun saveAlarmConfiguration(input: ReadableMap, promise: Promise) {
    executor.execute {
      try {
        requireContractVersion(input.requiredInt("contractVersion"))
        val command = SaveAlarmDraftCommand(
          commandId = CommandId.parse(input.requiredString("commandId")),
          alarmId = input.nullableString("alarmId")?.let(AlarmId::parse),
          expectedRevision = input.nullableInt("expectedRevision")?.let(Revision::of),
          label = input.requiredString("label"),
          scheduleKind = input.requiredString("scheduleKind"),
          localTimeMinutes = input.requiredInt("localTimeMinutes"),
          repeatDaysMask = input.requiredInt("repeatDaysMask"),
          oneTimeAtUtcMs = input.nullableLong("oneTimeAtUtcMs"),
          configuredTimezoneId = input.requiredString("configuredTimezoneId"),
          soundId = input.requiredString("soundId"),
          missionType = MissionType.valueOf(input.requiredString("missionType")),
          target = input.requiredInt("target"),
          pushupProfileVersion = input.nullableString("pushupProfileVersion"),
          mathOperationsMask = input.nullableInt("mathOperationsMask"),
          mathGeneratorVersion = input.nullableString("mathGeneratorVersion"),
        )
        val ack = try {
          repository.save(command)
        } catch (_: AlarmDraftRepositoryException.EnabledEditUnsupported) {
          if (!hasCriticalSchedulingCapability()) throw CapabilityRequired()
          schedulingRepository.editEnabled(command)
        }
        drainReliabilityEffects()
        promise.resolve(
          Arguments.createMap().apply {
            putString("commandId", ack.commandId)
            putString("aggregateType", "ALARM")
            putString("aggregateId", ack.alarmId)
            putInt("revision", ack.revision)
            putDouble("appliedAtMs", ack.appliedAtMs.toDouble())
            putBoolean("replayed", ack.replayed)
          },
        )
      } catch (error: Throwable) {
        rejectMapped(promise, error)
      }
    }
  }

  override fun enableAlarm(input: ReadableMap, promise: Promise) {
    executor.execute {
      try {
        requireContractVersion(input.requiredInt("contractVersion"))
        val command = EnableAlarmCommand(
          commandId = CommandId.parse(input.requiredString("commandId")),
          alarmId = AlarmId.parse(input.requiredString("aggregateId")),
          expectedRevision = Revision.of(input.requiredInt("expectedRevision")),
        )
        if (!hasCriticalSchedulingCapability()) throw CapabilityRequired()
        val ack = schedulingRepository.enable(command)
        drainReliabilityEffects()
        promise.resolve(commandAck(ack.commandId, ack.alarmId, ack.revision, ack.appliedAtMs, ack.replayed))
      } catch (error: Throwable) {
        rejectMapped(promise, error)
      }
    }
  }

  override fun disableAlarm(input: ReadableMap, promise: Promise) {
    executor.execute {
      try {
        requireContractVersion(input.requiredInt("contractVersion"))
        val ack = schedulingRepository.disable(input.toAlarmAggregateCommand())
        drainReliabilityEffects()
        promise.resolve(commandAck(ack.commandId, ack.alarmId, ack.revision, ack.appliedAtMs, ack.replayed))
      } catch (error: Throwable) {
        rejectMapped(promise, error)
      }
    }
  }

  override fun deleteAlarm(input: ReadableMap, promise: Promise) {
    executor.execute {
      try {
        requireContractVersion(input.requiredInt("contractVersion"))
        val ack = schedulingRepository.delete(input.toAlarmAggregateCommand())
        drainReliabilityEffects()
        promise.resolve(commandAck(ack.commandId, ack.alarmId, ack.revision, ack.appliedAtMs, ack.replayed))
      } catch (error: Throwable) {
        rejectMapped(promise, error)
      }
    }
  }

  override fun getAlarmEditorSnapshot(
    contractVersion: Double,
    alarmId: String?,
    promise: Promise,
  ) {
    executor.execute {
      try {
        requireContractVersion(contractVersion.toContractInt())
        val parsedAlarmId = alarmId?.let(AlarmId::parse)
        drainReliabilityEffects()
        val stored = parsedAlarmId?.let(repository::find)
        if (parsedAlarmId != null && stored == null) throw AlarmDraftRepositoryException.NotFound()
        promise.resolve(editorSnapshot(stored))
      } catch (error: Throwable) {
        rejectMapped(promise, error)
      }
    }
  }

  override fun getHomeSnapshot(contractVersion: Double, promise: Promise) {
    executor.execute {
      try {
        requireContractVersion(contractVersion.toContractInt())
        val snapshot = database.runInTransaction<com.facebook.react.bridge.WritableMap> {
          homeSnapshot(
            database.alarmDao().findHomeAlarms(),
            database.runtimeDao().loadActiveRuntimeSnapshot(),
            database.runtimeDao().findRecentHistory(HOME_HISTORY_LIMIT),
          )
        }
        promise.resolve(snapshot)
      } catch (error: Throwable) {
        rejectMapped(promise, error)
      }
    }
  }

  override fun getActiveRuntimeSnapshot(contractVersion: Double, promise: Promise) {
    executor.execute {
      try {
        requireContractVersion(contractVersion.toContractInt())
        promise.resolve(activeRuntimeSnapshot(database.runtimeDao().loadActiveRuntimeSnapshot()))
      } catch (error: Throwable) {
        rejectMapped(promise, error)
      }
    }
  }

  override fun launchActiveInstance(input: ReadableMap, promise: Promise) {
    executor.execute {
      try {
        requireContractVersion(input.requiredInt("contractVersion"))
        val requestId = input.requiredString("requestId").also(UUID::fromString)
        val instanceId = input.requiredString("aggregateId").also(UUID::fromString)
        val expectedRevision = input.requiredInt("expectedRevision").also { require(it >= 1) }
        launchReceipts[requestId]?.let { receipt ->
          if (receipt.instanceId != instanceId || receipt.expectedRevision != expectedRevision) {
            throw LaunchRequestReused()
          }
          promise.resolve(launchAck(receipt))
          return@execute
        }
        val active = database.runtimeDao().loadActiveRuntimeSnapshot()
          ?: throw ActiveInstanceNotFound()
        if (active.instanceId != instanceId) throw ActiveInstanceNotFound()
        if (active.revision != expectedRevision) throw ActiveInstanceRevisionConflict()
        (alarmHostPresenterOverride ?: AndroidAlarmHostPresenter(reactContext.applicationContext))
          .present(active.instanceId)
        val receipt = LaunchReceipt(
          requestId,
          instanceId,
          expectedRevision,
          UUID.randomUUID().toString(),
        )
        launchReceipts[requestId] = receipt
        promise.resolve(launchAck(receipt))
      } catch (error: Throwable) {
        rejectMapped(promise, error)
      }
    }
  }

  override fun invalidate() {
    executor.shutdown()
    if (directBootDatabaseDelegate.isInitialized()) directBootDatabaseDelegate.value.close()
    if (databaseDelegate.isInitialized()) database.close()
    super.invalidate()
  }

  private fun drainReliabilityEffects() {
    runCatching { runtimeStopEffectRunner.drain() }
    runCatching { runtimeEffectRunner.drain() }
    runCatching { presentationEffectRunner.drain() }
    runCatching { scheduleEffectRunner.drain() }
    runCatching { mirrorEffectRunner.drain() }
  }

  private fun editorSnapshot(stored: AlarmWithMission?) = Arguments.createMap().apply {
    putDouble("generatedAtMs", System.currentTimeMillis().toDouble())
    putBoolean("isNewDraft", stored == null)
    if (stored == null) putNull("alarm") else putMap("alarm", alarmSnapshot(stored))
    putMap("capabilities", capabilitySnapshot())
    putString("availablePushupProfileVersion", PUSHUP_PROFILE_VERSION)
    putString("availableMathGeneratorVersion", MATH_GENERATOR_VERSION)
  }

  private fun activeRuntimeSnapshot(snapshot: ActiveRuntimeSnapshot?) = Arguments.createMap().apply {
    putDouble("generatedAtMs", System.currentTimeMillis().toDouble())
    putBoolean("found", snapshot != null)
    putNullableString("instanceId", snapshot?.instanceId)
    putNullableInt("revision", snapshot?.revision)
    putNullableString("runtimeState", snapshot?.runtimeState)
    putNullableDouble("scheduledAtUtcMs", snapshot?.scheduledAtUtcMs)
    putNullableDouble("actualTriggerAtMs", snapshot?.actualTriggerAtMs)
    putNullableString("missionType", snapshot?.missionType)
    putNullableInt("target", snapshot?.target)
    putNullableInt("committedProgress", snapshot?.committedProgress)
    putNull("feedbackCode")
    putNullableString(
      "recoveryReasonCode",
      if (snapshot?.runtimeState == "RECOVERY_REQUIRED" ||
        snapshot?.missionRuntimeStatus == "RECOVERY_REQUIRED"
      ) "MISSION_RECOVERY_REQUIRED" else null,
    )
    val mathQuestion = snapshot?.mathQuestion
    if (mathQuestion == null) {
      putNull("mathQuestion")
    } else {
      putMap("mathQuestion", Arguments.createMap().apply {
        putInt("ordinal", mathQuestion.ordinal)
        putInt("total", snapshot.target)
        putString("operation", mathQuestion.operation)
        putInt("operandA", mathQuestion.operandA)
        putInt("operandB", mathQuestion.operandB)
      })
    }
    putInt("queuedCount", snapshot?.queuedCount ?: 0)
    putNull("terminalResult")
  }

  private fun homeSnapshot(
    alarms: List<AlarmWithMission>,
    active: ActiveRuntimeSnapshot?,
    history: List<AlarmHistoryEntity>,
  ) = Arguments.createMap().apply {
    putDouble("generatedAtMs", System.currentTimeMillis().toDouble())
    putArray("alarms", Arguments.createArray().apply {
      alarms.forEach { pushMap(alarmListItem(it)) }
    })
    if (active == null) {
      putNull("active")
    } else {
      putMap("active", Arguments.createMap().apply {
        putString("instanceId", active.instanceId)
        putInt("revision", active.revision)
        putString("state", active.runtimeState)
        putString("missionType", active.missionType)
        putInt("target", active.target)
        putInt("committedProgress", active.committedProgress)
        putInt("queuedCount", active.queuedCount)
      })
    }
    putArray("recentHistory", Arguments.createArray().apply {
      history.forEach { item ->
        pushMap(Arguments.createMap().apply {
          putString("instanceId", item.instanceId)
          putDouble("endedAtMs", item.endedAtMs.toDouble())
          putDouble("scheduledAtUtcMs", item.scheduledAtUtcMs.toDouble())
          putString("missionType", item.missionType)
          putInt("target", item.target)
          putInt("finalProgress", item.finalProgress)
          putString("result", item.result)
        })
      }
    })
  }

  private fun alarmListItem(stored: AlarmWithMission) = Arguments.createMap().apply {
    val alarm = stored.alarm
    val occurrence = database.runtimeDao().findNextOccurrence(alarm.id)
    putString("id", alarm.id)
    putInt("revision", alarm.revision)
    putString("label", alarm.label)
    putBoolean("enabled", alarm.enabled)
    putInt("localTimeMinutes", alarm.localTimeMinutes)
    putInt("repeatDaysMask", alarm.repeatDaysMask)
    putString("missionType", stored.mission.missionType)
    putInt("target", stored.mission.target)
    putNullableDouble("nextOccurrenceAtUtcMs", occurrence?.scheduledAtUtcMs)
    putString("scheduleHealth", scheduleHealth(alarm.enabled, occurrence))
  }

  private fun launchAck(receipt: LaunchReceipt) = Arguments.createMap().apply {
    putString("requestId", receipt.requestId)
    putString("sessionId", receipt.sessionId)
    putBoolean("launched", true)
    putString("launchType", "ACTIVE_INSTANCE")
  }

  private fun alarmSnapshot(stored: AlarmWithMission) = Arguments.createMap().apply {
    val alarm = stored.alarm
    val mission = stored.mission
    val occurrence = database.runtimeDao().findNextOccurrence(alarm.id)
    putString("id", alarm.id)
    putInt("revision", alarm.revision)
    putString("label", alarm.label)
    putBoolean("enabled", alarm.enabled)
    putString("scheduleKind", alarm.scheduleKind)
    putInt("localTimeMinutes", alarm.localTimeMinutes)
    putInt("repeatDaysMask", alarm.repeatDaysMask)
    putNullableDouble("oneTimeAtUtcMs", alarm.oneTimeAtUtcMs)
    putString("configuredTimezoneId", alarm.configuredTimezoneId)
    putString("soundId", alarm.soundId)
    putMap("mission", Arguments.createMap().apply {
      putString("missionType", mission.missionType)
      putInt("configVersion", mission.configVersion)
      putInt("target", mission.target)
      putNullableString("pushupProfileVersion", mission.pushupProfileVersion)
      putNullableInt("mathOperationsMask", mission.mathOperationsMask)
      putNullableString("mathGeneratorVersion", mission.mathGeneratorVersion)
      putBoolean("qrRegistered", mission.qrReferenceDigest != null)
      putNullableString("qrDigestVersion", mission.qrDigestVersion)
    })
    putNullableDouble("nextOccurrenceAtUtcMs", occurrence?.scheduledAtUtcMs)
    putString(
      "scheduleHealth",
      scheduleHealth(alarm.enabled, occurrence),
    )
    putNullableString("scheduleErrorCode", occurrence?.lastErrorCode)
  }

  private fun capabilitySnapshot() = Arguments.createMap().apply {
    val now = System.currentTimeMillis()
    val alarmManager = reactContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val notificationManager =
      reactContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val exactAvailable = Build.VERSION.SDK_INT < 31 ||
      declaresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM) ||
      declaresPermission(Manifest.permission.USE_EXACT_ALARM)
    val exactGranted = exactAvailable &&
      (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms())
    val notificationsAvailable = Build.VERSION.SDK_INT < 33 ||
      declaresPermission(Manifest.permission.POST_NOTIFICATIONS)
    val notificationsGranted = notificationManager.areNotificationsEnabled() &&
      notificationsAvailable &&
      (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS))
    val fullScreenAvailable = Build.VERSION.SDK_INT < 29 ||
      declaresPermission(Manifest.permission.USE_FULL_SCREEN_INTENT)
    val fullScreenGranted = fullScreenAvailable &&
      (Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent())
    val cameraAvailable =
      reactContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
        declaresPermission(Manifest.permission.CAMERA)
    val cameraGranted = cameraAvailable && hasPermission(Manifest.permission.CAMERA)

    putDouble("checkedAtMs", now.toDouble())
    putInt("androidApiLevel", Build.VERSION.SDK_INT)
    putMap("exactAlarm", capability("EXACT_ALARM", exactGranted, true, false, true, exactAvailable))
    putMap(
      "notifications",
      capability(
        "NOTIFICATIONS",
        notificationsGranted,
        false,
        notificationsAvailable && Build.VERSION.SDK_INT >= 33,
        true,
        notificationsAvailable,
      ),
    )
    putMap(
      "fullScreenIntent",
      capability("FULL_SCREEN_INTENT", fullScreenGranted, false, false, true, fullScreenAvailable),
    )
    putMap(
      "camera",
      capability("CAMERA", cameraGranted, false, cameraAvailable && !cameraGranted, true, cameraAvailable),
    )
  }

  private fun hasCriticalSchedulingCapability(): Boolean {
    criticalSchedulingCapabilityOverride?.let { return it() }
    val exactDeclared = Build.VERSION.SDK_INT < 31 ||
      declaresPermission(Manifest.permission.SCHEDULE_EXACT_ALARM) ||
      declaresPermission(Manifest.permission.USE_EXACT_ALARM)
    if (!exactDeclared) return false
    if (Build.VERSION.SDK_INT < 31) return true
    val alarmManager = reactContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
  }

  private fun commandAck(
    commandId: String,
    alarmId: String,
    revision: Int,
    appliedAtMs: Long,
    replayed: Boolean,
  ) = Arguments.createMap().apply {
    putString("commandId", commandId)
    putString("aggregateType", "ALARM")
    putString("aggregateId", alarmId)
    putInt("revision", revision)
    putDouble("appliedAtMs", appliedAtMs.toDouble())
    putBoolean("replayed", replayed)
  }

  private fun capability(
    name: String,
    granted: Boolean,
    required: Boolean,
    canRequest: Boolean,
    canOpenSettings: Boolean,
    available: Boolean = true,
  ) = Arguments.createMap().apply {
    putString("capability", name)
    putString("status", if (!available) "UNAVAILABLE" else if (granted) "GRANTED" else "DENIED")
    putBoolean("requiredForEnable", required)
    putBoolean("canRequestInApp", canRequest)
    putBoolean("canOpenSettings", available && canOpenSettings)
  }

  private fun hasPermission(permission: String): Boolean =
    reactContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  @Suppress("DEPRECATION")
  private fun declaresPermission(permission: String): Boolean =
    reactContext.packageManager
      .getPackageInfo(reactContext.packageName, PackageManager.GET_PERMISSIONS)
      .requestedPermissions
      ?.contains(permission) == true

  private fun requireContractVersion(version: Int) {
    if (version != CONTRACT_VERSION) throw UnsupportedContractVersion()
  }

  private fun rejectMapped(promise: Promise, error: Throwable) {
    val code = when (error) {
      is UnsupportedContractVersion -> "UNSUPPORTED_CONTRACT_VERSION"
      is AlarmDraftRepositoryException.NotFound -> "NOT_FOUND"
      is AlarmDraftRepositoryException.RevisionConflict -> "CONFLICT_REVISION"
      is AlarmDraftRepositoryException.IdempotencyKeyReused -> "IDEMPOTENCY_KEY_REUSED"
      is AlarmDraftRepositoryException.EnabledEditUnsupported -> "INVALID_STATE"
      is AlarmSchedulingRepositoryException.NotFound -> "NOT_FOUND"
      is AlarmSchedulingRepositoryException.RevisionConflict -> "CONFLICT_REVISION"
      is AlarmSchedulingRepositoryException.IdempotencyKeyReused -> "IDEMPOTENCY_KEY_REUSED"
      is AlarmSchedulingRepositoryException.QrNotRegistered -> "QR_NOT_REGISTERED"
      is AlarmSchedulingRepositoryException.AlreadyEnabled,
      is AlarmSchedulingRepositoryException.AlreadyDisabled,
      is AlarmSchedulingRepositoryException.ActiveInstanceExists,
      is AlarmSchedulingRepositoryException.ScheduleExpired,
      is AlarmSchedulingRepositoryException.PendingOccurrenceExists,
      -> "INVALID_STATE"
      is CapabilityRequired -> "CAPABILITY_REQUIRED"
      is ActiveInstanceNotFound -> "NOT_FOUND"
      is ActiveInstanceRevisionConflict -> "CONFLICT_REVISION"
      is LaunchRequestReused -> "IDEMPOTENCY_KEY_REUSED"
      is IllegalArgumentException ->
        if (error.message?.contains("lowercase UUID v4") == true) {
          "INVALID_ARGUMENT"
        } else {
          "VALIDATION_FAILED"
        }
      else -> "INTERNAL_CONTRACT_ERROR"
    }
    promise.reject(code, code)
  }

  private class UnsupportedContractVersion : IllegalArgumentException()
  private class CapabilityRequired : IllegalStateException()
  private class ActiveInstanceNotFound : IllegalStateException()
  private class ActiveInstanceRevisionConflict : IllegalStateException()
  private class LaunchRequestReused : IllegalStateException()

  private data class LaunchReceipt(
    val requestId: String,
    val instanceId: String,
    val expectedRevision: Int,
    val sessionId: String,
  )

  companion object {
    const val NAME = "NativeMissionAlarm"
    const val CONTRACT_VERSION = 1
    const val MINIMUM_CLIENT_CONTRACT_VERSION = 1
    const val PUSHUP_PROFILE_VERSION = "pushup-profile-v1"
    const val MATH_GENERATOR_VERSION = "math-v1"
    const val HOME_HISTORY_LIMIT = 5
  }
}

private fun scheduleHealth(enabled: Boolean, occurrence: com.missionalarm.core.data.AlarmOccurrenceEntity?) =
  when {
    !enabled -> "DISABLED"
    occurrence == null -> "PENDING"
    occurrence.state == "SCHEDULED_OS" -> "HEALTHY"
    occurrence.state == "FAILED" -> "FAILED"
    occurrence.lastErrorCode == "EXACT_ALARM_CAPABILITY_REQUIRED" -> "BLOCKED"
    else -> "PENDING"
  }

private fun ReadableMap.requiredString(key: String): String =
  requireNotNull(if (hasKey(key) && !isNull(key)) getString(key) else null) { "$key is required" }

private fun ReadableMap.nullableString(key: String): String? =
  if (!hasKey(key) || isNull(key)) null else getString(key)

private fun ReadableMap.requiredInt(key: String): Int =
  requireNotNull(if (hasKey(key) && !isNull(key)) getDouble(key).toContractInt() else null) {
    "$key is required"
  }

private fun ReadableMap.nullableInt(key: String): Int? =
  if (!hasKey(key) || isNull(key)) null else getDouble(key).toContractInt()

private fun ReadableMap.nullableLong(key: String): Long? =
  if (!hasKey(key) || isNull(key)) null else getDouble(key).toContractLong()

private fun ReadableMap.toAlarmAggregateCommand() = EnableAlarmCommand(
  commandId = CommandId.parse(requiredString("commandId")),
  alarmId = AlarmId.parse(requiredString("aggregateId")),
  expectedRevision = Revision.of(requiredInt("expectedRevision")),
)

private fun Double.toContractInt(): Int {
  require(isFinite() && this % 1.0 == 0.0 && this in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
  return toInt()
}

private fun Double.toContractLong(): Long {
  require(isFinite() && this % 1.0 == 0.0 && this in 0.0..9_007_199_254_740_991.0)
  return toLong()
}

private fun com.facebook.react.bridge.WritableMap.putNullableString(key: String, value: String?) {
  if (value == null) putNull(key) else putString(key, value)
}

private fun com.facebook.react.bridge.WritableMap.putNullableInt(key: String, value: Int?) {
  if (value == null) putNull(key) else putInt(key, value)
}

private fun com.facebook.react.bridge.WritableMap.putNullableDouble(key: String, value: Long?) {
  if (value == null) putNull(key) else putDouble(key, value.toDouble())
}
