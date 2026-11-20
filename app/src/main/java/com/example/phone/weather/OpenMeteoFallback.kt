package com.example.phone.weather

import java.math.BigDecimal
import org.json.JSONException
import org.json.JSONObject

/** Current values and the next hours from the backup source. Times are epoch millis. */
data class FallbackWeather(
  val observedAtMillis: Long,
  val temperatureCelsius: BigDecimal,
  val feelsLikeCelsius: BigDecimal?,
  /** Hourly precipitation probability, in order, starting at the current hour. */
  val rain: List<Pair<Long, Int>>,
)

/** Backup source used only when the SMN does not answer. Tests replace it. */
fun interface WeatherFallback {
  suspend fun weather(): FallbackWeather
}

/**
 * Open-Meteo: free, no key, no account, JSON over HTTPS. One GET with fixed coordinates (Capital Federal); no
 * GPS. Used only when the SMN is down, and the answer always names Open-Meteo as its source.
 */
class OpenMeteoFallback(private val transport: SmnTransport) : WeatherFallback {
  override suspend fun weather(): FallbackWeather {
    val response = transport.get(URL, mapOf("Accept" to "application/json"), MAX_BYTES)
    if (response.code != 200) throw SmnException(SmnErrorKind.UNAVAILABLE, "Open-Meteo respondió con el estado ${response.code}.")
    return OpenMeteoParser.parse(String(response.body, Charsets.UTF_8))
  }

  companion object {
    const val HOST = "api.open-meteo.com"
    const val LATITUDE = "-34.6037"
    const val LONGITUDE = "-58.3816"
    const val URL = "https://$HOST/v1/forecast?latitude=$LATITUDE&longitude=$LONGITUDE" +
      "&current=temperature_2m,apparent_temperature&hourly=precipitation_probability&forecast_hours=6" +
      "&timezone=America%2FArgentina%2FBuenos_Aires"
    const val MAX_BYTES = 32 * 1024
  }
}

object OpenMeteoParser {
  private val localTime = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2})")

  fun parse(text: String): FallbackWeather = try {
    val root = JSONObject(text)
    val current = root.getJSONObject("current")
    val at = instant(current.getString("time")) ?: malformed()
    val temperature = number(current.opt("temperature_2m")) ?: malformed()
    val feels = number(current.opt("apparent_temperature"))
    val hourly = root.optJSONObject("hourly")
    val times = hourly?.optJSONArray("time")
    val probabilities = hourly?.optJSONArray("precipitation_probability")
    val rain = if (times == null || probabilities == null) emptyList() else
      (0 until minOf(times.length(), probabilities.length())).mapNotNull { i ->
        val time = instant(times.optString(i)) ?: return@mapNotNull null
        val value = probabilities.opt(i) as? Number ?: return@mapNotNull null
        time to value.toInt().coerceIn(0, 100)
      }
    FallbackWeather(at, temperature, feels, rain)
  } catch (error: JSONException) {
    throw SmnException(SmnErrorKind.MALFORMED, "Open-Meteo devolvió datos ilegibles.", error)
  }

  private fun malformed(): Nothing = throw SmnException(SmnErrorKind.MALFORMED, "Open-Meteo devolvió datos incompletos.")

  private fun number(value: Any?): BigDecimal? = (value as? Number)?.let { BigDecimal(it.toString()) }
    ?.takeIf { it > BigDecimal(-90) && it < BigDecimal(70) }

  private fun instant(text: String): Long? {
    val m = localTime.matchEntire(text) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    if (mo.toInt() !in 1..12 || d.toInt() !in 1..31 || h.toInt() !in 0..23 || mi.toInt() !in 0..59) return null
    return SmnDate(y.toInt(), mo.toInt(), d.toInt()).atHour(h.toInt())?.plus(mi.toInt() * 60_000L)
  }
}
