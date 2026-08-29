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
import com.missionalarm.core.data.AlarmWithMission
import com.missionalarm.core.data.MissionAlarmDatabaseFactory
import com.missionalarm.core.data.SaveAlarmDraftCommand
import com.missionalarm.core.domain.AlarmId
import com.missionalarm.core.domain.CommandId
import com.missionalarm.core.domain.MissionType
import com.missionalarm.core.domain.Revision
import com.missionalarm.specs.NativeMissionAlarmSpec
import java.util.UUID
import java.util.concurrent.Executors

class MissionAlarmModule(
  private val reactContext: ReactApplicationContext,
) : NativeMissionAlarmSpec(reactContext) {
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
  private val database by databaseDelegate
  private val repository by repositoryDelegate

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
        val ack = repository.save(command)
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

  override fun getAlarmEditorSnapshot(
    contractVersion: Double,
    alarmId: String?,
    promise: Promise,
  ) {
    executor.execute {
      try {
        requireContractVersion(contractVersion.toContractInt())
        val parsedAlarmId = alarmId?.let(AlarmId::parse)
        val stored = parsedAlarmId?.let(repository::find)
        if (parsedAlarmId != null && stored == null) throw AlarmDraftRepositoryException.NotFound()
        promise.resolve(editorSnapshot(stored))
      } catch (error: Throwable) {
        rejectMapped(promise, error)
      }
    }
  }

  override fun invalidate() {
    executor.shutdown()
    if (databaseDelegate.isInitialized()) database.close()
    super.invalidate()
  }

  private fun editorSnapshot(stored: AlarmWithMission?) = Arguments.createMap().apply {
    putDouble("generatedAtMs", System.currentTimeMillis().toDouble())
    putBoolean("isNewDraft", stored == null)
    if (stored == null) putNull("alarm") else putMap("alarm", alarmSnapshot(stored))
    putMap("capabilities", capabilitySnapshot())
    putString("availablePushupProfileVersion", PUSHUP_PROFILE_VERSION)
    putString("availableMathGeneratorVersion", MATH_GENERATOR_VERSION)
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
      when {
        !alarm.enabled -> "DISABLED"
        occurrence == null -> "PENDING"
        occurrence.state == "SCHEDULED_OS" -> "HEALTHY"
        occurrence.state == "FAILED" -> "FAILED"
        else -> "PENDING"
      },
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
        true,
        notificationsAvailable && Build.VERSION.SDK_INT >= 33,
        true,
        notificationsAvailable,
      ),
    )
    putMap(
      "fullScreenIntent",
      capability("FULL_SCREEN_INTENT", fullScreenGranted, true, false, true, fullScreenAvailable),
    )
    putMap(
      "camera",
      capability("CAMERA", cameraGranted, true, cameraAvailable && !cameraGranted, true, cameraAvailable),
    )
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

  companion object {
    const val NAME = "NativeMissionAlarm"
    const val CONTRACT_VERSION = 1
    const val MINIMUM_CLIENT_CONTRACT_VERSION = 1
    const val PUSHUP_PROFILE_VERSION = "pushup-profile-v1"
    const val MATH_GENERATOR_VERSION = "math-v1"
  }
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
