package com.example.phone.weather

import java.math.BigDecimal
import java.util.Calendar
import java.util.Locale

/** Spanish answers. Only published values, with the SMN's own names and hours; never derived numbers. */
object SmnWeatherText {
  private fun number(value: BigDecimal) = value.toPlainString().replace('.', ',')
  internal fun clock(millis: Long): String = smnCalendar(millis).let {
    "%02d:%02d".format(Locale.ROOT, it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))
  }

  fun temperature(observation: SmnCurrentObservation, feelsLikeOnly: Boolean): String {
    val station = observation.stationName
    val at = clock(observation.observedAtMillis)
    val measured = "Según el SMN, en $station hay ${number(observation.temperatureCelsius)} grados (medición de las $at)."
    return when (val feels = observation.feelsLike) {
      is FeelsLike.Value -> if (feelsLikeOnly)
        "Según el SMN, la sensación térmica en $station es de ${number(feels.celsius)} grados (medición de las $at)."
        else "$measured La sensación térmica es de ${number(feels.celsius)}."
      FeelsLike.NotCalculated -> if (feelsLikeOnly)
        "El SMN no calcula la sensación térmica para $station en la medición de las $at."
        else measured
      FeelsLike.Missing -> {
        val missing = "El SMN no informa la sensación térmica para $station en este momento."
        if (feelsLikeOnly) missing else "$measured $missing"
      }
    }
  }

  fun staleObservation(station: String) =
    "La última medición del SMN para $station es de hace más de tres horas; no la informo como temperatura actual."
  fun missingStation(station: String) = "El último archivo del SMN no incluye una medición para $station."
  fun futureObservation(station: String) =
    "La medición del SMN para $station tiene una hora posterior a la actual; no la informo como temperatura actual."
  fun temperatureFailure(kind: SmnErrorKind) = when (kind) {
    SmnErrorKind.BLOCKED, SmnErrorKind.RATE_LIMITED -> "El SMN no permitió la consulta automática de la medición en este momento."
    else -> "No pude consultar la medición actual del SMN."
  }

  fun rainFailure(kind: SmnErrorKind) = when (kind) {
    SmnErrorKind.BLOCKED, SmnErrorKind.RATE_LIMITED -> "El SMN no permitió la consulta automática del pronóstico en este momento."
    else -> "No pude consultar el pronóstico actualizado del SMN."
  }
  fun staleForecast(location: String) =
    "El pronóstico del SMN para $location no se actualizó en las últimas 24 horas; no lo informo."
  fun noRainProbability(location: String) =
    "El SMN no informa una probabilidad de precipitaciones para $location en las próximas horas."

  /** [periods] are the published blocks overlapping the window, at most two, in order. */
  fun rain(location: String, periods: List<SmnRainPeriod>, now: Long): String {
    require(periods.size <= 2)
    if (periods.isEmpty() || periods.all { it.probability == RainProbability.Missing }) return noRainProbability(location)
    val today = SmnDate.of(now)
    val first = periods.first()
    val name = "${fullName(first, today)} (${hours(first.period)})"
    val opening = when (val p = first.probability) {
      is RainProbability.Range -> "Según el SMN, en $location, para $name hay entre ${percent(p.min)} y ${percent(p.max)} por ciento de probabilidad de precipitaciones."
      is RainProbability.Exact -> if (p.value == 0)
        "En $location, para $name, el SMN indica un cero por ciento de probabilidad de precipitaciones."
        else "Según el SMN, en $location, para $name hay ${percent(p.value)} por ciento de probabilidad de precipitaciones."
      RainProbability.Missing -> "En $location, para $name, el SMN no informa una probabilidad de precipitaciones."
    }
    val second = periods.getOrNull(1) ?: return opening
    val secondName = "${shortName(second, today)} (${hours(second.period)})"
    val follow = when (val p = second.probability) {
      is RainProbability.Range -> "Para $secondName, entre ${percent(p.min)} y ${percent(p.max)} por ciento."
      is RainProbability.Exact -> "Para $secondName, ${percent(p.value)} por ciento."
      RainProbability.Missing -> "Para $secondName, el SMN no informa una probabilidad."
    }
    return "$opening $follow"
  }

  private fun percent(value: Int) = if (value == 0) "un cero" else "un $value"
  private fun hours(period: SmnPeriod) = "${period.startHour} a ${period.endHour}"
  private fun partOfDay(period: SmnPeriod) = when (period) {
    SmnPeriod.EARLY_MORNING -> "madrugada"
    SmnPeriod.MORNING -> "mañana"
    SmnPeriod.AFTERNOON -> "tarde"
    SmnPeriod.NIGHT -> "noche"
  }
  private fun relativeDay(date: SmnDate, today: SmnDate): Int? {
    val start = today.atHour(0) ?: return null
    val target = date.atHour(0) ?: return null
    return Math.round((target - start) / 86_400_000.0).toInt()
  }
  private fun fullName(period: SmnRainPeriod, today: SmnDate) = when (relativeDay(period.date, today)) {
    0 -> "esta ${partOfDay(period.period)}"
    1 -> "mañana a la ${partOfDay(period.period)}"
    else -> "el ${period.date.day}/${period.date.month} a la ${partOfDay(period.period)}"
  }
  private fun shortName(period: SmnRainPeriod, today: SmnDate) = when (relativeDay(period.date, today)) {
    0 -> "la ${partOfDay(period.period)}"
    else -> fullName(period, today)
  }
}
