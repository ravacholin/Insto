package com.example

import com.example.assist.AudioShareMachine
import com.example.assist.SendStep
import com.example.assist.TextSendMachine
import com.example.assist.UiBounds
import com.example.assist.UiNode
import com.example.assist.WhatsAppScreenReader
import com.example.assist.WhatsAppSendAction
import com.example.assist.WhatsAppSendEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppSendMachineTest {
  private val request = 7L
  private val phone = "+447700900123"
  private val name = "Instinct"
  private val audio = "instinct_12345678.m4a"

  @Test fun textRequiresCurrentLaunchRecipientAndExactDraftThenConfirmsOnlyOnce() {
    val machine = TextSendMachine(request, "Mensaje sintético", phone, name)
    var state: TextSendMachine.State = TextSendMachine.State.Waiting
    fun event(event: WhatsAppSendEvent): WhatsAppSendAction {
      val next = machine.reduce(state, event)
      state = next.state
      return next.action
    }
    assertTrue(event(screen("text-draft", launch = request - 1)) is WhatsAppSendAction.Wait)
    assertTrue(event(screen("wrong-contact")) is WhatsAppSendAction.Wait)
    assertTrue(event(screen("empty-chat")) is WhatsAppSendAction.Wait)
    assertEquals(SendStep.TEXT, (event(screen("text-draft")) as WhatsAppSendAction.Touch).step)
    assertEquals(WhatsAppSendAction.None, event(screen("text-draft")))
    assertEquals(WhatsAppSendAction.Wait(delayMs = 250), event(gesture(SendStep.TEXT)))
    assertEquals(WhatsAppSendAction.Confirm, event(screen("empty-chat", at = 1_000)))
    assertEquals(WhatsAppSendAction.None, event(screen("text-draft")))
  }

  @Test fun cancelledTextGestureAndTimedOutConfirmationAreTerminal() {
    val machine = TextSendMachine(request, "Mensaje sintético", phone, name)
    val touch = machine.reduce(TextSendMachine.State.Waiting, screen("text-draft"))
    val cancelled = machine.reduce(touch.state, gesture(SendStep.TEXT, completed = false))
    assertEquals(WhatsAppSendAction.Abort, cancelled.action)
    assertEquals(WhatsAppSendAction.None, machine.reduce(cancelled.state, screen("text-draft")).action)

    val checking = machine.reduce(touch.state, gesture(SendStep.TEXT))
    assertTrue(machine.reduce(checking.state, WhatsAppSendEvent.Elapsed(3_499)).action is WhatsAppSendAction.Wait)
    val timeout = machine.reduce(checking.state, WhatsAppSendEvent.Elapsed(3_500))
    assertEquals(WhatsAppSendAction.Abort, timeout.action)
    assertEquals(WhatsAppSendAction.None, machine.reduce(timeout.state, screen("empty-chat")).action)
  }

  @Test fun audioAllowsBothOrdersAndDirectSendWithAStableEmptyChat() {
    val orders = listOf(
      listOf("share-dialog" to SendStep.SHARE_CONFIRMATION, "audio-preview" to SendStep.SHARE_SEND),
      listOf("audio-preview" to SendStep.SHARE_SEND, "share-dialog" to SendStep.SHARE_CONFIRMATION),
      listOf("audio-preview" to SendStep.SHARE_SEND),
    )
    for (order in orders) {
      val machine = AudioShareMachine(request, audio, null, phone, name)
      var state = AudioShareMachine.State()
      fun event(event: WhatsAppSendEvent): WhatsAppSendAction {
        val next = machine.reduce(state, event)
        state = next.state
        return next.action
      }
      for ((fixture, step) in order) {
        val snapshot = screen(fixture, fresh = true)
        assertEquals(step, (event(snapshot) as WhatsAppSendAction.Touch).step)
        assertFalse(event(snapshot) is WhatsAppSendAction.Touch)
        event(gesture(step))
        assertFalse(event(snapshot) is WhatsAppSendAction.Touch)
      }
      assertTrue(event(screen("empty-chat", at = 1_000, window = 43)) is WhatsAppSendAction.Wait)
      assertTrue(event(screen("empty-chat", at = 1_499, window = 43)) is WhatsAppSendAction.Wait)
      assertEquals(WhatsAppSendAction.Confirm, event(screen("empty-chat", at = 1_500, window = 43)))
      assertEquals(WhatsAppSendAction.None, event(screen("audio-preview", fresh = true)))
    }
  }

  @Test fun audioDoesNotTouchPreexistingUncommittedOrUnrelatedSurfaces() {
    val machine = AudioShareMachine(request, audio, null, phone, name)
    val state = AudioShareMachine.State()
    for (event in listOf(screen("audio-preview"), screen("share-dialog"),
      screen("audio-preview", fresh = true, launch = request - 1),
      screen("send-to", fresh = true), screen("permission-dialog", fresh = true),
      screen("wrong-contact", fresh = true))) {
      assertFalse(machine.reduce(state, event).action is WhatsAppSendAction.Touch)
    }
    val touched = machine.reduce(state, screen("audio-preview", fresh = true))
    val aborted = machine.reduce(touched.state, gesture(SendStep.SHARE_SEND, completed = false))
    assertEquals(WhatsAppSendAction.Abort, aborted.action)
    assertEquals(WhatsAppSendAction.None, machine.reduce(aborted.state, screen("share-dialog", fresh = true)).action)
  }

  @Test fun attachmentConfirmationTimesOutWithoutRetouching() {
    val machine = AudioShareMachine(request, "archivo.pdf", null, phone, name)
    val root = UiNode(children = listOf(
      UiNode(id = "com.whatsapp:id/recipient_name", text = name),
      UiNode(id = "com.whatsapp:id/file_name", text = "archivo.pdf"),
      UiNode(id = "com.whatsapp:id/send", bounds = UiBounds(100, 100, 180, 150)),
    ))
    val snapshot = screen("audio-preview").copy(reader = WhatsAppScreenReader(root, "com.whatsapp"))
    val touched = machine.reduce(AudioShareMachine.State(), snapshot)
    assertEquals(SendStep.SHARE_SEND, (touched.action as WhatsAppSendAction.Touch).step)
    val checking = machine.reduce(touched.state, gesture(SendStep.SHARE_SEND))
    val timeout = machine.reduce(checking.state, WhatsAppSendEvent.Elapsed(3_500))
    assertEquals(WhatsAppSendAction.Abort, timeout.action)
    assertEquals(WhatsAppSendAction.None, machine.reduce(timeout.state, snapshot).action)
  }

  private fun gesture(step: SendStep, completed: Boolean = true) =
    WhatsAppSendEvent.Gesture(500, step, completed)

  private fun screen(
    fixture: String,
    at: Long = 450,
    window: Int = 42,
    fresh: Boolean = false,
    launch: Long = request,
  ): WhatsAppSendEvent.Screen {
    val fixtureData = requireNotNull(javaClass.getResource("/whatsapp/$fixture.json")).readText()
    val json = JSONObject(fixtureData)
    assertEquals("synthetic", json.getString("origin"))
    return WhatsAppSendEvent.Screen(at, window,
      WhatsAppScreenReader(node(json.getJSONObject("root")), "com.whatsapp"),
      UiBounds(0, 0, 500, 500), launch, fresh)
  }

  private fun node(json: JSONObject): UiNode {
    fun string(key: String) = if (json.has(key)) json.getString(key) else null
    val rect = json.optJSONArray("bounds")
    val children = json.optJSONArray("children")
    return UiNode(
      id = string("id"), className = string("className"), text = string("text"),
      contentDescription = string("controlName"),
      bounds = if (rect == null) UiBounds(0, 0, 0, 0) else
        UiBounds(rect.getInt(0), rect.getInt(1), rect.getInt(2), rect.getInt(3)),
      enabled = json.optBoolean("enabled", true),
      clickable = json.optBoolean("clickable"),
      focused = json.optBoolean("focused"),
      editable = json.optBoolean("editable"),
      children = if (children == null) emptyList() else List(children.length()) { node(children.getJSONObject(it)) },
    )
  }
}
