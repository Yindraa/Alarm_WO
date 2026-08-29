package com.missionalarm.core.domain

private val LOWERCASE_UUID_V4 = Regex(
  "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)

private fun requireUuidV4(value: String, field: String): String {
  require(LOWERCASE_UUID_V4.matches(value)) {
    "$field must be a lowercase UUID v4"
  }
  return value
}

@JvmInline
value class AlarmId private constructor(val value: String) {
  companion object {
    fun parse(value: String) = AlarmId(requireUuidV4(value, "alarmId"))
  }
}

@JvmInline
value class OccurrenceId private constructor(val value: String) {
  companion object {
    fun parse(value: String) = OccurrenceId(requireUuidV4(value, "occurrenceId"))
  }
}

@JvmInline
value class InstanceId private constructor(val value: String) {
  companion object {
    fun parse(value: String) = InstanceId(requireUuidV4(value, "instanceId"))
  }
}

@JvmInline
value class CommandId private constructor(val value: String) {
  companion object {
    fun parse(value: String) = CommandId(requireUuidV4(value, "commandId"))
  }
}

@JvmInline
value class Revision private constructor(val value: Int) {
  fun next(): Revision {
    check(value < Int.MAX_VALUE) { "revision exhausted" }
    return Revision(value + 1)
  }

  companion object {
    fun of(value: Int): Revision {
      require(value >= 1) { "revision must be positive" }
      return Revision(value)
    }
  }
}
