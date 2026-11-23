package com.example.phone

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Notification and alarm are projections of the existing execution row. The journal decides every touch. */
internal object InstinctQuestionNotifications {
  const val CHANNEL = "instinct-questions"
  const val ACTION = "com.example.phone.INSTINCT_QUESTION_ACTION"
  const val TICK = "com.example.phone.INSTINCT_QUESTION_TICK"
  private const val NOTIFICATION_ID = 7_318
  private const val PUBLIC_CONTENT = "question-public-content"
  private const val PREVIEW = "question-preview"
  private const val ID = "insto.question.execution"
  private const val CHOICE = "insto.question.choice"
  private const val DEFAULT_CHOICE = Int.MIN_VALUE
  private val lock = Any()

  fun receive(context: Context, record: ExecutionRecord, question: InstinctQuestion, source: ProposalSource) {
    val app = context.applicationContext
    val policy = InstinctAutonomySettings(app).policy()
    val level = policy.level(InstinctCategory.PREGUNTAS)
    if (level == InstinctAutonomy.NUNCA) return reject(app, record, InstinctAutonomyPolicy.REFUSED)
    if (question.actions.any { actionRefused(app, it) }) return reject(app, record, InstinctAutonomyPolicy.REFUSED)
    if (!InstinctLogSettings(app).configured()) return reject(app, record, InstinctQuestion.NEEDS_LOG)
    val needsPreview = level == InstinctAutonomy.CONFIRMAR ||
      InstinctSourceCap.apply(AutonomyDecision.Run, source, InstinctMailboxSettings(app).whatsappCap()) is AutonomyDecision.Confirm
    val journal = InstinctExecutions.journal(app)
    when (val admission = runCatching { journal.startQuestion(record.executionId, question, needsPreview) }.getOrNull()) {
      InstinctExecutionJournal.QuestionAdmission.Accepted -> refresh(app)
      is InstinctExecutionJournal.QuestionAdmission.Refused -> reject(app, record, admission.reason)
      InstinctExecutionJournal.QuestionAdmission.Unavailable -> Unit
      null -> reject(app, record, InstinctProposalIntake.JOURNAL_FAILED)
    }
  }

  fun reject(context: Context, record: ExecutionRecord, reason: String, state: ExecutionState = ExecutionState.FAILED) {
    if (!InstinctExecutions.move(context, record.executionId, state, reason = reason,
        from = setOf(ExecutionState.RECEIVED, ExecutionState.VALIDATED, ExecutionState.READY))) return
    InstinctProposalIntake.diagnose(InstinctProposalIntake.Diagnosis.FAILED, reason)
    if (InstinctLogSettings(context).configured())
      InstinctLog.enqueue(context, listOf(AckEntry(AckKind.ERROR, record.line, reason)), record.logMessage)
  }

  private fun actionRefused(context: Context, line: String): Boolean {
    val now = java.util.Calendar.getInstance().apply { timeInMillis = InstinctExecutionJournal.clock() }
    val command = InstinctProposalValidator.backgroundAction(line, PhoneCommandParser(), now) ?: return true
    return InstinctProposalCenter.refusedByPolicy(context, command)
  }

  fun probe(context: Context, source: ProposalSource, question: InstinctQuestion? = null): InstinctProbe.Verdict {
    if (question?.actions?.any { actionRefused(context, it) } == true) return InstinctProbe.Verdict.Refused
    val policy = InstinctAutonomySettings(context).policy()
    val category = InstinctCategory.PREGUNTAS
    val level = policy.level(category)
    if (level == InstinctAutonomy.NUNCA) return InstinctProbe.Verdict.Refused
    if (!InstinctLogSettings(context).configured()) return InstinctProbe.Verdict.Invalid(InstinctQuestion.NEEDS_LOG)
    val limit = runCatching { InstinctExecutions.journal(context).questionLimit() }.getOrNull()
    if (limit != null) return InstinctProbe.Verdict.Invalid(limit)
    val now = InstinctExecutionJournal.clock()
    if (InstinctQuestion.quietEnd(policy.quiet, now) != null) return InstinctProbe.Verdict.Waits(category.label, InstinctAutonomyPolicy.QUIET)
    if (level == InstinctAutonomy.CONFIRMAR) return InstinctProbe.Verdict.Waits(category.label, "la persona pide confirmación")
    if (InstinctSourceCap.apply(AutonomyDecision.Run, source, InstinctMailboxSettings(context).whatsappCap()) is AutonomyDecision.Confirm)
      return InstinctProbe.Verdict.Waits(category.label, InstinctSourceCap.REASON)
    return InstinctProbe.Verdict.Alone(category.label)
  }

  fun recoverSoon(context: Context) {
    val app = context.applicationContext
    if (InstinctExecutions.existingJournal(app) == null) return
    InstinctMailbox.background(Runnable { runCatching { InstinctExecutionRecoveryManager.recover(app, questionsOnly = true) } })
  }

  fun refresh(context: Context) = synchronized(lock) { runCatching { refreshLocked(context.applicationContext) } }

  private fun refreshLocked(app: Context) {
    val journal = InstinctExecutions.journal(app)
    val manager = app.getSystemService(NotificationManager::class.java) ?: return
    val now = InstinctExecutionJournal.clock()
    val policy = InstinctAutonomySettings(app).policy()
    val disabled = !InstinctProposalSettings(app).isEnabled() || policy.level(InstinctCategory.PREGUNTAS) == InstinctAutonomy.NUNCA
    val quietUntil = InstinctQuestion.quietEnd(policy.quiet, now)
    val pending = journal.unfinished(InstinctQuestion.PENDING)
    val live = pending.filter { !disabled && it.deadline != null && it.deadline > now }
    val deadline = live.mapNotNull { it.deadline }.minOrNull()
    arm(app, deadline?.let { minOf(it, quietUntil ?: it) })

    // A notification already shown can outlive the process. Use its journal id as the tag and leave it in place.
    val active = manager.activeNotifications.filter { it.id == NOTIFICATION_ID }.associateBy { it.tag }
    val liveIds = live.mapTo(HashSet()) { it.executionId }
    active.keys.filterNot { it in liveIds }.forEach { manager.cancel(it, NOTIFICATION_ID) }
    pending.filterNot { it.executionId in liveIds }.forEach { row ->
      val result = journal.finishQuestion(row.executionId, if (disabled) InstinctQuestion.DISCARD else null)
      if (result != null) completed(app, row.executionId, result)
    }
    if (quietUntil != null || live.isEmpty() || !InstinctProposalCenter.canNotify(app, CHANNEL)) return
    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
    val showOnLockScreen = InstinctAutonomySettings(app).showQuestionsOnLockScreen()
    for (row in live) {
      val preview = row.state == ExecutionState.QUESTION_CONFIRM
      val publicContent = !preview && showOnLockScreen
      val shown = active[row.executionId]?.notification
      if (shown?.extras?.getBoolean(PREVIEW) == preview && shown.extras.containsKey(PUBLIC_CONTENT) &&
        shown.extras.getBoolean(PUBLIC_CONTENT) == publicContent) continue
      val question = InstinctQuestion.decode(row.command, row.deadline) ?: continue
      val notification = InstinctProposalCenter.builder(app, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(if (preview) "Instinct quiere hacerte una pregunta" else "Insto te pregunta")
        .setContentText(question.notificationText()).setStyle(Notification.BigTextStyle().bigText(question.notificationText()))
        .setVisibility(if (publicContent) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion(app))
        .setOnlyAlertOnce(true)
        .setExtras(Bundle().apply {
          putBoolean(PREVIEW, preview)
          putBoolean(PUBLIC_CONTENT, publicContent)
        })
        .setDeleteIntent(action(app, row.executionId, InstinctQuestion.DISCARD))
      if (preview) {
        val review = PendingIntent.getActivity(app, 0, Intent(app, InstinctQuestionPreviewActivity::class.java)
          .setData(Uri.parse("insto-question://preview/${row.executionId}")).putExtra(ID, row.executionId),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notification.setContentIntent(review).addAction(Notification.Action.Builder(null, "Revisar", review).build())
          .addAction(Notification.Action.Builder(null, "Descartar", action(app, row.executionId, InstinctQuestion.DISCARD)).build())
      } else {
        question.options.forEachIndexed { index, option ->
          notification.addAction(Notification.Action.Builder(null, option, action(app, row.executionId, index)).build())
        }
      }
      manager.notify(row.executionId, NOTIFICATION_ID, notification.build())
    }
  }

  internal fun publicVersion(app: Context): Notification = InstinctProposalCenter.builder(app, CHANNEL)
    .setSmallIcon(android.R.drawable.ic_dialog_info)
    .setContentTitle("Insto")
    .setContentText("Tenés una pregunta pendiente.")
    .setVisibility(Notification.VISIBILITY_PUBLIC)
    .build()

  private fun arm(app: Context, at: Long?) {
    val manager = app.getSystemService(AlarmManager::class.java) ?: return
    val pending = PendingIntent.getBroadcast(app, 0, Intent(app, InstinctProposalReceiver::class.java).setAction(TICK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    if (at == null) manager.cancel(pending)
    else if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
      try {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
      } catch (_: SecurityException) {
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
      }
    } else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
  }

  private fun action(app: Context, id: String, choice: Int): PendingIntent =
    PendingIntent.getBroadcast(app, 0, Intent(app, InstinctProposalReceiver::class.java).setAction(ACTION)
      .setData(Uri.parse("insto-question://answer/$id/$choice")).putExtra(ID, id).putExtra(CHOICE, choice),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  fun handle(context: Context, intent: Intent) {
    if (intent.action != ACTION) { refresh(context); return }
    val id = intent.getStringExtra(ID) ?: return
    val choice = intent.getIntExtra(CHOICE, DEFAULT_CHOICE)
    if (choice == DEFAULT_CHOICE) return
    val selected = finish(context, id, choice, showFeedback = choice != InstinctQuestion.DISCARD)
    if (selected != null) {
      InstinctExecutionRecoveryManager.later(context)
      InstinctProposalCenter.dispatchDecision(context, selected)
    }
  }

  private fun finish(context: Context, id: String, choice: Int, showFeedback: Boolean): ExecutionRecord? = synchronized(lock) {
    val journal = InstinctExecutions.journal(context)
    val permitted = InstinctProposalSettings(context).isEnabled() &&
      InstinctAutonomySettings(context).policy().level(InstinctCategory.PREGUNTAS) != InstinctAutonomy.NUNCA
    val outcome = journal.finishQuestion(id, if (permitted) choice else InstinctQuestion.DISCARD)
    if (outcome != null) completed(context, id, outcome)
    if (showFeedback && outcome?.state != ExecutionState.ANSWERED) {
      val row = journal.get(id)
      feedback(context, when (row?.state) {
        ExecutionState.EXPIRED -> "La pregunta ya venció."
        ExecutionState.ANSWERED -> "Ya respondiste esta pregunta."
        else -> "Esta pregunta ya no está disponible."
      })
    }
    refresh(context)
    outcome?.action
  }

  private fun completed(context: Context, id: String, outcome: QuestionOutcome) {
    InstinctLog.questionCompleted(context)
    context.getSystemService(NotificationManager::class.java)?.cancel(id, NOTIFICATION_ID)
    InstinctTrust.record(context, InstinctCategory.PREGUNTAS, when (outcome.state) {
      ExecutionState.ANSWERED -> TrustEvent.EJECUTADA
      ExecutionState.EXPIRED -> TrustEvent.VENCIDA
      else -> TrustEvent.DESCARTADA
    })
  }

  private fun feedback(context: Context, message: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show() }
  }

  fun previewId(intent: Intent): String? = intent.getStringExtra(ID)

  fun preview(context: Context, id: String): InstinctQuestion? {
    val row = InstinctExecutions.journal(context).get(id) ?: return null
    if (row.state != ExecutionState.QUESTION_CONFIRM || (row.deadline ?: 0) <= InstinctExecutionJournal.clock()) return null
    return InstinctQuestion.decode(row.command, row.deadline)
  }

  fun approve(context: Context, id: String) = synchronized(lock) {
    if (preview(context, id) != null && InstinctProposalSettings(context).isEnabled() &&
      InstinctAutonomySettings(context).policy().level(InstinctCategory.PREGUNTAS) != InstinctAutonomy.NUNCA) {
      InstinctExecutions.move(context, id, ExecutionState.ASKING, from = setOf(ExecutionState.QUESTION_CONFIRM))
    } else feedback(context, "Esta pregunta ya no está disponible.")
    refresh(context)
  }

  fun discard(context: Context, id: String) { finish(context, id, InstinctQuestion.DISCARD, showFeedback = false) }
}

/** Opened only by «Revisar» for the Confirmar policy. Option buttons use broadcasts instead. */
class InstinctQuestionPreviewActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val id = InstinctQuestionNotifications.previewId(intent)
    val question = id?.let { runCatching { InstinctQuestionNotifications.preview(this, it) }.getOrNull() }
    if (id == null || question == null) {
      Toast.makeText(this, "Esta pregunta ya no está disponible.", Toast.LENGTH_SHORT).show()
      finish()
      return
    }
    AlertDialog.Builder(this)
      .setTitle("Instinct quiere hacerte una pregunta")
      .setMessage(question.notificationText() + if (question.actions.isEmpty()) "\n\n" + question.options.joinToString(" · ") else "")
      .setPositiveButton("Mostrar pregunta") { _, _ -> InstinctQuestionNotifications.approve(this, id) }
      .setNegativeButton("Descartar") { _, _ -> InstinctQuestionNotifications.discard(this, id) }
      .setOnDismissListener { finish() }
      .show()
  }
}
