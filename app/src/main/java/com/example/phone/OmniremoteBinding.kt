package com.example.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import com.example.external.IRemoteCommandService
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One short-lived session per interaction. A failed connection is released before the next call. */
internal class OmniremoteBinding(private val callTimeoutMillis: Long = 4_000) {
  class Unavailable(val reason: String = "Omniremote está temporalmente inaccesible.",
    val health: HealthCode = HealthCode.PROVIDER_DOWN) : Exception(reason)

  fun open(context: Context) = Session(context.applicationContext)

  inner class Session internal constructor(private val context: Context) : Closeable {
    private var connection: Connection? = null

    fun <T> call(deadline: Long, operation: (IRemoteCommandService) -> T): T {
      // Android delivers these ServiceConnection callbacks on the main thread.
      if (Looper.myLooper() == Looper.getMainLooper()) throw Unavailable()
      val active = connection ?: connect(deadline)
      try {
        return active.call(deadline, operation)
      } catch (error: Exception) {
        close()
        throw error
      }
    }

    private fun connect(deadline: Long): Connection {
      if (SystemClock.elapsedRealtime() >= deadline) throw Unavailable()
      val pending = Connection()
      connection = pending
      try {
        val bound = context.bindService(Intent(ACTION).setComponent(COMPONENT), pending, Context.BIND_AUTO_CREATE)
        if (!bound) throw Unavailable("Falta un servicio local compatible de Omniremote.", HealthCode.UNSUPPORTED)
        val version = pending.call(deadline) { it.apiVersion }
        if (version < API_VERSION) throw Unavailable("La versión de la API local de Omniremote no es compatible.", HealthCode.VERSION)
        return pending
      } catch (error: Exception) {
        close()
        throw error
      }
    }

    override fun close() {
      val active = connection ?: return
      connection = null
      active.disconnect()
      try {
        context.unbindService(active)
      } catch (_: IllegalArgumentException) {
        // A failed bind may leave no registered connection to release.
      }
    }
  }

  private inner class Connection : ServiceConnection {
    private val connected = CompletableFuture<IRemoteCommandService>()
    private var disconnected = false
    private var inFlight: FutureTask<*>? = null

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      connected.complete(IRemoteCommandService.Stub.asInterface(binder))
    }

    override fun onServiceDisconnected(name: ComponentName) = disconnect()
    override fun onBindingDied(name: ComponentName) = disconnect()
    override fun onNullBinding(name: ComponentName) =
      disconnect(Unavailable("Falta un servicio local compatible de Omniremote.", HealthCode.UNSUPPORTED))

    @Synchronized
    fun disconnect(error: Unavailable = Unavailable()) {
      disconnected = true
      connected.completeExceptionally(error)
      inFlight?.cancel(true)
    }

    fun <T> call(deadline: Long, operation: (IRemoteCommandService) -> T): T {
      val callDeadline = minOf(deadline, SystemClock.elapsedRealtime() + callTimeoutMillis)
      val api = await(connected, callDeadline)
      val task = FutureTask {
        if (SystemClock.elapsedRealtime() >= callDeadline) throw Unavailable()
        operation(api)
      }
      try {
        synchronized(this) {
          if (disconnected) throw Unavailable()
          inFlight = task
          workers.execute(task)
        }
        return await(task, callDeadline)
      } catch (_: RejectedExecutionException) {
        throw Unavailable()
      } finally {
        synchronized(this) { inFlight = null }
        task.cancel(true)
      }
    }

    private fun <T> await(future: Future<T>, deadline: Long): T {
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining <= 0) throw Unavailable()
      try {
        return future.get(remaining, TimeUnit.MILLISECONDS)
      } catch (_: TimeoutException) {
        throw Unavailable(health = HealthCode.TIMEOUT)
      } catch (_: CancellationException) {
        throw Unavailable()
      } catch (error: ExecutionException) {
        when (val cause = error.cause) {
          is RemoteException -> throw Unavailable()
          null -> throw Unavailable()
          else -> throw cause
        }
      }
    }
  }

  companion object {
    const val API_VERSION = 1
    const val PACKAGE = "com.aistudio.omnitvremote.qvrtm"
    private const val ACTION = "$PACKAGE.BIND_EXTERNAL_CONTROL"
    private val COMPONENT = ComponentName(PACKAGE, "com.example.external.RemoteCommandService")

    // A synchronous Binder transaction can outlive its client deadline. Bound the abandoned workers.
    private val workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue()) { task ->
      Thread(task, "insto-omniremote-binder").apply { isDaemon = true }
    }
  }
}
