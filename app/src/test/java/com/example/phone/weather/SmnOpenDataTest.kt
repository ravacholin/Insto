package com.example.phone.weather

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmnOpenDataTest {
  private val zip = fixture("tiepre-20261120.zip")
  private val rows = SmnObservationParser.parseZip(zip)

  private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { stream ->
      for ((name, bytes) in entries) { stream.putNextEntry(ZipEntry(name)); stream.write(bytes); stream.closeEntry() }
    }
  }.toByteArray()

  private fun kind(block: () -> Unit) = (runCatching(block).exceptionOrNull() as SmnException).kind

  @Test fun leeElArchivoRealConCodificacionWindows1252() {
    assertEquals(119, rows.size)
    assertTrue(rows.any { it.stationName == "Bahía Blanca" })
    assertTrue(rows.any { it.stationName == "Villa María Del Río Seco" })
    assertEquals("Azul", rows.first().stationName) // first row has no leading space
  }

  @Test fun filaDeBuenosAiresConSensacionTermicaNumerica() {
    val row = rows.single { it.stationName == "Buenos Aires" }
    assertEquals(BigDecimal("28.3"), row.temperatureCelsius)
    assertEquals(FeelsLike.Value(BigDecimal("28.4")), row.feelsLike)
    assertEquals(smnTime(2026, 11, 20, 12), row.observedAtMillis)
  }

  @Test fun noSeCalculaEsDistintoDeAusente() {
    assertEquals(FeelsLike.NotCalculated, rows.single { it.stationName == "Aeroparque Buenos Aires" }.feelsLike)
    val missing = SmnObservationParser.row(" Buenos Aires;20-noviembre-2026;12:00;Despejado;10 km;28.3;; 46;Oeste  5;1006.2 / ")!!
    assertEquals(FeelsLike.Missing, missing.feelsLike)
    val invalid = SmnObservationParser.row(" Buenos Aires;20-noviembre-2026;12:00;Despejado;10 km;28.3;--; 46;Oeste  5;1006.2 / ")!!
    assertEquals(FeelsLike.Missing, invalid.feelsLike)
  }

  @Test fun conservaLaPrecisionPublicadaYCadaFilaTieneSuHora() {
    val bolivar = rows.single { it.stationName == "Bolívar" }
    assertEquals("24", bolivar.temperatureCelsius.toPlainString())
    assertEquals(smnTime(2026, 11, 19, 21), rows.single { it.stationName == "Pigué" }.observedAtMillis)
    assertEquals(smnTime(2026, 11, 20, 11), rows.single { it.stationName == "Mar del Plata" }.observedAtMillis)
    assertEquals(smnTime(2026, 11, 20, 9), rows.single { it.stationName == "Trenque Lauquen" }.observedAtMillis)
  }

  @Test fun descartaFilasIlegiblesSinInventarValores() {
    for (line in listOf("", " / ", " Buenos Aires;20-noviembre-2026;12:00;Despejado", " Buenos Aires;20-brumario-2026;12:00;D;10 km;28.3;28.4; 46;Oeste;1006 / ",
      " Buenos Aires;20-noviembre-2026;25:00;D;10 km;28.3;28.4; 46;Oeste;1006 / ", " Buenos Aires;20-noviembre-2026;12:00;D;10 km;s/d;28.4; 46;Oeste;1006 / ",
      " Buenos Aires;31-noviembre-2026;12:00;D;10 km;28.3;28.4; 46;Oeste;1006 / ")) {
      assertNull(line, SmnObservationParser.row(line))
    }
    assertEquals(BigDecimal("-2.5"), SmnObservationParser.row(" Ushuaia;20-julio-2026;06:00;Nevadas;2 km;-2.5;-8.1; 90;Oeste  20;990 / ")!!.temperatureCelsius)
  }

  @Test fun rechazaHtmlNoZipVariosArchivosYDemasiadoGrande() {
    assertEquals(SmnErrorKind.MALFORMED, kind { SmnObservationParser.unzip(CLOUDFLARE_BLOCK.toByteArray()) })
    assertEquals(SmnErrorKind.MALFORMED, kind { SmnObservationParser.unzip("estado;del;tiempo".toByteArray()) })
    assertEquals(SmnErrorKind.MALFORMED, kind { SmnObservationParser.unzip(zipOf("a.txt" to "x".toByteArray(), "b.txt" to "y".toByteArray())) })
    assertEquals(SmnErrorKind.MALFORMED, kind { SmnObservationParser.unzip(zipOf("../a.txt" to "x".toByteArray())) })
    assertEquals(SmnErrorKind.MALFORMED, kind { SmnObservationParser.unzip(zip.copyOf(zip.size / 2)) })
    assertEquals(SmnErrorKind.TOO_LARGE, kind { SmnObservationParser.unzip(ByteArray(SmnObservationParser.MAX_COMPRESSED_BYTES + 1)) })
    assertEquals(SmnErrorKind.MALFORMED, kind { SmnObservationParser.parseZip(zipOf("vacio.txt" to "sin filas\r\n".toByteArray())) })
  }

  @Test fun unaBombaZipSeCortaPorElTamanoDescomprimido() {
    val bomb = zipOf("estado_tiempo.txt" to ByteArray(4 * 1024 * 1024) { ' '.code.toByte() })
    assertTrue(bomb.size < SmnObservationParser.MAX_COMPRESSED_BYTES)
    assertEquals(SmnErrorKind.TOO_LARGE, kind { SmnObservationParser.unzip(bomb) })
  }

  @Test fun elClienteHaceUnGetAcotadoYDetectaBloqueos() = runBlocking {
    val transport = FakeTransport { _, _ -> ok(zip, "application/octet-stream") }
    val client = SmnOpenDataClient(transport, SmnCooldown({ 0L }))
    assertEquals(119, client.observations().size)
    assertEquals(SmnOpenDataClient.URL, transport.calls.single().url)
    assertEquals(SmnObservationParser.MAX_COMPRESSED_BYTES, transport.calls.single().maxBytes)
    assertNull(transport.calls.single().headers["Authorization"])

    val page = SmnOpenDataClient(FakeTransport { _, _ -> html("<html>mantenimiento</html>") }, SmnCooldown({ 0L }))
    assertEquals(SmnErrorKind.MALFORMED, (runCatching { page.observations() }.exceptionOrNull() as SmnException).kind)
    val blocked = FakeTransport { _, _ -> html(CLOUDFLARE_BLOCK, 403) }
    val blockedClient = SmnOpenDataClient(blocked, SmnCooldown({ 0L }))
    repeat(2) { assertEquals(SmnErrorKind.BLOCKED, (runCatching { blockedClient.observations() }.exceptionOrNull() as SmnException).kind) }
    assertEquals(1, blocked.calls.size)
  }
}
