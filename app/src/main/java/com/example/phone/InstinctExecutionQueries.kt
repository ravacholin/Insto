package com.example.phone

import android.content.Context
import java.util.Calendar

/** «ver ejecuciones» and «estado de ejecución <id>» (1.20.0): read-only answers from the journal. */
object InstinctExecutionQueries {
  private fun clock(millis: Long): String = InstinctDeferral.clock(millis, Calendar.getInstance())

  fun list(context: Context): String {
    val journal = InstinctExecutions.journal(context)
    return ExecutionText.list(journal.recent(ExecutionJournalPolicy.LIST), InstinctExecutionJournal.clock(), ::clock)
  }

  fun status(context: Context, shortId: String): String {
    val short = ExecutionIds.parseShort(shortId) ?: return "«$shortId» no es un id de ejecución: son 8 caracteres de «ver ejecuciones»."
    return ExecutionText.status(InstinctExecutions.journal(context).byShort(short), short, InstinctExecutionJournal.clock(), ::clock)
  }
}
