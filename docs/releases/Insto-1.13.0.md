# Insto 1.13.0 (código 22): «Instinct con manos»

Instinct puede proponer acciones y Insto las ejecuta **solo con tu confirmación**. Función opcional, apagada por defecto. Sin dependencias, servidores ni llamadas de red nuevas.

## Qué cambia

- Instinct puede responder en WhatsApp con hasta tres líneas `#insto <orden>` por mensaje, por ejemplo `#insto poner una alarma a las 07:00`.
- Insto las detecta en la notificación de WhatsApp del contacto de Instinct, las interpreta solo con la gramática local, las valida como cualquier pedido y las ofrece para confirmar: dentro del flotante si está abierto o con una notificación con **Confirmar** y **Descartar**.
- Lista blanca v1: alarmas, temporizadores, volumen, linterna, silencio/vibrar/No molestar, multimedia, abrir una app, ver alarmas o temporizadores, búsquedas de música, Spotify o YouTube y eventos de calendario (con su vista previa). Llamadas, mensajes, archivos, lugares, rutinas y planes de varios pasos quedan prohibidos.
- Nuevo interruptor **Ajustes → Propuestas de Instinct → «Recibir propuestas de Instinct»**, con la ventana de correlación configurable (5 a 60 minutos; 15 por defecto).
- Nuevos canales de notificación: «Insto · propuestas de Instinct» (alta importancia) y «Insto · avisos de propuestas» (baja importancia, para «Instinct propuso algo que no entiendo»).

Detalle completo, privacidad, límites y el bloque para pegar en el prompt de Instinct: [Propuestas de Instinct](../INSTINCT-PROPUESTAS.md).

## Cambio de privacidad

`NotificationAccessService` deja de leer contenido solo ante un pedido explícito: con el nuevo interruptor encendido, lee de forma pasiva el último mensaje del chat individual con el contacto configurado, en la app de WhatsApp configurada y solo durante la ventana posterior a un envío confirmado. Con el interruptor apagado, `onNotificationPosted` retorna sin leer nada. El texto no se guarda, no se registra (Logcat solo muestra contadores), no sale del teléfono y no entra en copias de seguridad. El interruptor vive en `noBackupFilesDir`: una restauración nunca lo enciende.

## Garantías que se conservan

- Primero lo local y comandos tipados: la propuesta pasa por `RequestSafetyPolicy`, `PhoneCommandParser` y `SemanticCommandGuard.review` como último control, más la lista blanca.
- Nada tardío u obsoleto ejecuta: las propuestas vencen a los 10 minutos, se ejecutan una sola vez (`ProposalBook.claim` atómico) y se rechazan si cambió el contacto, la app de WhatsApp o se apagó el interruptor.
- Nunca se repite un efecto incierto: la ejecución usa `PhoneActionExecutor` y `ActionExecutionLedger` como cualquier pedido, con sus permisos y confirmaciones.
- Las propuestas no alimentan frases ni estructuras aprendidas y no cuentan como turnos de `OperationalContext`.
- El flotante no pisa un borrador, una grabación ni una confirmación en curso: la propuesta queda en espera.

## Límites conocidos

- La coincidencia por nombre no prueba el número: Android no expone el número del remitente de WhatsApp.
- Solo los envíos confirmados por el auto-envío abren la ventana; un envío manual no.
- Requiere Android 9 o posterior (para excluir grupos con `EXTRA_IS_GROUP_CONVERSATION`).
- Las propuestas viven en memoria: si Android cierra el proceso, se pierden.

## Verificación

En este entorno, con el SDK de Android 36.1 instalado y el keystore efímero que también crea el CI:

- `./gradlew testDebugUnitTest assembleDebug lintDebug`: correcto. Pruebas nuevas: `InstinctProposalsTest` (23, JVM) e `InstinctProposalFlowTest` (12, Robolectric).
- `python3 scripts/test-phone-core.py`: correcto; incluye `InstinctProposalsTest`.

**Pendiente en el teléfono:** recibir una propuesta real desde el chat de Instinct con WhatsApp y WhatsApp Business, probar Confirmar y Descartar desde la notificación (también con la pantalla bloqueada), la espera con un borrador en el flotante, un grupo y otro contacto con nombre parecido, y comprobar con `adb logcat -s InstoProposals` que solo aparecen contadores. Pasos en [Cómo probarlo en el teléfono](../INSTINCT-PROPUESTAS.md#cómo-probarlo-en-el-teléfono).
