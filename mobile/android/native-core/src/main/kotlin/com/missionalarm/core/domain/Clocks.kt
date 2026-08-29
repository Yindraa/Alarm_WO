package com.missionalarm.core.domain

fun interface WallClock {
  fun nowEpochMillis(): Long
}

fun interface MonotonicClock {
  fun elapsedRealtimeMillis(): Long
}
