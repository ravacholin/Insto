package com.example.alarmvoice

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Vosk's small Spanish model. Not in the APK: downloaded from alphacephei's official URL when the person turns the
 * feature on, checked against its size and SHA-256 before it is unzipped to filesDir. Speech recognition then runs
 * on the phone only; Insto never sends audio anywhere.
 */
class AlarmVoiceModel(context: Context) {
  private val app = context.applicationContext
  private val root = File(app.filesDir, "vosk")
  val directory = File(root, MODEL_NAME)
  private val marker = File(root, "$MODEL_NAME.ok")

  fun isInstalled(): Boolean = marker.exists() && marker.readTextOrNull() == SHA256 && File(directory, "am/final.mdl").exists()

  /** Installed archive marker and the nonempty files needed by the recognizer. Native load checks the format. */
  fun hasRequiredFiles(): Boolean = isInstalled() && listOf(
    "am/final.mdl", "conf/model.conf", "conf/mfcc.conf", "graph/HCLr.fst", "graph/Gr.fst"
  ).all { path -> File(directory, path).let { it.isFile && it.length() > 0 } }

  fun sizeOnDisk(): Long = directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

  fun delete() {
    marker.delete()
    directory.deleteRecursively()
  }

  /**
   * Downloads, verifies and installs. [onProgress] gets 0..1 while downloading, then 1 while unzipping. Throws
   * with a message for the screen when something fails; a cancelled coroutine leaves nothing half installed.
   */
  suspend fun download(onProgress: (Float) -> Unit) {
    root.mkdirs()
    val zip = File(app.cacheDir, "$MODEL_NAME.zip.part")
    try {
      val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
      val digest = MessageDigest.getInstance("SHA-256")
      client.newCall(Request.Builder().url(URL).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("El servidor respondió ${response.code}.")
        val body = response.body ?: throw IOException("Respuesta vacía.")
        val total = body.contentLength().takeIf { it > 0 } ?: SIZE_BYTES
        var read = 0L
        body.byteStream().use { input ->
          zip.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              coroutineContext.ensureActive()
              val n = input.read(buffer)
              if (n < 0) break
              out.write(buffer, 0, n); digest.update(buffer, 0, n); read += n
              onProgress((read.toFloat() / total).coerceIn(0f, 0.99f))
            }
          }
        }
        if (read != SIZE_BYTES) throw IOException("El archivo descargado no tiene el tamaño esperado.")
      }
      val sha = digest.digest().joinToString("") { "%02x".format(it) }
      if (sha != SHA256) throw IOException("La verificación de integridad falló: el modelo no coincide con el oficial.")
      onProgress(1f)
      directory.deleteRecursively(); marker.delete()
      unzip(zip, root)
      if (!File(directory, "am/final.mdl").exists()) throw IOException("El modelo descomprimido está incompleto.")
      marker.writeText(SHA256)
    } catch (e: Throwable) {
      if (!isInstalled()) directory.deleteRecursively()
      throw e
    } finally {
      zip.delete()
    }
  }

  private suspend fun unzip(zip: File, into: File) {
    val base = into.canonicalPath + File.separator
    ZipInputStream(zip.inputStream().buffered()).use { zin ->
      while (true) {
        coroutineContext.ensureActive()
        val entry = zin.nextEntry ?: break
        val target = File(into, entry.name)
        // Zip-slip: nothing may land outside the model's folder.
        if (!target.canonicalPath.startsWith(base)) throw IOException("Entrada inválida en el modelo.")
        if (entry.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); target.outputStream().use { zin.copyTo(it) } }
      }
    }
  }

  private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

  companion object {
    const val MODEL_NAME = "vosk-model-small-es-0.42"
    const val URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    /** Size and SHA-256 of the official zip, checked on 25/11/2026. */
    const val SIZE_BYTES = 39_817_833L
    const val SHA256 = "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
  }
}
