package com.example.phone

import org.junit.Assert.*
import org.junit.Test

class LocalIntentRegistryTest {
  private val registry get() = LocalIntentRegistry.default
  @Test fun catalogCoversEveryCategoryAndDescribesEveryAction() {
    assertEquals(IntentCategory.entries.toSet(), registry.definitions.map { it.category }.toSet())
    assertTrue(registry.definitions.size >= 60)
    registry.definitions.forEach {
      assertTrue(it.id, it.description.isNotBlank() && it.examples.size >= 2)
      assertTrue(it.id, it.mechanism.isNotBlank() && it.expectedResult.isNotBlank())
      val required = it.parameters.filter { parameter -> parameter.required }.joinToString { parameter -> parameter.name }
      assertEquals(it.id, required, registry.choices.getValue(it.id).substringAfter(" Obligatorios: ", "").removeSuffix("."))
      assertEquals(it.parameters.size, it.parameters.map { p -> p.name }.distinct().size)
    }
  }
  @Test fun registryRejectsDuplicateAndReservedIds() {
    val entry = registry.definitions.first()
    try { LocalIntentRegistry(listOf(entry,entry)); fail("duplicate") } catch (_: IllegalArgumentException) { }
    try { LocalIntentRegistry(listOf(entry.copy(id="instinct"))); fail("reserved") } catch (_: IllegalArgumentException) { }
  }
  @Test fun independentRegistryCanAddActionsWithoutChangingTheRouter() {
    val custom = registry.definitions.first { it.id == "torch_on" }.copy(id="custom_light")
    val extended = LocalIntentRegistry(registry.definitions + custom)
    assertEquals(PhoneRoute.Local(PhoneCommand.Torch(true)), extended.resolve("custom_light","iluminá con la linterna"))
    assertFalse(registry.choices.containsKey("custom_light"))
  }
  @Test fun clarificationCannotChangeTheSelectedIntent() {
    assertEquals(PhoneRoute.Local(PhoneCommand.Timer(180)), registry.reply("timer", "tres minutos"))
    assertNull(registry.reply("timer", "apagá la linterna"))
    assertNull(registry.reply("timer", "explicame relatividad"))
    assertEquals(PhoneRoute.Cancel, registry.reply("timer", "cancelar"))
  }
  @Test fun unimplementedIntegrationIsARecognizedLocalError() {
    assertTrue(registry.resolve("timer_update", "modificá el temporizador") is PhoneRoute.Unsupported)
    assertTrue(registry.resolve("recorder_stop", "detené la grabación de otra app") is PhoneRoute.Unsupported)
  }
  @Test fun settingsAndConnectivityChangesAreSeparateActions() {
    assertEquals(PhoneRoute.Local(PhoneCommand.Settings("wifi")), registry.resolve("settings_wifi", "abrí ajustes de wifi"))
    assertTrue(registry.resolve("wifi_on", "activá el wifi") is PhoneRoute.Unsupported)
  }
  @Test fun newNativeParametersPreserveOriginalContentAndRejectExtraInstructions() {
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.CONTACT_SEARCH, "José Pérez")),
      registry.resolve("contact_search", "buscá el contacto José Pérez"))
    assertTrue(registry.resolve("brightness_set", "poné el brillo al ciento veinte por ciento") is PhoneRoute.Clarify)
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.BRIGHTNESS, percent=40)),
      registry.resolve("brightness_set", "poné el brillo al cuarenta por ciento"))
    assertTrue(registry.resolve("contact_search", "buscá el contacto Ana y llamala") !is PhoneRoute.Local)
  }
  @Test fun fixedActionsDoNotEraseNegationOrConditions() {
    assertFalse(registry.resolve("torch_on", "no prendas la linterna") is PhoneRoute.Local)
    assertFalse(registry.resolve("dnd_on", "activá no molestar si llega un mensaje") is PhoneRoute.Local)
  }
  @Test fun nativeFieldRepliesStayWithinTheRecognizedIntent() {
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.BRIGHTNESS,percent=40)), registry.reply("brightness_set","cuarenta por ciento"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.CONTACT_SEARCH,"José Pérez")), registry.reply("contact_search","José Pérez"))
    assertNull(registry.reply("brightness_set","explicame por qué cambia el brillo"))
    assertTrue(registry.reply("call_direct", "Ana") is PhoneRoute.Clarify)
  }
  @Test fun newQuestionDuringNativeFieldPromptIsNotConsumedAsAParameter() {
    assertNull(registry.reply("brightness_set","hola"))
  }
  @Test fun fixedPlatformIntentsWorkEvenWhenLegacyParserCallsThemApps() {
    for(id in listOf("dialer_open","files_browse","accessibility_settings","recorder_open")) {
      val definition=registry.definitions.single {it.id==id}
      assertEquals(id,PhoneRoute.Local(definition.fixed!!),registry.resolve(id,definition.examples.first()))
    }
  }
  @Test fun interrogativeQuestionsDuringFieldsReturnToDecisions() {
    for(id in listOf("contact_search","app_settings","notification_open","dial","maps"))
      for(text in listOf("¿por qué el cielo es azul?","¿cuánto es dos más dos?","dónde queda Roma","cuál es la capital"))
        assertNull("$id $text",registry.reply(id,text))
  }
  @Test fun alarmPeriodReplyPreservesOriginalDateAndDoesNotLoseItForAnotherTime() {
    val now=java.util.Calendar.getInstance()
    val source="despertame mañana a las ocho" to now
    val route=registry.reply("alarm","de la mañana",source)
    assertTrue(route is PhoneRoute.Local)
    val alarm=(route as PhoneRoute.Local).command as PhoneCommand.Alarm
    val tomorrow=now.clone() as java.util.Calendar;tomorrow.add(java.util.Calendar.DAY_OF_MONTH,1)
    val expected="%04d-%02d-%02d".format(java.util.Locale.ROOT,tomorrow.get(java.util.Calendar.YEAR),tomorrow.get(java.util.Calendar.MONTH)+1,tomorrow.get(java.util.Calendar.DAY_OF_MONTH))
    assertEquals(expected,alarm.date);assertEquals(8,alarm.hour)
    assertTrue(registry.reply("alarm","a las siete de la mañana",source) is PhoneRoute.Clarify)
  }
  @Test fun extendedCatalogResolvesPublicOperationsRatherThanConstantRejections() {
    for((id,text) in listOf("file_open" to "abrí el archivo informe.pdf","file_delete" to "eliminá el archivo informe.pdf","file_copy" to "copiá un archivo","calendar_query" to "qué tengo mañana","calendar_update" to "editá el evento \"Reunión\"","calendar_delete" to "cancelá el evento \"Reunión\"","routine_run" to "ejecutá la rutina Estudio")) {
      assertTrue(id,registry.resolve(id,text) is PhoneRoute.Local)
      assertNull(registry.definitions.single {it.id==id}.unavailable)
    }
  }
  @Test fun communicationRequiresExplicitRecipientAndPreservesExactBody() {
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.MESSAGE_SEND,"+541112345678",content="¡Llegué, José!")),registry.resolve("message_send","mandá por WhatsApp a +541112345678 que diga ¡Llegué, José!"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.CALL_DIRECT,"+541112345678")),registry.resolve("call_direct","llamá directamente al +541112345678"))
    assertTrue(registry.resolve("message_send","mandá por WhatsApp que diga Hola") is PhoneRoute.Clarify)
  }
  @Test fun appAndRoutineNamesCompleteOnlyTheirRecognizedIntent() {
    assertEquals(PhoneRoute.Local(PhoneCommand.OpenApp("spotify")),registry.reply("app_open","Spotify"))
    assertEquals(PhoneRoute.Local(PhoneCommand.Platform(PlatformAction.ROUTINE_RUN,"Estudio")),registry.reply("routine_run","Estudio"))
    assertNull(registry.reply("app_open","necesito ayuda con mis estudios"))
    assertNull(registry.reply("routine_run","quiero saber quién descubrió América"))
  }
  @Test fun tomorrowAlarmPreservesTheDateInsteadOfSilentlyDroppingIt() {
    val now=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires")).apply {
      set(2026,10,16,18,0,0);set(java.util.Calendar.MILLISECOND,0)
    }
    val route=PhoneCommandParser {now.clone() as java.util.Calendar}.parse("despertame mañana a las ocho de la mañana")
    assertTrue(route is PhoneRoute.Local)
    assertEquals("2026-11-17", PhoneCommandCodec.encode((route as PhoneRoute.Local).command).getString("date"))
  }
  @Test fun fixedActionsNeverOverrideExplicitParserRestrictions() {
    assertTrue(registry.resolve("torch_on", "encendé la linterna durante diez minutos") is PhoneRoute.Unsupported)
    assertTrue(registry.resolve("torch_on", "apagá la linterna") is PhoneRoute.Unsupported)
  }
}
