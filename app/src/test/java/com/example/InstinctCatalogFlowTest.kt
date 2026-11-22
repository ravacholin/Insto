package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.phone.*
import java.io.File
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Catálogo vivo» (1.18.0) on Android: when it is published, what goes to the script and what Settings says. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InstinctCatalogFlowTest {
  private val app get() = ApplicationProvider.getApplicationContext<Application>()
  private val url = "https://script.google.com/macros/s/AKfycbx-ejemplo_123456/exec"
  private val token = "token-largo-de-prueba"
  private val posts = mutableListOf<JSONObject>()
  private var answer: (JSONObject) -> String = { """{"ok":true,"catalogo":"guardado"}""" }
  private var jobs = 0

  @Before fun setUp() {
    InstinctLog.schedule = {}
    InstinctStatePublisher.heartbeat = { _, _ -> }
    InstinctMailbox.schedule = { _, _ -> }
    InstinctCatalogPublisher.schedule = { jobs++ }
    InstinctCatalogPublisher.instoVersion = { "1.18.0" }
    val fake = FakeHttp { ex ->
      val body = JSONObject(ex.bodyText())
      if (body.optString("action") == "catalogo") { posts += body; FakeHttp.Reply(200, answer(body)) }
      else FakeHttp.Reply(200, """{"ok":true,"accepted":[]}""")
    }
    InstinctCatalogPublisher.client = { InstinctLogClient(OkHttpClient.Builder().addInterceptor(fake).build()) }
    File(app.noBackupFilesDir, InstinctCatalogPublisher.LAST_FILE).delete()
    InstinctProposalSettings(app).setEnabled(true)
  }

  @After fun tearDown() {
    InstinctAutonomySettings(app).setQuiet(null)
    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.SOLO)
    InstinctProposalSettings(app).setEnabled(false)
    InstinctLogSettings(app).clear()
  }

  @Test fun `without the log nothing is published or scheduled`() {
    InstinctAutonomySettings(app).set(InstinctCategory.VOLUMEN, InstinctAutonomy.CONFIRMAR)
    assertEquals(0, jobs)
    assertEquals(InstinctCatalogPublisher.Outcome.Inactive, InstinctCatalogPublisher.publish(app))
    assertNull(InstinctCatalogPublisher.line(app))
  }

  @Test fun `published once per hash, with its hash, and again when a setting changes`() {
    assertTrue(InstinctLogSettings(app).save(url, token))
    assertTrue("saving the log schedules it", jobs > 0)
    val first = InstinctCatalogPublisher.publish(app)
    assertTrue(first is InstinctCatalogPublisher.Outcome.Published)
    val sent = posts.single()
    val json = JSONObject(sent.getString("json"))
    assertEquals(sent.getString("hash"), json.getString("hash"))
    assertEquals(InstinctCatalogPublisher.current(app).hash, sent.getString("hash"))
    assertTrue(json.has("publicado"))
    listOf(url, token).forEach { assertFalse(sent.getString("json").contains(it)) }
    assertEquals(InstinctCatalogPublisher.Outcome.Unchanged, InstinctCatalogPublisher.publish(app))
    assertEquals(1, posts.size)
    val before = jobs
    InstinctAutonomySettings(app).setQuiet(QuietHours(0, 7 * 60))
    assertTrue("a change schedules the job", jobs > before)
    assertTrue(InstinctCatalogPublisher.publish(app) is InstinctCatalogPublisher.Outcome.Published)
    assertNotEquals(posts[0].getString("hash"), posts[1].getString("hash"))
    assertEquals("00:00", JSONObject(posts[1].getString("json")).getJSONObject("horas_de_silencio").getString("desde"))
    val line = InstinctCatalogPublisher.line(app)!!
    assertTrue(line, line.startsWith("Catálogo para Instinct: publicado "))
    assertTrue(line, line.endsWith("••••" + posts[1].getString("hash").takeLast(6)))
    // «ver catálogo» answers with the published hash.
    assertTrue(InstinctProposalCenter.answer(app, ProposalCheck.QueryKind.CATALOG).endsWith("hash ${posts[1].getString("hash")}."))
  }

  @Test fun `a script older than 1_18_0 or a rejected token wait for the person, a network failure retries`() {
    assertTrue(InstinctLogSettings(app).save(url, token))
    answer = { """{"ok":true,"accepted":[]}""" }
    assertEquals(InstinctCatalogPublisher.Outcome.Failed(InstinctCatalogPublisher.OLD_SCRIPT, retry = false), InstinctCatalogPublisher.publish(app))
    assertTrue(InstinctCatalogPublisher.line(app)!!.contains("no se pudo publicar"))
    answer = { """{"ok":false,"error":"token"}""" }
    assertEquals(InstinctCatalogPublisher.Outcome.Failed("token rechazado", retry = false), InstinctCatalogPublisher.publish(app))
    InstinctCatalogPublisher.client = { InstinctLogClient(OkHttpClient.Builder().addInterceptor { throw java.io.IOException("sin red") }.build()) }
    assertEquals(InstinctCatalogPublisher.Outcome.Failed("sin red", retry = true), InstinctCatalogPublisher.publish(app))
    // The script took one: the failure is gone and «igual» counts as published.
    InstinctCatalogPublisher.client = { InstinctLogClient(OkHttpClient.Builder().addInterceptor(FakeHttp { FakeHttp.Reply(200, """{"ok":true,"catalogo":"igual"}""") }).build()) }
    assertTrue(InstinctCatalogPublisher.publish(app) is InstinctCatalogPublisher.Outcome.Published)
    assertFalse(InstinctCatalogPublisher.line(app)!!.contains("no se pudo"))
  }
}
