package com.example.testsupport

import com.example.phone.*

/** Adapter tests script a classification from their known command fixtures; no network or credentials. */
fun localActionDecisions() = HybridPhoneRouter({true},DecisionClassifier { text ->
  val candidate=(PhoneCommandParser().parse(text) as? PhoneRoute.Local)?.command
  val choice=if(candidate==null) "instinct" else LocalIntentRegistry.default.definitions.firstOrNull {it.accepts(candidate)}?.id ?: "unsupported"
  SemanticDecision(choice,.99,.99,.99,.99)
})
