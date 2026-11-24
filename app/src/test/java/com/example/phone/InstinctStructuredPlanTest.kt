package com.example.phone

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class InstinctStructuredPlanTest {
  private val now = Calendar.getInstance().apply { timeInMillis = 1_800_000_000_000 }
  private fun parse(json: String) = InstinctStructuredPlan.parse("plan-json " + json.trimIndent().replace("\n", ""), now)

  @Test fun `meeting objective compiles to a single plan with a timer and restoration`() {
    val parsed = InstinctStructuredPlan.parse(InstinctStructuredPlan.MEETING_EXAMPLE, now) as InstinctPlanSpec.Parse.Valid
    assertTrue(parsed.spec.steps.any { it is PlanStep.Save })
    assertTrue(parsed.spec.steps.last() is PlanStep.Restore)
    assertTrue(parsed.spec.steps.any { it is PlanStep.WaitFor && it.trigger == PlanTrigger.EVENT_END })
    assertTrue(parsed.spec.steps.flatMap { it.actions }.any { it.invocation?.capability == "insto_timer" })
  }

  @Test fun `unknown capabilities coercion dependency cycles and duplicate keys reject the whole plan`() {
    val invalid = listOf(
      """{"version":2,"steps":[]}""",
      """{"version":1,"version":1,"steps":[]}""",
      """{"version":1,"steps":[{"id":"a","kind":"action","capability":"shell","params":{}}]}""",
      """{"version":1,"steps":[{"id":"a","kind":"action","capability":"volume","params":{"percent":"20"}}]}""",
      """{"version":1,"steps":[{"id":"a","kind":"action","requires":["b"],"capability":"volume","params":{"percent":20}}]}""",
      """{version:1,"steps":[{"id":"a","kind":"action","capability":"volume","params":{"percent":20}}]}""",
      """{"version":1,"steps":[{"id":"a","kind":"action","capability":"volume","params":{"percent":20}}]} {}""",
    )
    invalid.forEach { assertTrue(it, parse(it) is InstinctPlanSpec.Parse.Invalid) }
  }

  @Test fun `only a terminal step can deliver an external intent`() {
    val open = """{"id":"open","kind":"action","capability":"open_resource","params":{"url":"https://example.org"}}"""
    val volume = """{"id":"volume","kind":"action","capability":"volume","params":{"percent":20}}"""
    assertTrue(parse("""{"version":1,"steps":[$volume,$open]}""") is InstinctPlanSpec.Parse.Valid)
    assertTrue(parse("""{"version":1,"steps":[$open,$volume]}""") is InstinctPlanSpec.Parse.Invalid)
  }
}
