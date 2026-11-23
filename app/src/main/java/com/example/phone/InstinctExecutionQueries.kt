package com.example.phone

import android.content.Context
import java.util.Calendar

/** «ver ejecuciones» and «estado de ejecución <id>» (1.20.0): read-only answers from the journal. */
object InstinctExecutionQueries {
  private fun clock(millis: Long): String = InstinctDeferral.clock(millis, Calendar.getInstance())

  fun list(context: Context, showQuestions: Boolean = true): String {
    val journal = InstinctExecutions.journal(context)
    val records = journal.recent(ExecutionJournalPolicy.LIST)
    val now = InstinctExecutionJournal.clock()
    return if (showQuestions || records.isEmpty()) ExecutionText.list(records, now, ::clock)
      else records.joinToString(" | ") { ExecutionText.line(it, now, false, ::clock) }
  }

  fun status(context: Context, shortId: String, showQuestion: Boolean = true): String {
    val short = ExecutionIds.parseShort(shortId) ?: return "«$shortId» no es un id de ejecución: son 8 caracteres de «ver ejecuciones»."
    val row = InstinctExecutions.journal(context).byShort(short)
    val now = InstinctExecutionJournal.clock()
    return if (row == null) ExecutionText.status(null, short, now, ::clock) else ExecutionText.line(row, now, showQuestion, ::clock)
  }
}
