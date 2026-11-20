package com.example.assist

import com.example.assist.WhatsAppSelectorFixtures.Expectation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppSelectorsTest {
  private fun check(fixture: String, element: String, expected: Expectation, actual: WhatsAppSelectors.Result) {
    val label = "$fixture $element"
    when (expected) {
      is Expectation.At -> {
        assertTrue("$label: $actual", actual is WhatsAppSelectors.Result.Found)
        actual as WhatsAppSelectors.Result.Found
        assertEquals(label, expected.bounds, actual.node.bounds)
        assertEquals(label, expected.strategy, actual.strategy)
      }
      is Expectation.Ambiguous -> assertEquals(label, WhatsAppSelectors.Result.Ambiguous(expected.strategy), actual)
      Expectation.None -> assertEquals(label, WhatsAppSelectors.Result.NotFound, actual)
    }
  }

  @Test fun everyFixtureFindsTheRightElementWithTheExpectedStrategy() {
    for (f in WhatsAppSelectorFixtures.all) {
      check(f.name, "send", f.send, WhatsAppSelectors.sendButton(f.tree, f.pkg))
      check(f.name, "composer", f.composer, WhatsAppSelectors.composer(f.tree, f.pkg, WhatsAppSelectorFixtures.DRAFT))
      check(f.name, "mic", f.mic, WhatsAppSelectors.voiceMic(f.tree, f.pkg))
      if (f.share != Expectation.None) check(f.name, "share", f.share, WhatsAppSelectors.sharePositive(f.tree, f.pkg))
      assertEquals(f.name, listOfNotNull(f.header), WhatsAppSelectors.headers(f.tree, f.pkg))
    }
  }

  @Test fun aLabelThatOnlyContainsSendIsNeverTheSendButton() {
    val tree = WhatsAppSelectorFixtures.Tree(WhatsAppSelectorFixtures.Node(UiNode(), listOf(
      WhatsAppSelectorFixtures.Node(UiNode(text = "Enviar ubicación", clickable = true, bounds = UiNode.Box(0, 0, 10, 10))),
      WhatsAppSelectorFixtures.Node(UiNode(description = "Enviar a…", clickable = true, bounds = UiNode.Box(0, 20, 10, 30))),
    )))
    assertEquals(WhatsAppSelectors.Result.NotFound, WhatsAppSelectors.sendButton(tree, "com.whatsapp"))
  }

  @Test fun anotherPackagesIdsDoNotCount() {
    val f = WhatsAppSelectorFixtures.waA
    assertEquals(WhatsAppSelectors.Result.NotFound, WhatsAppSelectors.composer(
      WhatsAppSelectorFixtures.Tree(WhatsAppSelectorFixtures.Node(UiNode())), "com.whatsapp", ""))
    // WA_A ids belong to com.whatsapp; looked up as Business they are not found and the label strategy decides.
    assertEquals("send.label", (WhatsAppSelectors.sendButton(f.tree, "com.whatsapp.w4b") as WhatsAppSelectors.Result.Found).strategy)
  }

  @Test fun twoEqualComposersAreAmbiguousUnlessOneHoldsTheDraft() {
    fun tree(vararg texts: String?) = WhatsAppSelectorFixtures.Tree(WhatsAppSelectorFixtures.Node(UiNode(), texts.mapIndexed { i, t ->
      WhatsAppSelectorFixtures.Node(UiNode(id = "com.whatsapp:id/entry", text = t, editable = true, bounds = UiNode.Box(0, i * 100, 10, i * 100 + 50)))
    }))
    assertEquals(WhatsAppSelectors.Result.Ambiguous("composer.id.entry"), WhatsAppSelectors.composer(tree(null, null), "com.whatsapp", "hola"))
    assertTrue(WhatsAppSelectors.composer(tree(null, "hola"), "com.whatsapp", "hola") is WhatsAppSelectors.Result.Found)
  }
}
