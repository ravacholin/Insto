# Voz adaptativa y texto de otras apps

Insto 1.10.0 (código 17), integrado sobre main `196685e` / PR #16. Incluye la base 1.9.0 / PR #15 (`9fcc034`) y conserva disponibilidad local, Gemini Decisions, catálogo nativo, borradores revisables y envío a Instinct.

## Grabación

El VAD de WebRTC cierra tras 900 ms de silencio si el tramo entre la primera y última voz dura menos de 4 s; 1600 ms si dura entre 4 y 12 s; 2400 ms desde 12 s. Cada cuadro de voz reinicia el silencio. La escucha inicial conserva 4 s; el botón de envío permite cerrar antes. Se mantienen la grabación original, reintento sin repetir transcripción y cancelación de resultados obsoletos.

Es una heurística de duración, no una clasificación semántica de orden/dictado. Una pausa larga al comienzo de un dictado todavía puede cerrarlo; hay que validar los umbrales con hablantes y ruido reales antes de fijarlos para producción.

### Transcribir mientras hablás

Se evaluó el flujo actual: `AudioRecord → WebRTC VAD → AacAudioWriter → M4A finalizado → Groq /audio/transcriptions → Decisions`. La frontera `AudioTranscriber` recibe un archivo. No hay un transporte de transcripción incremental y el M4A en escritura todavía no es un archivo finalizado. Abrir Android SpeechRecognizer simultáneamente competiría por el mismo micrófono. No se habilitó streaming ni se suben fragmentos parciales. Para una siguiente iteración: un único dueño del audio, cuadros PCM hacia un transcriptor incremental, hipótesis parciales solo para UI y texto final validado antes de ejecutar. Medir primero qué etapa domina en teléfono.

## Usar contenido como contexto

1. Seleccioná texto en otra app y elegí **Insto** en el menú de selección, o usá **Compartir → Insto** para contenido de texto.
2. El flotante muestra “Texto seleccionado/compartido” y una vista previa. Podés quitarlo y seguir con otro pedido.
3. Decí o escribí “Resumime esto”, “Traducilo al inglés” o “Agendá la fecha que aparece acá”. Recibir contenido por sí solo no envía consultas ni ejecuta acciones.
4. Resúmenes y traducciones se envían al contacto de Instinct con la instrucción y referencia en campos separados. Los pedidos por voz que llevan contexto usan el envío de texto, para incluir la referencia completa. Sin contexto se conserva el envío del audio original.
5. Con Gemini habilitado, las fechas se extraen usando el texto y se muestran en un plan para revisar/confirmar. Si faltan datos se conserva el borrador; sin Gemini, sigue disponible la aclaración local de los datos del evento.

Solo se leen los extras de texto de `ACTION_PROCESS_TEXT` y `ACTION_SEND`. No se lee portapapeles ni pantalla, no se descarga el destino de una URL ni se extraen PDF/Word. Compartir solo un enlace aporta ese enlace; seleccioná el texto de la página si querés resumir su contenido. No requiere permisos nuevos.

El límite es 10000 caracteres. Se rechaza el exceso sin truncar ni perder fechas al final. Gemini también aplica su límite existente de 16000 caracteres al JSON completo. El contexto queda en memoria durante el flotante, sobrevive rotaciones y reintentos, se reemplaza al recibir otro texto y se borra al cerrar. Un reemplazo o eliminación invalida decisiones y planes todavía sin confirmar. Una secuencia ya confirmada debe terminar o cancelarse antes de cambiar el contexto.

## Medir en teléfono

Usar una compilación debug y un teléfono con depuración USB, Groq y Gemini configurados. La pantalla muestra los tiempos y Logcat publica solo números bajo `InstoVoiceLatency`; nunca texto, audio ni claves.

```sh
python3 scripts/measure-voice-latency.py --samples 20 --output /tmp/insto-cortos.csv
# Para elegir un teléfono: --serial SERIAL
```

Ejecutar por separado órdenes cortas, intervenciones de 4–12 s y dictados de más de 12 s. Incluir pausas naturales, voz baja, ventilador/ruido y Bluetooth; mantener dispositivo, red y proveedores constantes al comparar. Guardar porcentaje de cierres prematuros además de p50/p95.

- `endpoint_ms`: última voz detectada → inicio de envío automático/manual; incluye silencio y polling del flotante.
- `finalization_ms`: cierre del micrófono y del M4A.
- `transcription_ms`: llamada a Groq, sin repetirla al reintentar una decisión.
- `interpretation_ms`: interpretación/enrutamiento, incluyendo preparación o ejecución local síncrona.
- `total_ms`: suma de las etapas disponibles. No mide la respuesta final de Instinct en WhatsApp ni el final de una acción Android asíncrona. Las mediciones guardadas al reintentar conservan los tiempos de captura/transcripción ya completados.

No hay teléfono conectado en este entorno. Los umbrales están cubiertos por pruebas de política, pero todavía no hay medición física ni garantía de mejora extremo a extremo.

## Verificación inicial sobre PR #15 (2026-11-17)

- `python3 scripts/test-phone-core.py`: JUnit informa 167 casos sin fallos; 166 aprobados y la evaluación real opt-in de Gemini omitida por no estar habilitada. Incluye política adaptativa, serialización del contexto y regresión de acciones registradas con referencia adjunta.
- `assembleDebug compileDebugUnitTestKotlin`: BUILD SUCCESSFUL. Compilan también las pruebas de intents, actividad/rotación, reemplazo, cancelación, consultas contextuales de voz y tiempos. Compilarlas no demuestra su ejecución.
- `testDebugUnitTest`: ejecución bloqueada antes de iniciar los casos por HTTP 403 al descargar `org.robolectric:nativeruntime-dist-compat:1.0.18` desde el mirror de Maven. No se modificaron dependencias del proyecto para ocultarlo.
- Colector de mediciones: ayuda/sintaxis verificadas; simulación de ADB con dos registros verificó descarte de histórico y CSV/percentiles. Es una prueba del recolector, no una medición de rendimiento.
- APK local: `app/build/outputs/apk/debug/app-debug.apk`, 18941441 bytes, SHA-256 `8a42b75b08e6132d0ec7651547ee647a622293a31e999191dad3b58a7f599d8c`; firma v2 y alineación ZIP/16 KiB verificadas. Usa una clave debug local nueva: no tiene la firma del APK 1.9.0 publicado; no se garantiza instalarlo como actualización sobre ese APK.
- Revisión independiente: corregidos el bloqueo de acciones registradas al adjuntar contexto, el adjunto anterior conservado al recibir nueva referencia y el texto obsoleto que anunciaba 3 segundos de silencio.
- Pendiente: ejecución de Robolectric en un entorno con acceso a esas dependencias y validación en teléfono de micrófono, proveedores reales, menú Compartir/selección y entrega en WhatsApp. ADB no detectó dispositivos conectados.

## Integración con PR #16 y entrega

La rama se actualizó sobre `196685e66560799b2f3c3bdff29fcfd606b44553` antes de publicar. Se conserva el camino rápido de comandos completos y la derivación a Instinct cuando Gemini falla. La aclaración de una fecha con texto de referencia puede usar extracción de Gemini; sin referencia o sin Gemini habilitado se mantiene la aclaración local inmediata. La prueba de calendario contextual falló tras incorporar main y volvió a pasar al integrar ambos caminos.

La entrega actualizada y el APK de Drive se describen en [Insto 1.10.0](releases/Insto-1.10.0-voz-contexto-debug.md). Los tiempos aún requieren validación física.

## Corrección de los fallos detectados por CI

La primera ejecución Android completa del PR #17 ejecutó 325 casos y detectó dos fallos (más una evaluación real omitida):

- `NativePhoneActionLifecycleTest.clockHandoffFinishesEvenWhenTheOverlayStopsBeforeItsNextFrame`: la aserción usaba una cola auxiliar de instrumentación vacía, aunque el ejecutor real había producido HANDOFF. La prueba comprueba ahora la finalización pendiente y el cierre tras stop; el intent exacto y la ausencia de duplicados siguen cubiertos por las pruebas del ejecutor y del enrutador.
- `FloatingContextTest.newReferenceReplacesAPreviouslySelectedAttachment`: el proveedor anónimo se registraba sin adjuntarle contexto Android. La prueba usa ahora el `FileProvider` real del manifest, un archivo temporal permitido y comprueba URI y nombre antes de quitar el adjunto al recibir contexto.

No se deshabilitaron pruebas ni comprobaciones del workflow. La verificación final se documenta en la nota de entrega.

La salida completa de XML en CI reveló además una regresión de aislamiento de `FloatingHybridRoutingTest`: el FileProvider reutilizaba raíces de otro directorio temporal. Su fixture ahora se adjunta en cada prueba, como las demás pruebas de audio existentes.

La traza con estado confirmó que el Reloj devolvía HANDOFF, sin error, y la finalización estaba pendiente antes de detener la actividad. La prueba de ciclo de vida comprueba ese resultado real y el cierre/consumo único después de stop, sin avanzar tiempo de un frame. La verificación del intent exacto y el envío único sigue en `NativeStopwatchControllerTest` y `FloatingPhoneRoutingTest`; las tres pruebas se ejecutan en la misma suite. No se fabrica un resultado ni se sustituye el ejecutor. El receptor también tolera defensivamente un intent de inicio ausente.
