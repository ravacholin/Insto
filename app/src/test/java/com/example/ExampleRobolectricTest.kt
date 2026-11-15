package com.example

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.assist.ArmedActionMode
import com.example.assist.AutoSendCoordinator
import com.example.context.ContextRoutingEngine
import com.example.data.AssistantActivationMode
import com.example.data.AssistantPreferences
import com.example.data.ContextRuleConfig
import com.example.data.OrbHoldBehavior
import com.example.data.PinnedContact
import com.example.data.WhatsAppFlavor
import com.example.ui.RecordingUiState
import com.example.ui.components.PingOrb
import com.example.ui.theme.PingAssistTheme
import com.example.whatsapp.PhoneNumberUtils
import com.example.whatsapp.WhatsAppDispatcher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `PingOrb tap or 2s hold opens chat without recording in progressive mode`() {
    var tapped = false
    var startedRecording = false
    var stoppedAndSent = false

    composeRule.setContent {
      PingAssistTheme {
        PingOrb(
          contactName = "Alex",
          recordingState = RecordingUiState.Idle,
          hasAudioPermission = true,
          holdBehavior = OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO,
          onTapOpenChat = { tapped = true },
          onStartRecording = {
            startedRecording = true
            true
          },
          onStopAndSend = { stoppedAndSent = true },
          onCancelRecording = {},
          onRequestAudioPermission = {},
        )
      }
    }

    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput { down(center) }
    composeRule.mainClock.advanceTimeBy(1_900L)
    composeRule.waitForIdle()
    assertFalse("Should not start recording before 2.5s threshold", startedRecording)

    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput { up() }
    composeRule.waitForIdle()
    assertTrue("Expected releasing under 2.5s to open chat directly", tapped)
    assertFalse("Should not call stopAndSend when releasing under 2.5s", stoppedAndSent)
  }

  @Test
  fun `PingOrb holding past 2_5s records and auto-sends on release in progressive mode`() {
    var tapped = false
    var startedRecording = false
    var stoppedAndSent = false

    composeRule.setContent {
      PingAssistTheme {
        PingOrb(
          contactName = "Alex",
          recordingState = RecordingUiState.Idle,
          hasAudioPermission = true,
          holdBehavior = OrbHoldBehavior.PROGRESSIVE_CHAT_THEN_AUDIO,
          onTapOpenChat = { tapped = true },
          onStartRecording = {
            startedRecording = true
            true
          },
          onStopAndSend = { stoppedAndSent = true },
          onCancelRecording = {},
          onRequestAudioPermission = {},
        )
      }
    }

    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput { down(center) }
    composeRule.mainClock.advanceTimeBy(2_750L)
    composeRule.waitForIdle()
    assertTrue("Expected startRecording after holding past 2.5s", startedRecording)
    assertFalse("Recording should not send while finger is still held", stoppedAndSent)

    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput {
      moveBy(androidx.compose.ui.geometry.Offset(18f, -12f))
      up()
    }
    composeRule.waitForIdle()
    assertTrue("Expected stopAndSend on release", stoppedAndSent)
    assertFalse("Open chat should not fire after recording starts", tapped)
  }

  @Test
  fun `PingOrb instant hold mode records at 350ms and sends on release`() {
    var tapped = false
    var startedRecording = false
    var stoppedAndSent = false

    composeRule.setContent {
      PingAssistTheme {
        PingOrb(
          contactName = "Alex",
          recordingState = RecordingUiState.Idle,
          hasAudioPermission = true,
          holdBehavior = OrbHoldBehavior.INSTANT_HOLD_TO_RECORD,
          onTapOpenChat = { tapped = true },
          onStartRecording = {
            startedRecording = true
            true
          },
          onStopAndSend = { stoppedAndSent = true },
          onCancelRecording = {},
          onRequestAudioPermission = {},
        )
      }
    }

    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput { down(center) }
    composeRule.mainClock.advanceTimeBy(400L)
    composeRule.waitForIdle()
    assertTrue("Expected startRecording after 400ms in instant mode", startedRecording)

    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput { up() }
    composeRule.waitForIdle()
    assertTrue("Expected stopAndSend on release", stoppedAndSent)
    assertFalse(tapped)
  }

  @Test
  fun `PingOrb does not call stopAndSend if startRecording fails`() {
    var stoppedAndSent = false

    composeRule.setContent {
      PingAssistTheme {
        PingOrb(
          contactName = "Alex",
          recordingState = RecordingUiState.Idle,
          hasAudioPermission = true,
          holdBehavior = OrbHoldBehavior.INSTANT_HOLD_TO_RECORD,
          onTapOpenChat = {},
          onStartRecording = { false },
          onStopAndSend = { stoppedAndSent = true },
          onCancelRecording = {},
          onRequestAudioPermission = {},
        )
      }
    }

    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput { down(center) }
    composeRule.mainClock.advanceTimeBy(400L)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput { up() }
    composeRule.waitForIdle()
    assertFalse("Should not stopAndSend when startRecording failed", stoppedAndSent)
  }

  @Test
  fun `PingOrb longpress without mic permission requests permission`() {
    var requestedPermission = false
    var startedRecording = false

    composeRule.setContent {
      PingAssistTheme {
        PingOrb(
          contactName = "Alex",
          recordingState = RecordingUiState.Idle,
          hasAudioPermission = false,
          holdBehavior = OrbHoldBehavior.INSTANT_HOLD_TO_RECORD,
          onTapOpenChat = {},
          onStartRecording = {
            startedRecording = true
            true
          },
          onStopAndSend = {},
          onCancelRecording = {},
          onRequestAudioPermission = { requestedPermission = true },
        )
      }
    }

    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput { down(center) }
    composeRule.mainClock.advanceTimeBy(400L)
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("assistant_ping_orb").performTouchInput { up() }
    composeRule.waitForIdle()

    assertTrue("Expected mic permission request on hold", requestedPermission)
    assertFalse("Should not start recording without permission", startedRecording)
  }

  @Test
  fun `app_name matches PingAssist`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PingAssist", appName)
  }

  @Test
  fun `buildOpenChatIntent builds direct wa_me or whatsapp scheme url`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val intent =
      WhatsAppDispatcher.buildOpenChatIntent(
        context = context,
        phoneNumber = "+1 (555) 019-2834",
        preferredFlavor = WhatsAppFlavor.AUTO,
      )

    assertNotNull(intent)
    assertEquals(Intent.ACTION_VIEW, intent!!.action)
    assertEquals("https://wa.me/15550192834", intent.dataString)
    assertEquals(
      "whatsapp://send?phone=15550192834",
      PhoneNumberUtils.toWhatsAppDirectSchemeUrl("+1 (555) 019-2834"),
    )
  }

  @Test
  fun `text deep link preserves unicode punctuation and recipient`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val text = "Hola Instinct & café?\nUna línea + otra 😊"
    val intent = WhatsAppDispatcher.buildSendTextIntent(context, "+44 7700 900123", text, WhatsAppFlavor.AUTO)
    assertNotNull(intent)
    assertEquals("447700900123", intent!!.data!!.getQueryParameter("phone"))
    assertEquals(text, intent.data!!.getQueryParameter("text"))
    assertNull(WhatsAppDispatcher.buildSendTextIntent(context, "123", text, WhatsAppFlavor.AUTO))
    assertNull(WhatsAppDispatcher.buildSendTextIntent(context, "+447700900123", "   ", WhatsAppFlavor.AUTO))
  }

  @Test
  fun `buildSendAudioIntent attaches fileprovider stream and jid`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val voiceDir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
    val audioFile = File(voiceDir, "test_ping.m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

    val intent =
      WhatsAppDispatcher.buildSendAudioIntent(
        context = context,
        phoneNumber = "+44 7700 900123",
        audioFile = audioFile,
        preferredFlavor = WhatsAppFlavor.AUTO,
      )

    assertNotNull(intent)
    assertEquals(Intent.ACTION_SEND, intent!!.action)
    assertEquals("audio/mp4", intent.type)
    assertEquals("447700900123@s.whatsapp.net", intent.getStringExtra("jid"))
    val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
    assertNotNull(stream)
    assertEquals(stream, intent.clipData?.getItemAt(0)?.uri)
    assertEquals(1, intent.clipData?.itemCount)
    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

    audioFile.delete()
  }

  @Test
  fun `AutoSendCoordinator supports native PTT mode and Boomerang return package`() {
    AutoSendCoordinator.disarm()
    AutoSendCoordinator.lastKnownForegroundPackage = "com.google.android.apps.maps"
    assertFalse(AutoSendCoordinator.isArmedFor("com.whatsapp"))

    AutoSendCoordinator.arm(
      targetPackage = "com.whatsapp",
      mode = ArmedActionMode.NATIVE_PTT_LOCK,
      boomerangReturn = true,
      durationMs = 5_000L,
    )
    assertTrue(AutoSendCoordinator.isArmedFor("com.whatsapp"))
    assertEquals(ArmedActionMode.NATIVE_PTT_LOCK, AutoSendCoordinator.currentMode())
    assertTrue(AutoSendCoordinator.shouldBoomerangReturn())
    assertEquals("com.google.android.apps.maps", AutoSendCoordinator.consumeReturnPackage())
    assertNull(AutoSendCoordinator.consumeReturnPackage())

    AutoSendCoordinator.disarm()
    assertFalse(AutoSendCoordinator.isArmedFor("com.whatsapp"))
  }

  @Test
  fun `ContextRoutingEngine applies night window rule`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val primary = PinnedContact("Alex", "+15550001111")
    val prefs =
      AssistantPreferences(
        pinnedContact = primary,
        activationMode = AssistantActivationMode.INSTANT_OPEN_CHAT,
        contextRule =
          ContextRuleConfig(
            switchToVoiceInCarOrBluetooth = false,
            nightWindowEnabled = true,
            nightStartHour = 21,
            nightEndHour = 7,
          ),
      )

    val nightRoute = ContextRoutingEngine.resolveRoute(context, prefs, hourOfDay = 23)
    assertEquals(AssistantActivationMode.INSTANT_RECORD_AUDIO, nightRoute.activationMode)
    assertEquals("Night Window · Voice", nightRoute.activeContextLabel)

    val dayRoute = ContextRoutingEngine.resolveRoute(context, prefs, hourOfDay = 14)
    assertEquals(AssistantActivationMode.INSTANT_OPEN_CHAT, dayRoute.activationMode)
    assertNull(dayRoute.activeContextLabel)
  }
}
