package com.example.phone

import android.content.Context
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What a receiver hands over to finish its broadcast: `goAsync()`'s PendingResult, or a fake in the tests. */
fun interface PushFinisher { fun finish() }

/** Calls [PushFinisher.finish] exactly once, whoever calls it and however many times. */
internal class OnceFinisher(private val target: PushFinisher?) {
  private val done = AtomicBoolean(false)
  fun finish() { if (done.compareAndSet(false, true)) runCatching { target?.finish() } }
  val finished: Boolean get() = done.get()
}

/** A bounded executor: [threads] named [name], at most [queue] tasks waiting; beyond that it refuses. */
internal fun boundedExecutor(name: String, queue: Int, threads: Int = 1): ThreadPoolExecutor =
  ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, ArrayBlockingQueue(queue)) { Thread(it, name).apply { isDaemon = true } }
    .apply { allowCoreThreadTimeOut(true) }

/**
 * Receiving a push (1.19.0). `onReceive()` runs on the main thread: it only takes `goAsync()` and queues the work on
 * one bounded thread, so it returns at once. That thread does the light part (the connector's checks, classifying,
 * storing in [InstinctPushInbox]), kicks [InstinctPushDispatcher] and waits for it at most
 * [InstinctPushPolicy.FAST_PATH_BUDGET_MILLIS]; then the broadcast is finished, exactly once. Whatever is still
 * pending is in the inbox, and a rescue job drains it if the process dies.
 */
object InstinctPushIntake {
  private val executor by lazy { boundedExecutor("insto-push-rx", InstinctPushPolicy.RECEIVE_QUEUE) }
  /** Runs the receiving work; throws [RejectedExecutionException] when saturated. Tests replace it. */
  internal var execute: (Runnable) -> Unit = { executor.execute(it) }
  /** The fast path's wait, on the receiving thread. Tests shorten it. */
  internal var budgetMillis = InstinctPushPolicy.FAST_PATH_BUDGET_MILLIS

  /**
   * [async] is `goAsync()` (null outside a real broadcast). [work] is the light part; it must not touch the network.
   * Returns at once.
   */
  fun receive(context: Context, async: () -> PushFinisher?, work: () -> Unit) {
    val app = context.applicationContext
    val finisher = OnceFinisher(runCatching(async).getOrNull())
    val task = Runnable {
      try {
        runCatching(work)
        InstinctPushDispatcher.kick(app)
        InstinctPushDispatcher.awaitIdle(budgetMillis)
      } finally { finisher.finish() }
    }
    try { execute(task) } catch (_: RejectedExecutionException) {
      // Saturated: the distributor gets no acknowledgement for what was not stored, and the poll covers it.
      runCatching { InstinctDoorbell.settings(app).update { it.copy(queueRejected = it.queueRejected + 1, lastError = "cola de recepción llena") } }
      finisher.finish()
    }
  }
}

/**
 * Dispatching what the inbox holds, on one thread. An order: [InstinctMailboxSignature.verify] (signature, window,
 * seen), [InstinctOrderClaims.claimOrders] on the journal every transport shares (the claim and the order's rows in one
 * transaction, 1.20.0), then [InstinctProposalIntake.onMailbox],
 * exactly like a polled order; the sheet is not read. A ring: the gate, and the poll on the mailbox's own thread. An
 * order an earlier process claimed but may not have handed over is never run again: the log says «incierta».
 */
object InstinctPushDispatcher {
  private val executor by lazy { boundedExecutor("insto-push-dispatch", 4) }
  internal var execute: (Runnable) -> Unit = { executor.execute(it) }
  private val idle = Object()
  private var busy = 0
  private var kickPending = false
  private val drainLock = Any()
  /** Signals whose HTTP request runs now, in this process. */
  private val inFlight = HashSet<Long>()
  /** Order entries this process claimed and is handing over; one claimed but not here comes from a dead process. */
  private val handing = HashSet<Long>()

  const val UNCERTAIN_DETAIL = "Insto se cerró mientras recibía esta orden por el timbre; puede haberse ejecutado o no. No se repite: revisalo."

  fun inbox(context: Context) = InstinctPushInbox(File(context.applicationContext.noBackupFilesDir, InstinctPushInbox.FILE)) { InstinctMailbox.clock() }

  /** Drains soon, on the dispatch thread. Kicks while one is queued fold into it; one during a drain drains again. */
  fun kick(context: Context) {
    val app = context.applicationContext
    synchronized(idle) { if (kickPending) return; kickPending = true; busy++ }
    try {
      execute(Runnable {
        synchronized(idle) { kickPending = false }
        try { drain(app) } finally { done() }
      })
    } catch (_: RejectedExecutionException) { synchronized(idle) { kickPending = false }; done() }
  }

  /** Waits (never on the main thread) until nothing is queued or running, at most [millis]. True when idle. */
  fun awaitIdle(millis: Long): Boolean = synchronized(idle) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
    while (busy > 0) {
      val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
      if (left <= 0) return@synchronized false
      idle.wait(left)
    }
    true
  }

  private fun done() = synchronized(idle) { busy--; idle.notifyAll() }

  /** Every entry, oldest first. Also the rescue job's and the boot's work. */
  fun drain(context: Context) = synchronized(drainLock) {
    val app = context.applicationContext
    val inbox = inbox(app)
    // Each entry once per drain, oldest first; a ring added meanwhile (a refused order's fallback) is seen too.
    val visited = HashSet<Long>()
    while (true) {
      val entry = inbox.entries().firstOrNull { it.seq !in visited } ?: break
      visited += entry.seq
      when (entry.kind) {
        PushEntry.Kind.ORDER -> order(app, inbox, entry)
        PushEntry.Kind.RING -> ring(app, inbox, entry)
        PushEntry.Kind.REFERENCE -> reference(app, inbox, entry)
      }
    }
    // 1.20.0: the same job also continues or settles what the execution journal holds unfinished.
    runCatching { InstinctExecutionRecoveryManager.recover(app) }
    if (inbox.size() == 0 && !InstinctExecutionRecoveryManager.pending(app)) InstinctDoorbell.cancel(app, InstinctDoorbell.KIND_DRAIN)
  }

  private fun reference(app: Context, inbox: InstinctPushInbox, entry: PushEntry) {
    val started = synchronized(inFlight) { inFlight.add(entry.seq) }
    if (!started) return
    synchronized(idle) { busy++ }
    val fetch = Runnable {
      try { fetchReference(app, inbox, entry) } finally {
        synchronized(inFlight) { inFlight.remove(entry.seq) }
        done()
      }
    }
    try {
      InstinctMailbox.background(fetch)
    } catch (_: RejectedExecutionException) {
      synchronized(inFlight) { inFlight.remove(entry.seq) }
      done()
      InstinctDoorbell.schedule(app, InstinctDoorbell.KIND_DRAIN, InstinctPushPolicy.RESCUE_DELAY_MILLIS)
    }
  }

  private fun fetchReference(app: Context, inbox: InstinctPushInbox, entry: PushEntry) {
    val reference = checkNotNull(entry.reference)
    val log = InstinctLogSettings(app)
    val url = log.url()
    val token = log.token()
    if (url == null || token == null || InstinctMailboxSettings(app).blocker() != null) {
      inbox.remove(entry.seq)
      return
    }
    if (runCatching { InstinctMailbox.seen(app).contains(reference.id) }.getOrDefault(false)) {
      inbox.remove(entry.seq)
      return
    }
    val fetched = runCatching { InstinctMailbox.client().fetchOrder(url, token, reference.id) }
      .getOrElse { InstinctMailboxClient.Fetch.Failed(InstinctLogClient.Failure.NETWORK.label) }
    when (fetched) {
      is InstinctMailboxClient.Fetch.Failed -> {
        if (entry.attempts + 1 >= InstinctPushPolicy.MAX_CLAIM_ATTEMPTS) inbox.remove(entry.seq)
        else {
          inbox.bumpAttempts(entry.seq)
          InstinctDoorbell.schedule(app, InstinctDoorbell.KIND_DRAIN, InstinctPushPolicy.RESCUE_DELAY_MILLIS)
        }
      }
      is InstinctMailboxClient.Fetch.Orders -> {
        if (url != log.url() || token != log.token()) {
          inbox.remove(entry.seq)
          return
        }
        val candidate = fetched.orders.singleOrNull()
        if (candidate == null || !reference.matches(candidate)) {
          if (fetched.orders.isNotEmpty()) InstinctDoorbell.settings(app).update {
            it.copy(badPayloads = it.badPayloads + 1, lastError = "la orden recuperada no coincide con el timbre")
          }
          inbox.remove(entry.seq)
          return
        }
        order(app, inbox, entry.copy(order = candidate))
      }
    }
  }

  private fun order(app: Context, inbox: InstinctPushInbox, entry: PushEntry) {
    val order = entry.order ?: run { inbox.remove(entry.seq); return }
    val bell = InstinctDoorbell.settings(app)
    if (entry.claimed && entry.seq !in synchronized(handing) { handing.toSet() }) {
      // Claimed by a process that died before handing it over. Since 1.20.0 the claim and the order's rows are one
      // transaction: if the journal has them, recovery decides from their state, never by running it again here.
      val journal = InstinctExecutions.journal(app)
      val records = runCatching { journal.forMessage(order.id) }.getOrNull()
      if (records == null) {
        // The journal cannot be read: keep the entry, the next drain asks again.
        bell.update { it.copy(storageFailures = it.storageFailures + 1, lastError = "no se pudo leer el diario de ejecución") }
        InstinctDoorbell.schedule(app, InstinctDoorbell.KIND_DRAIN, InstinctPushPolicy.RESCUE_DELAY_MILLIS)
        return
      }
      if (records.isEmpty()) {
        // 1.19.0 claimed it and recorded nothing: maybe it ran, maybe not. Never again; kept as uncertain.
        runCatching { journal.recordLegacyUncertain(order.id, InstinctProposalProtocol.orders(order.lines.joinToString("\n")),
          InstinctMailboxSignature.messageId(order.id), UNCERTAIN_DETAIL) }
        reportUncertain(app, order)
        bell.update { it.copy(uncertain = it.uncertain + 1, lastError = "Insto se cerró con una orden recibida a medias") }
      } else InstinctExecutionRecoveryManager.recover(app)
      inbox.remove(entry.seq); return
    }
    // Revalidated now, whatever happened before: the mailbox may have been turned off, the key changed.
    val key = InstinctMailboxSettings(app).key()
    if (InstinctMailboxSettings(app).blocker() != null || key == null) { inbox.remove(entry.seq); return }
    val seen = InstinctMailbox.seen(app, entry.receivedAt)
    when (InstinctMailboxSignature.verify(key, order, InstinctMailbox.clock(), seen::contains)) {
      InstinctMailboxSignature.Verdict.VALID -> {
        // 1.20.0: claiming the id and recording each of its orders are one transaction in the journal.
        val transport = if (entry.reference == null) ExecutionTransport.PUSH else ExecutionTransport.PUSH_REFERENCE
        val won = try { seen.claimOrders(listOf(order), transport) } catch (_: Throwable) {
          // The journal could not be written: the order does not run without its record. Retried, then left to the poll.
          bell.update { it.copy(storageFailures = it.storageFailures + 1, lastError = "no se pudo guardar el registro de órdenes vistas") }
          if (entry.attempts + 1 >= InstinctPushPolicy.MAX_CLAIM_ATTEMPTS) { inbox.remove(entry.seq); if (entry.reference == null) InstinctDoorbell.ringFallback(app) }
          else { inbox.bumpAttempts(entry.seq); InstinctDoorbell.schedule(app, InstinctDoorbell.KIND_DRAIN, InstinctPushPolicy.RESCUE_DELAY_MILLIS) }
          return
        }
        if (order.id.lowercase() !in won) {
          bell.update { it.copy(duplicates = it.duplicates + 1) }
          inbox.remove(entry.seq); return
        }
        synchronized(handing) { handing += entry.seq }
        try {
          inbox.markClaimed(entry.seq)
          bell.update {
            if (entry.reference == null) it.copy(directOrders = it.directOrders + 1, lastDirectAt = InstinctDoorbell.clock())
            else it.copy(referencedOrders = it.referencedOrders + 1)
          }
          runCatching { InstinctProposalIntake.onMailbox(app, order) }
          // Safe now: whatever onMailbox did not finish is in the journal, and recovery continues it.
          InstinctDoorbell.afterDirect(app, order)
          inbox.remove(entry.seq)
        } finally { synchronized(handing) { handing -= entry.seq } }
      }
      // A poll (or an earlier push) took it first: it ran there.
      InstinctMailboxSignature.Verdict.REPEATED -> {
        bell.update { it.copy(duplicates = it.duplicates + 1) }
        inbox.remove(entry.seq)
      }
      // Runs nothing, answers nobody and polls, as a plain ring does.
      InstinctMailboxSignature.Verdict.BAD_SIGNATURE, InstinctMailboxSignature.Verdict.EXPIRED -> {
        bell.update { it.copy(badPayloads = it.badPayloads + 1, lastError = "una orden del timbre no pasó la firma o venció") }
        inbox.remove(entry.seq)
        if (entry.reference == null) InstinctDoorbell.ringFallback(app)
      }
    }
  }

  private fun ring(app: Context, inbox: InstinctPushInbox, entry: PushEntry) {
    if (InstinctMailboxSettings(app).blocker() != null) { inbox.remove(entry.seq); return }
    if (synchronized(inFlight) { entry.seq in inFlight }) return
    InstinctDoorbell.pollAsTheGateSays(app, removed = { inbox.remove(entry.seq) }) {
      synchronized(inFlight) { inFlight += entry.seq }
      synchronized(idle) { busy++ }
      val poll = Runnable {
        try { InstinctDoorbell.pollForRing(app) } finally {
          // Removed only now: if the process dies during the poll, the rescue job polls again.
          inbox.remove(entry.seq)
          synchronized(inFlight) { inFlight -= entry.seq }
          done()
        }
      }
      try { InstinctMailbox.background(poll) } catch (_: RejectedExecutionException) { synchronized(inFlight) { inFlight -= entry.seq }; done() }
    }
  }

  /** «incierta» for each line of [order], to the log only, as every mailbox acknowledgement. */
  private fun reportUncertain(app: Context, order: MailboxOrder) {
    if (InstinctProposalSettings(app).replyMode() == InstinctReplyMode.NEVER) return
    val lines = InstinctProposalProtocol.orders(order.lines.joinToString("\n"))
    InstinctLog.enqueue(app, lines.map { AckEntry(AckKind.UNCERTAIN, it, UNCERTAIN_DETAIL) }, InstinctMailboxSignature.messageId(order.id))
  }

  /** Tests only. */
  internal fun reset() {
    synchronized(idle) { busy = 0; kickPending = false }
    synchronized(inFlight) { inFlight.clear() }
    synchronized(handing) { handing.clear() }
  }
}
