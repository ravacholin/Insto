package com.example.phone.weather

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.Charset
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Pure parser for the SMN open dataset «Estado del tiempo presente» (`zipopendata.php?dato=tiepre`).
 *
 * Verified format (20/11/2026): a ZIP with one `estado_tiempoYYYYMMDD.txt`, Windows-1252, CRLF, no header,
 * `;` separator, rows usually starting with a space and ending with ` / `:
 * `estación;fecha;hora;estado;visibilidad;temperatura;sensación térmica;humedad;viento;presión`.
 */
object SmnObservationParser {
  const val MAX_COMPRESSED_BYTES = 64 * 1024
  const val MAX_UNCOMPRESSED_BYTES = 256 * 1024
  private val windows1252: Charset = Charset.forName("windows-1252")
  private val months = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
    "septiembre", "octubre", "noviembre", "diciembre")
  private val date = Regex("([0-9]{1,2})-([a-z]+)-([0-9]{4})")
  private val time = Regex("([0-9]{1,2}):([0-9]{2})")
  private val decimal = Regex("-?[0-9]{1,3}(?:\\.[0-9]{1,2})?")
  const val NOT_CALCULATED = "No se calcula"

  /** Extracts the single text entry. Rejects HTML, non-ZIP data, several entries and oversized content. */
  fun unzip(bytes: ByteArray): String {
    if (bytes.size > MAX_COMPRESSED_BYTES) throw SmnException(SmnErrorKind.TOO_LARGE, "El archivo del SMN supera el tamaño admitido.")
    val start = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).trimStart()
    if (start.startsWith("<")) throw SmnException(SmnErrorKind.MALFORMED, "El SMN devolvió una página en lugar del archivo de datos.")
    if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte() || bytes[2] != 3.toByte() || bytes[3] != 4.toByte())
      throw SmnException(SmnErrorKind.MALFORMED, "El archivo del SMN no es un ZIP.")
    try {
      ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val entry = zip.nextEntry ?: throw SmnException(SmnErrorKind.MALFORMED, "El ZIP del SMN está vacío.")
        if (entry.isDirectory || !entry.name.endsWith(".txt") || entry.name.contains('/') || entry.name.contains('\\'))
          throw SmnException(SmnErrorKind.MALFORMED, "El ZIP del SMN no contiene el archivo esperado.")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
          val count = zip.read(buffer)
          if (count < 0) break
          // Guard against zip bombs: count what was actually inflated, not the declared size.
          if (output.size() + count > MAX_UNCOMPRESSED_BYTES)
            throw SmnException(SmnErrorKind.TOO_LARGE, "El archivo del SMN descomprimido supera el tamaño admitido.")
          output.write(buffer, 0, count)
        }
        zip.closeEntry()
        if (zip.nextEntry != null) throw SmnException(SmnErrorKind.MALFORMED, "El ZIP del SMN contiene más de un archivo.")
        return String(output.toByteArray(), windows1252)
      }
    } catch (error: ZipException) {
      throw SmnException(SmnErrorKind.MALFORMED, "El ZIP del SMN está dañado.", error)
    } catch (error: java.io.EOFException) {
      throw SmnException(SmnErrorKind.MALFORMED, "El ZIP del SMN está incompleto.", error)
    }
  }

  /** Valid rows only. A row with an unreadable date, hour or temperature is skipped, never guessed. */
  fun parse(text: String): List<SmnCurrentObservation> = text.split("\r\n", "\n").mapNotNull(::row)

  fun parseZip(bytes: ByteArray): List<SmnCurrentObservation> {
    val rows = parse(unzip(bytes))
    if (rows.isEmpty()) throw SmnException(SmnErrorKind.MALFORMED, "El archivo del SMN no tiene mediciones legibles.")
    return rows
  }

  internal fun row(line: String): SmnCurrentObservation? {
    val trimmed = line.trim().removeSuffix("/").trim()
    if (trimmed.isEmpty()) return null
    val fields = trimmed.split(';').map(String::trim)
    if (fields.size != 10) return null
    val station = fields[0].takeIf { it.isNotEmpty() && it.length <= 100 } ?: return null
    val observedAt = instant(fields[1], fields[2]) ?: return null
    val temperature = number(fields[5]) ?: return null
    val feelsLike = when {
      fields[6] == NOT_CALCULATED -> FeelsLike.NotCalculated
      else -> number(fields[6])?.let(FeelsLike::Value) ?: FeelsLike.Missing
    }
    return SmnCurrentObservation(station, observedAt, temperature, feelsLike)
  }

  private fun number(text: String): BigDecimal? =
    text.takeIf(decimal::matches)?.let(::BigDecimal)?.takeIf { it > BigDecimal(-90) && it < BigDecimal(70) }

  private fun instant(dateText: String, timeText: String): Long? {
    val d = date.matchEntire(dateText.lowercase(java.util.Locale.ROOT)) ?: return null
    val t = time.matchEntire(timeText) ?: return null
    val month = months.indexOf(d.groupValues[2]).takeIf { it >= 0 } ?: return null
    val hour = t.groupValues[1].toInt().takeIf { it in 0..23 } ?: return null
    val minute = t.groupValues[2].toInt().takeIf { it in 0..59 } ?: return null
    val day = d.groupValues[1].toInt().takeIf { it in 1..31 } ?: return null
    val base = SmnDate(d.groupValues[3].toInt(), month + 1, day).atHour(hour) ?: return null
    return base + minute * 60_000L
  }
}

/** Downloads and parses `tiepre`. No token, no cookies, one GET per call. */
class SmnOpenDataClient(
  private val transport: SmnTransport,
  private val cooldown: SmnCooldown,
) {
  suspend fun observations(): List<SmnCurrentObservation> {
    val host = SmnHosts.host(URL)
    cooldown.check(host)
    val response = transport.get(URL, mapOf("Accept" to "application/zip, application/octet-stream"),
      SmnObservationParser.MAX_COMPRESSED_BYTES)
    SmnResponses.rejectBlocked(host, response, cooldown)
    if (response.code != 200) throw SmnException(SmnErrorKind.NETWORK, "El SMN respondió con el estado ${response.code}.")
    if (response.header("content-type").orEmpty().lowercase(java.util.Locale.ROOT).contains("html"))
      throw SmnException(SmnErrorKind.MALFORMED, "El SMN devolvió una página en lugar del archivo de datos.")
    return SmnObservationParser.parseZip(response.body)
  }

  companion object {
    const val URL = "https://ssl.smn.gob.ar/dpd/zipopendata.php?dato=tiepre"
  }
}
