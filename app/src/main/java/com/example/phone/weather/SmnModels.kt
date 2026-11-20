package com.example.phone.weather

import java.math.BigDecimal
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * SMN publishes local Argentine time without an offset. java.time needs API 26 (minSdk is 24 and the
 * project has no desugaring), so instants are epoch millis interpreted with this zone.
 */
internal val SMN_ZONE: TimeZone = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")

internal fun smnCalendar(millis: Long? = null): Calendar = Calendar.getInstance(SMN_ZONE, Locale.ROOT).apply {
  if (millis != null) timeInMillis = millis
}

/** Local calendar date in the SMN zone. */
data class SmnDate(val year: Int, val month: Int, val day: Int) : Comparable<SmnDate> {
  init { require(month in 1..12 && day in 1..31) }
  override fun compareTo(other: SmnDate): Int = compareValuesBy(this, other, SmnDate::year, SmnDate::month, SmnDate::day)
  override fun toString(): String = "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)

  /** Epoch millis of [hour]:00 on this date, or null if the calendar rejects it (for example 31 of a short month). */
  fun atHour(hour: Int): Long? = try {
    smnCalendar().apply {
      isLenient = false
      clear()
      set(year, month - 1, day, 0, 0, 0)
      timeInMillis // validates
      add(Calendar.HOUR_OF_DAY, hour)
    }.timeInMillis
  } catch (_: IllegalArgumentException) { null }

  companion object {
    fun of(millis: Long): SmnDate = smnCalendar(millis).let {
      SmnDate(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
    }
  }
}

/** Feels-like as published: a number, the literal «No se calcula», or absent/invalid. */
sealed interface FeelsLike {
  data class Value(val celsius: BigDecimal) : FeelsLike
  data object NotCalculated : FeelsLike
  data object Missing : FeelsLike
}

/** One row of the SMN «Estado del tiempo presente» file: a station, not a locality. */
data class SmnCurrentObservation(
  val stationName: String,
  /** Local date and hour of the row, interpreted in [SMN_ZONE]. */
  val observedAtMillis: Long,
  /** Published precision is kept (for example «27.6» or «24»). */
  val temperatureCelsius: BigDecimal,
  val feelsLike: FeelsLike,
)

enum class SmnPeriod(val key: String, val startHour: Int, val endHour: Int) {
  EARLY_MORNING("early_morning", 0, 6),
  MORNING("morning", 6, 12),
  AFTERNOON("afternoon", 12, 18),
  NIGHT("night", 18, 24),
}

sealed interface RainProbability {
  /** Published as `rain_prob_range: [min, max]` with min < max. */
  data class Range(val min: Int, val max: Int) : RainProbability
  /** Published as a range with min == max. */
  data class Exact(val value: Int) : RainProbability
  /** Absent, null or not a valid percentage. Never treated as zero. */
  data object Missing : RainProbability
}

data class SmnRainPeriod(
  val date: SmnDate,
  val period: SmnPeriod,
  val startMillis: Long,
  val endMillis: Long,
  val probability: RainProbability,
  /** The original `rain_prob_range` JSON, kept as published; null if absent. */
  val originalRange: String?,
)

data class SmnForecast(
  val locationId: Int,
  /** As published in `location`; null when the response did not name it. */
  val locationName: String?,
  val updatedAtMillis: Long,
  val periods: List<SmnRainPeriod>,
)
