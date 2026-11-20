package com.example.phone.weather

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parser for `ws1.smn.gob.ar/v1/forecast/location/{id}`.
 *
 * NOT VERIFIED against a live response (Cloudflare blocked the anonymous token during the audit). The shape
 * follows independent clients that agree with each other: top-level `updated`, `location`, `forecast[]`; each
 * day has `date` and the periods `early_morning`, `morning`, `afternoon`, `night`, each an object or null with
 * `rain_prob_range: [min, max]`. Only that field is read for rain; `rain06h`, humidity or descriptions never are.
 * Unknown or null periods are left out rather than assumed.
 */
object SmnForecastParser {
  const val MAX_BYTES = 256 * 1024
  private val isoDate = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
  private val isoDateTime = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})[T ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.[0-9]{1,9})?)?(Z|[+-][0-9]{2}:?[0-9]{2})?")

  fun parse(text: String, locationId: Int): SmnForecast {
    val root = try {
      JSONTokener(text).nextValue() as? JSONObject
    } catch (error: JSONException) {
      throw SmnException(SmnErrorKind.MALFORMED, "El pronóstico del SMN no es JSON válido.", error)
    } ?: throw SmnException(SmnErrorKind.MALFORMED, "El pronóstico del SMN no es un objeto.")
    val updated = (root.opt("updated") as? String)?.let(::instant)
      ?: throw SmnException(SmnErrorKind.MALFORMED, "El pronóstico del SMN no informa su actualización.")
    val days = root.opt("forecast") as? JSONArray
      ?: throw SmnException(SmnErrorKind.MALFORMED, "El pronóstico del SMN no tiene días.")
    val periods = buildList {
      for (index in 0 until minOf(days.length(), 14)) {
        val day = days.opt(index) as? JSONObject ?: continue
        val date = (day.opt("date") as? String)?.let(::date) ?: continue
        for (period in SmnPeriod.entries) {
          val block = day.opt(period.key) as? JSONObject ?: continue
          val start = date.atHour(period.startHour) ?: continue
          val end = date.atHour(period.endHour) ?: continue
          val raw = block.opt("rain_prob_range")
          add(SmnRainPeriod(date, period, start, end, probability(raw), (raw as? JSONArray)?.toString()))
        }
      }
    }
    return SmnForecast(locationId, locationName(root.opt("location")), updated, periods)
  }

  internal fun probability(raw: Any?): RainProbability {
    val array = raw as? JSONArray ?: return RainProbability.Missing
    if (array.length() != 2) return RainProbability.Missing
    val values = (0 until 2).map { index ->
      val value = array.opt(index) as? Number ?: return RainProbability.Missing
      val number = value.toDouble()
      if (!number.isFinite() || number != Math.floor(number) || number < 0 || number > 100) return RainProbability.Missing
      number.toInt()
    }
    return when {
      values[0] == values[1] -> RainProbability.Exact(values[0])
      values[0] < values[1] -> RainProbability.Range(values[0], values[1])
      else -> RainProbability.Missing
    }
  }

  private fun locationName(location: Any?): String? = when (location) {
    is JSONObject -> location.opt("name") as? String
    is String -> location
    else -> null
  }?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 }

  private fun date(text: String): SmnDate? {
    val match = isoDate.find(text)?.takeIf { it.range.first == 0 } ?: return null
    return try {
      SmnDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        .takeIf { it.atHour(0) != null }
    } catch (_: IllegalArgumentException) { null }
  }

  /** ISO date-time; without an offset it is SMN local time. */
  internal fun instant(text: String): Long? {
    val m = isoDateTime.matchEntire(text.trim()) ?: return null
    val (y, mo, d, h, mi) = m.groupValues.subList(1, 6).map(String::toInt)
    val seconds = m.groupValues[6].ifEmpty { "0" }.toInt()
    if (h > 23 || mi > 59 || seconds > 59) return null
    val zone = m.groupValues[7]
    val calendar = java.util.Calendar.getInstance(
      if (zone.isEmpty()) SMN_ZONE else java.util.TimeZone.getTimeZone("GMT" + if (zone == "Z") "" else zone.let {
        if (it.contains(':')) it else it.substring(0, 3) + ":" + it.substring(3)
      }), java.util.Locale.ROOT)
    return try {
      calendar.apply { isLenient = false; clear(); set(y, mo - 1, d, h, mi, seconds) }.timeInMillis
    } catch (_: IllegalArgumentException) { null }
  }
}

/** Authenticated with the anonymous JWT, which is sent only to [SmnHosts.API] over HTTPS. */
class SmnForecastClient(
  private val transport: SmnTransport,
  private val tokens: SmnTokenProvider,
  private val cooldown: SmnCooldown,
) {
  suspend fun forecast(locationId: Int): SmnForecast {
    require(locationId in 1..999_999)
    val url = "https://${SmnHosts.API}/v1/forecast/location/$locationId"
    val host = SmnHosts.host(url)
    check(host == SmnHosts.API) // The token never leaves this host.
    cooldown.check(host)
    cooldown.check(SmnHosts.PORTAL)
    return try {
      authorized(url, host)
    } catch (error: SmnException) {
      // A block on the home page or on the API stops the whole rain flow, without retries.
      if (error.kind == SmnErrorKind.BLOCKED || error.kind == SmnErrorKind.RATE_LIMITED) {
        cooldown.start(SmnHosts.PORTAL, error.kind)
        cooldown.start(SmnHosts.API, error.kind)
      }
      throw error
    }.let { SmnForecastParser.parse(it, locationId) }
  }

  private suspend fun authorized(url: String, host: String): String {
    var renewed = false
    while (true) {
      val token = tokens.token()
      val response = transport.get(url, mapOf("Authorization" to "JWT ${token.value}", "Accept" to "application/json"),
        SmnForecastParser.MAX_BYTES)
      SmnResponses.rejectBlocked(host, response, cooldown)
      if (response.code == 401) {
        tokens.invalidate(token)
        if (renewed) throw SmnException(SmnErrorKind.UNAUTHORIZED, "El SMN rechazó la credencial anónima renovada.")
        renewed = true
        continue
      }
      if (response.code != 200) throw SmnException(SmnErrorKind.NETWORK, "El SMN respondió con el estado ${response.code}.")
      val type = response.header("content-type").orEmpty().lowercase(java.util.Locale.ROOT)
      if (!type.startsWith("application/json") && !type.contains("+json"))
        throw SmnException(SmnErrorKind.NOT_JSON, "El SMN no devolvió JSON.")
      return String(response.body, Charsets.UTF_8)
    }
  }
}
