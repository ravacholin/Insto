package com.example.phone.weather

import com.example.phone.PhoneCommand
import com.example.phone.PlatformAction
import com.example.phone.WeatherIntentParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Answer for the floating surface. [answered] is false when the SMN could not be consulted. */
data class SmnWeatherReply(val text: String, val answered: Boolean)

/** Boundary used by the view model; tests replace it. */
fun interface WeatherQueries {
  suspend fun answer(command: PhoneCommand.Platform): SmnWeatherReply
}

/**
 * On-demand SMN queries: no disk cache, no polling, no workers and nothing at startup.
 * Network and parsing run on [io]; concurrent identical requests share one download.
 */
class SmnWeatherService(
  transport: SmnTransport = OkHttpSmnTransport,
  private val clock: () -> Long = System::currentTimeMillis,
  io: CoroutineDispatcher = Dispatchers.IO,
  /** Station name exactly as it appears in `tiepre`. */
  val stationName: String = DEFAULT_STATION,
  /** `smn_id_interno` of «Capital Federal» in the SMN locality list. */
  val locationId: Int = DEFAULT_LOCATION_ID,
) : WeatherQueries {
  private val cooldown = SmnCooldown(clock)
  private val openData = SmnOpenDataClient(transport, cooldown)
  private val forecasts = SmnForecastClient(transport, SmnTokenProvider(transport, cooldown, clock), cooldown)
  private val scope = CoroutineScope(SupervisorJob() + io)
  private val lock = Mutex()
  private val cache = HashMap<String, Pair<Any, Long>>()
  private val inflight = HashMap<String, Deferred<Any>>()

  override suspend fun answer(command: PhoneCommand.Platform): SmnWeatherReply = when (command.action) {
    PlatformAction.WEATHER_TEMPERATURE -> temperature(command.argument == WeatherIntentParser.FEELS_LIKE)
    PlatformAction.WEATHER_RAIN -> rain()
    else -> throw IllegalArgumentException("No es una consulta del SMN")
  }

  suspend fun temperature(feelsLikeOnly: Boolean): SmnWeatherReply {
    val rows = try {
      cached("tiepre", ::observationExpiry) { openData.observations() }
    } catch (error: SmnException) {
      return SmnWeatherReply(SmnWeatherText.temperatureFailure(error.kind), answered = false)
    }
    val wanted = fold(stationName)
    val row = rows.firstOrNull { fold(it.stationName) == wanted }
      ?: return SmnWeatherReply(SmnWeatherText.missingStation(stationName), answered = false)
    val now = clock()
    return when {
      row.observedAtMillis > now + FUTURE_TOLERANCE_MILLIS ->
        SmnWeatherReply(SmnWeatherText.futureObservation(row.stationName), answered = false)
      now - row.observedAtMillis > MAX_OBSERVATION_AGE_MILLIS ->
        SmnWeatherReply(SmnWeatherText.staleObservation(row.stationName), answered = true)
      else -> SmnWeatherReply(SmnWeatherText.temperature(row, feelsLikeOnly), answered = true)
    }
  }

  suspend fun rain(): SmnWeatherReply {
    val forecast = try {
      cached("forecast:$locationId", { it + FORECAST_TTL_MILLIS }) { forecasts.forecast(locationId) }
    } catch (error: SmnException) {
      return SmnWeatherReply(SmnWeatherText.rainFailure(error.kind), answered = false)
    }
    val now = clock()
    val location = forecast.locationName ?: "la localidad $locationId del SMN"
    if (now - forecast.updatedAtMillis > MAX_FORECAST_AGE_MILLIS)
      return SmnWeatherReply(SmnWeatherText.staleForecast(location), answered = false)
    return SmnWeatherReply(SmnWeatherText.rain(location, window(forecast.periods, now), now), answered = true)
  }

  /** Published blocks overlapping [now, now + 6 h), in order, at most two. Finished blocks are dropped. */
  internal fun window(periods: List<SmnRainPeriod>, now: Long): List<SmnRainPeriod> =
    periods.filter { it.endMillis > now && it.startMillis < now + RAIN_WINDOW_MILLIS }
      .sortedBy { it.startMillis }.distinctBy { it.startMillis }.take(2)

  /** Five minutes, but never past the next full hour: a newer hourly file may be published then. */
  private fun observationExpiry(fetchedAt: Long): Long {
    val nextHour = smnCalendar(fetchedAt).apply {
      set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
      add(java.util.Calendar.HOUR_OF_DAY, 1)
    }.timeInMillis
    return minOf(fetchedAt + OBSERVATION_TTL_MILLIS, nextHour)
  }

  @Suppress("UNCHECKED_CAST")
  private suspend fun <T : Any> cached(key: String, expiry: (Long) -> Long, fetch: suspend () -> T): T {
    val pending = lock.withLock {
      cache[key]?.let { (value, until) -> if (until > clock()) return value as T else cache.remove(key) }
      inflight[key] ?: scope.async {
        try {
          val value: Any = fetch()
          lock.withLock { cache[key] = value to expiry(clock()) }
          value
        } finally {
          lock.withLock { inflight.remove(key) }
        }
      }.also { inflight[key] = it }
    }
    return pending.await() as T
  }

  /** Clears the in-memory cache; the cooldown is kept. */
  suspend fun clearCache() = lock.withLock { cache.clear() }

  private fun fold(text: String) = com.example.phone.PhoneCommandParser.normalize(text)

  companion object {
    const val DEFAULT_STATION = "Buenos Aires"
    const val DEFAULT_LOCATION_ID = 4864
    internal const val OBSERVATION_TTL_MILLIS = 5 * 60_000L
    internal const val FORECAST_TTL_MILLIS = 15 * 60_000L
    internal const val MAX_OBSERVATION_AGE_MILLIS = 3 * 60 * 60_000L
    internal const val MAX_FORECAST_AGE_MILLIS = 24 * 60 * 60_000L
    internal const val RAIN_WINDOW_MILLIS = 6 * 60 * 60_000L
    private const val FUTURE_TOLERANCE_MILLIS = 15 * 60_000L

    /** Process-wide, so the cache and the cooldown survive closing the floating panel. */
    val shared: SmnWeatherService by lazy { SmnWeatherService() }
  }
}
