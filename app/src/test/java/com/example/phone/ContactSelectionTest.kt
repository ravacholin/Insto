package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class ContactSelectionTest {
  @Test fun exactMatchWinsButApproximateMatchAlwaysRequiresChoice() {
    val ana = ContactOption("Ana", "+54 11 1111-2222")
    val anabel = ContactOption("Anabel", "+54 11 3333-4444")
    assertEquals(ContactResolution.Number("+541111112222"), ContactSelection.resolve("ana", listOf(anabel, ana)))
    assertEquals(ContactResolution.Choose(listOf(anabel)), ContactSelection.resolve("ana", listOf(anabel)))
  }
  @Test fun multipleNumbersAndDuplicateNamesCannotBeChosenArbitrarily() {
    val choices = listOf(ContactOption("Ana", "123456"), ContactOption("Ana", "654321"))
    assertEquals(ContactResolution.Choose(choices), ContactSelection.resolve("Ana", choices))
  }
  @Test fun truncatedLookupCannotAutomaticallyChooseASingleVisibleMatch() {
    val visible = ContactOption("Ana", "123456")
    assertEquals(ContactResolution.Choose(listOf(visible)), ContactSelection.resolve("Ana", listOf(visible), truncated = true))
    assertEquals(ContactResolution.Refine, ContactSelection.resolve("Ana", emptyList(), truncated = true))
  }
  @Test fun formattingDuplicatesCollapseAndInvalidNumbersAreExcluded() {
    assertEquals(ContactResolution.Number("+541111112222"), ContactSelection.resolve("Ana", listOf(
      ContactOption("Ana", "+54 11 1111-2222"), ContactOption("Ana", "+541111112222"), ContactOption("Ana", "*123#"))))
    assertEquals(ContactResolution.Missing, ContactSelection.resolve("Ana", listOf(ContactOption("Ana", "*123#"))))
  }
}
