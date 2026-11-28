package com.example.phone

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Catalog success establishes the local contract and caller authorization. Device state is a separate check. */
internal class OmniremoteHealth(
  private val app: Context,
  private val provider: OmniremoteAdapter.Provider = InstinctDeviceCapabilities.omniremoteProvider,
  private val onDevice: (HealthCheck) -> Unit,
) : CapabilityHealthContributor {
  private val lock = Mutex()
  @Volatile private var cached: Catalog? = null

  internal data class Catalog(val code: HealthCode, val devices: List<OmniremoteAdapter.Device> = emptyList(),
    val at: Long = SystemClock.elapsedRealtime())

  fun invalidate() { cached = null }

  private fun installed(): Boolean = try {
    val pm = app.packageManager
    @Suppress("DEPRECATION")
    val info = pm.getPackageInfo(OmniremoteBinding.PACKAGE, 0)
    info.packageName == OmniremoteBinding.PACKAGE
  } catch (_: PackageManager.NameNotFoundException) {
    false
  }

  private suspend fun catalog(): Catalog = lock.withLock {
    cached?.takeIf { SystemClock.elapsedRealtime() - it.at < 45_000L }?.let { return@withLock it }
    val found = if (!installed()) Catalog(HealthCode.PROVIDER_MISSING)
    else try {
      Catalog(HealthCode.VERIFIED, runInterruptible { provider.devices(app) })
    } catch (error: OmniremoteAdapter.DiscoveryFailure) {
      Catalog(error.health)
    }
    cached = found
    found.devices.take(24).forEach { device ->
      onDevice(HealthCheck(deviceId(device.id), "Dispositivo Omniremote · ${device.label}") {
        val updated = catalog()
        when {
          updated.code != HealthCode.VERIFIED -> status(updated.code)
          updated.devices.none { it.id == device.id } -> status(HealthCode.DEVICE_MISSING)
          updated.devices.singleOrNull { it.id == device.id }?.stateFields.isNullOrEmpty() -> status(HealthCode.LIMITED)
          else -> status(HealthCode.PREFLIGHT, configured = true)
        }
      })
      onDevice(HealthCheck(deviceId(device.id) + ".read", "Leer estado · ${device.label}", timeoutMillis = 20_000L,
        effect = HealthEffect.RESOURCE, verifies = deviceId(device.id)) {
        val control = device.controls.firstOrNull {
          (if (it == OmniremoteAdapter.Control.MUTE) "muted" else it.name.lowercase(java.util.Locale.ROOT)) in device.stateFields
        }
        if (control == null) status(HealthCode.UNSUPPORTED)
        else status(try {
          if (runInterruptible { provider.read(app, device.id, control) } == null) HealthCode.DEVICE_UNREACHABLE
          else HealthCode.VERIFIED
        } catch (error: OmniremoteBinding.Unavailable) { error.health }
        catch (_: SecurityException) { HealthCode.PROVIDER_UNTRUSTED },
          evidence = HealthEvidence.ACTIVE_TEST)
      })
    }
    found
  }

  override fun checks() = buildList {
    add(HealthCheck("omniremote.package", "Paquete de Omniremote instalado") {
      val present = installed()
      status(if (present) HealthCode.VERIFIED else HealthCode.PROVIDER_MISSING, configured = present)
    })
    add(HealthCheck("omniremote.catalog", "Catálogo de Omniremote", timeoutMillis = 17_000L, recovery = { invalidate() }) {
      status(catalog().code, evidence = HealthEvidence.TRANSPORT)
    })
    listOf("binder" to "Conexión Binder", "contract" to "Contrato y versión AIDL",
      "authorization" to "Autorización de firma en Omniremote").forEach { (key, label) ->
      add(HealthCheck("omniremote.$key", label, timeoutMillis = 17_000L) {
        val code = catalog().code
        status(when {
          key == "binder" && code in setOf(HealthCode.VERSION, HealthCode.RESPONSE) -> HealthCode.VERIFIED
          key == "contract" && code == HealthCode.PROVIDER_UNTRUSTED -> HealthCode.UNKNOWN
          else -> code
        }, authorized = if (key != "authorization") null else when (code) {
          HealthCode.VERIFIED -> true
          HealthCode.PROVIDER_UNTRUSTED -> false
          else -> null
        }, evidence = HealthEvidence.TRANSPORT)
      })
    }
    OmniremoteAdapter.Control.entries.forEach { control ->
      add(HealthCheck(control.capability, "Omniremote · ${control.label}", timeoutMillis = 17_000L) {
        val result = catalog()
        status(when {
          result.code != HealthCode.VERIFIED -> result.code
          result.devices.none { control in it.controls } -> HealthCode.UNSUPPORTED
          else -> HealthCode.PREFLIGHT
        })
      })
    }
  }

  companion object {
    fun deviceId(target: String): String = "omniremote.device." + sha256(target).take(20)
  }
}
