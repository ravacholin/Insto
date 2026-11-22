package com.example.backup

/**
 * Every place Insto keeps data (1.20.0), classified for the backup. A test reads the sources and fails when a stored
 * file, preference key, DataStore or SharedPreferences appears that is not listed here, so a new setting cannot be
 * forgotten by the backup silently. See docs/releases/Insto-1.20.0.md, «Inventario de backup».
 */
object BackupInventory {
  enum class Kind { FILE, DATASTORE, DATASTORE_KEY, SHARED_PREFERENCES, SQLITE, KEYSTORE, DIRECTORY, EXTERNAL }

  enum class Class(val label: String) {
    CONFIG("configuración exportable"),
    SECRET("secreto exportable (solo en la copia completa)"),
    DEVICE_BOUND("vinculado a este teléfono: se genera de nuevo"),
    TRANSIENT("estado operativo transitorio"),
    HISTORY("historial"),
    NOT_EXPORTABLE("no exportable por seguridad"),
  }

  data class Item(val name: String, val kind: Kind, val cls: Class, val category: BackupCategory?, val note: String)

  private fun f(name: String, cls: Class, category: BackupCategory?, note: String) = Item(name, Kind.FILE, cls, category, note)
  private fun k(name: String, cls: Class, category: BackupCategory?, note: String) = Item(name, Kind.DATASTORE_KEY, cls, category, note)

  val items: List<Item> = listOf(
    // Providers
    f("groq-key.enc", Class.SECRET, BackupCategory.CREDENTIALS, "clave de Groq, cifrada con el Keystore; se vuelve a cifrar en el destino"),
    f("gemini-key.enc", Class.SECRET, BackupCategory.CREDENTIALS, "clave de Gemini, cifrada con el Keystore; se vuelve a cifrar en el destino"),
    f("gemini-enabled", Class.CONFIG, BackupCategory.CREDENTIALS, "Gemini Decisions"),
    f("gemini-local-choice", Class.CONFIG, BackupCategory.CREDENTIALS, "«seguir en local»"),
    Item("insto.groq.personal.v1", Kind.KEYSTORE, Class.NOT_EXPORTABLE, null, "clave del Android Keystore que cifra la de Groq"),
    Item("insto.gemini.personal.v1", Kind.KEYSTORE, Class.NOT_EXPORTABLE, null, "clave del Android Keystore que cifra la de Gemini"),
    // Assistant
    Item("ping_assist_prefs", Kind.DATASTORE, Class.CONFIG, BackupCategory.ASSISTANT, "preferencias del asistente (nombre histórico)"),
    k("contact_name", Class.CONFIG, BackupCategory.ASSISTANT, "contacto de Instinct"),
    k("contact_phone", Class.CONFIG, BackupCategory.ASSISTANT, "contacto de Instinct"),
    k("driving_contact_name", Class.CONFIG, BackupCategory.ASSISTANT, "contacto de conducción"),
    k("driving_contact_phone", Class.CONFIG, BackupCategory.ASSISTANT, "contacto de conducción"),
    k("activation_mode", Class.CONFIG, BackupCategory.ASSISTANT, "modo de activación"),
    k("orb_hold_behavior", Class.CONFIG, BackupCategory.ASSISTANT, "botón principal"),
    k("whatsapp_flavor", Class.CONFIG, BackupCategory.ASSISTANT, "WhatsApp preferido"),
    k("voice_recording_mode", Class.CONFIG, BackupCategory.ASSISTANT, "modo de grabación"),
    k("volume_key_trigger", Class.CONFIG, BackupCategory.ASSISTANT, "teclas de volumen"),
    k("floating_pill_mode", Class.CONFIG, BackupCategory.ASSISTANT, "botón flotante"),
    k("eyes_free_cues", Class.CONFIG, BackupCategory.ASSISTANT, "señales sonoras"),
    k("boomerang_return", Class.CONFIG, BackupCategory.ASSISTANT, "volver a la app anterior"),
    k("car_bt_auto_voice", Class.CONFIG, BackupCategory.ASSISTANT, "voz en el auto"),
    k("night_window_enabled", Class.CONFIG, BackupCategory.ASSISTANT, "horario nocturno"),
    k("night_start_hour", Class.CONFIG, BackupCategory.ASSISTANT, "horario nocturno"),
    k("night_end_hour", Class.CONFIG, BackupCategory.ASSISTANT, "horario nocturno"),
    k("groq_vocabulary", Class.CONFIG, BackupCategory.ASSISTANT, "vocabulario de Groq"),
    k("groq_pinned_contact_hints", Class.CONFIG, BackupCategory.ASSISTANT, "vocabulario de Groq"),
    k("mic_prompted_once", Class.TRANSIENT, null, "si ya se pidió el micrófono en este teléfono"),
    k("recent_logs_json", Class.HISTORY, null, "actividad reciente"),
    Item("learned_phrases", Kind.DATASTORE, Class.CONFIG, BackupCategory.PHRASES, "frases y estructuras aprendidas, con su interruptor"),
    Item("saved_places", Kind.SHARED_PREFERENCES, Class.CONFIG, BackupCategory.PLACES, "casa y trabajo"),
    f("configured-routines.json", Class.CONFIG, BackupCategory.ROUTINES, "rutinas"),
    // Instinct
    f("instinct-proposals-enabled", Class.CONFIG, BackupCategory.INSTINCT, "«Recibir órdenes»: se restaura apagado y se pide reactivar"),
    f("instinct-proposals-reply-mode", Class.CONFIG, BackupCategory.INSTINCT, "«Responderle a Instinct»"),
    f("instinct-proposals-ack-channel", Class.CONFIG, BackupCategory.INSTINCT, "canal de estados y acuses"),
    f("instinct-proposals-ask-first", Class.CONFIG, BackupCategory.INSTINCT, "ajuste de 1.15.0, migrado a la autonomía"),
    f("instinct-autonomy.json", Class.CONFIG, BackupCategory.INSTINCT, "autonomía por categoría, horas de silencio y su excepción"),
    f("instinct-routines.json", Class.CONFIG, BackupCategory.INSTINCT, "rutinas que Instinct puede ejecutar"),
    f("instinct-log-url", Class.CONFIG, BackupCategory.INSTINCT, "URL completa del Apps Script"),
    f("instinct-log-token", Class.SECRET, BackupCategory.INSTINCT, "token de la bitácora"),
    f("instinct-mailbox-enabled", Class.CONFIG, BackupCategory.INSTINCT, "buzón: se restaura apagado y se pide reactivar"),
    f("instinct-mailbox-key", Class.SECRET, BackupCategory.INSTINCT, "clave HMAC-SHA256 del buzón"),
    f("instinct-mailbox-accept", Class.CONFIG, BackupCategory.INSTINCT, "«Aceptar órdenes de»"),
    f("instinct-whatsapp-cap", Class.CONFIG, BackupCategory.INSTINCT, "tope de WhatsApp sin firma"),
    f("instinct-mailbox-interval", Class.CONFIG, BackupCategory.INSTINCT, "intervalo del sondeo"),
    f("instinct-doorbell-enabled", Class.CONFIG, BackupCategory.INSTINCT, "timbre: se restaura apagado y se registra de nuevo al reactivar"),
    f("instinct-doorbell-with-order", Class.CONFIG, BackupCategory.INSTINCT, "modalidad: orden cifrada dentro del timbre"),
    f("instinct-doorbell-distributor", Class.CONFIG, BackupCategory.INSTINCT, "distribuidor UnifiedPush elegido"),
    f("instinct-scheduled.json", Class.CONFIG, BackupCategory.INSTINCT, "solo las recurrentes, como definición; nunca una orden única consumida o vencida"),
    f("instinct-doorbell-endpoint", Class.DEVICE_BOUND, null, "endpoint de este teléfono: el nuevo se registra y se le avisa al script"),
    f("instinct-doorbell-sealed", Class.DEVICE_BOUND, null, "timbre cifrado para las claves de este teléfono: se regenera"),
    f("instinct-doorbell-state.json", Class.DEVICE_BOUND, null, "registro, token del conector, confirmaciones del script y diagnóstico"),
    Item("unifiedpush-connector", Kind.EXTERNAL, Class.DEVICE_BOUND, null, "base de datos de la biblioteca UnifiedPush y claves Web Push en el Keystore"),
    f("instinct-executions.db", Class.HISTORY, null, "diario de ejecución: nunca se importa como trabajo pendiente"),
    f("instinct-trust.json", Class.HISTORY, null, "contadores del ajuste adaptativo"),
    f("instinct-mailbox-seen.json", Class.TRANSIENT, null, "ids vistos de 1.19.0 (migrados al diario)"),
    f("instinct-push-inbox.json", Class.TRANSIENT, null, "cola de recepción push"),
    f("instinct-outbox.json", Class.TRANSIENT, null, "acuses esperando enviarse a la bitácora"),
    f("instinct-log-status", Class.TRANSIENT, null, "último resultado de la bitácora"),
    f("instinct-mailbox-status.json", Class.TRANSIENT, null, "último sondeo"),
    f("instinct-catalog-last.json", Class.TRANSIENT, null, "último catálogo publicado"),
    f("instinct-state-last.json", Class.TRANSIENT, null, "último estado publicado"),
    f("instinct-routine-workflow.json", Class.TRANSIENT, null, "rutina en curso"),
    f("instinct-readiness-needed.json", Class.TRANSIENT, null, "permisos que una orden necesitó en este teléfono"),
    f("instinct-reactivation.json", Class.TRANSIENT, null, "pedido de reactivación después de restaurar"),
    // Other state
    f("temporary-phone-mode.json", Class.TRANSIENT, null, "modo temporal activo"),
    f("phone-workflow.json", Class.TRANSIENT, null, "secuencia en curso del panel"),
    f("autosend-metrics.json", Class.HISTORY, null, "métricas del envío automático"),
    f("restore-in-progress.json", Class.TRANSIENT, null, "marca de restauración interrumpida"),
    Item("captures", Kind.DIRECTORY, Class.NOT_EXPORTABLE, null, "fotos, videos y audios capturados"),
  )

  fun named(name: String): Item? = items.firstOrNull { it.name == name }
}
