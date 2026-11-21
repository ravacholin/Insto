package com.example.assist

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/**
 * Persists [AutoSendMetrics] in private storage excluded from backups, with atomic writes like the workflow store.
 * Writes run on one background thread; reading for the settings screen and the panel is a small file.
 */
class AutoSendMetricsStore(context: Context) {
  private val app = context.applicationContext
  private val file = AtomicFile(File(app.noBackupFilesDir, FILE_NAME))

  fun read(): AutoSendMetrics.Snapshot = synchronized(lock) {
    runCatching { AutoSendMetrics.decode(file.readFully().toString(Charsets.UTF_8)) }.getOrDefault(AutoSendMetrics.Snapshot())
  }

  fun clear() = synchronized(lock) { file.delete() }

  /** Records one outcome. Never throws and never blocks the caller. */
  fun record(packageName: String, kind: AutoSendMetrics.Kind, strategy: String?, outcome: AutoSendMetrics.Outcome) {
    val flavor = AutoSendMetrics.Flavor.of(packageName) ?: return
    val now = System.currentTimeMillis()
    writer.execute {
      runCatching {
        val version = versionCode(app, packageName) ?: return@runCatching
        synchronized(lock) {
          val updated = AutoSendMetrics.record(read(), flavor, version, kind, strategy, outcome, now)
          val stream = file.startWrite()
          try {
            stream.write(AutoSendMetrics.encode(updated).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
          } catch (error: Exception) {
            file.failWrite(stream)
            throw error
          }
        }
      }
    }
  }

  companion object {
    const val FILE_NAME = "autosend-metrics.json"
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "insto-autosend-metrics").apply { isDaemon = true } }

    fun versionCode(context: Context, packageName: String): Long? = runCatching {
      val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
      else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, 0)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrNull()

    fun versionName(context: Context, packageName: String): String? = runCatching {
      @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()
  }
}

/** Ends the bookkeeping of [request] with [outcome]. Later calls for the same request are ignored. */
fun AutoSendCoordinator.finishReport(context: Context, request: Long, outcome: AutoSendMetrics.Outcome) {
  val report = reportFor(request) ?: return
  if (!report.finish()) return
  AutoSendMetricsStore(context).record(report.packageName, report.kind, report.strategy, outcome)
}

/** A request that ended without a touch keeps the last reason it could not proceed. */
fun AutoSendCoordinator.finishUnsent(context: Context, request: Long) {
  val report = reportFor(request) ?: return
  finishReport(context, request, if (report.touched) AutoSendMetrics.Outcome.UNCERTAIN else report.failure ?: AutoSendMetrics.Outcome.TIMEOUT)
}
