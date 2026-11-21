# Insto 1.13.0 (código 22): «Instinct con manos»

Instinct puede pedirle acciones al teléfono **por su cuenta** e Insto las ejecuta y avisa después. Es una función opcional y viene apagada por defecto. No suma dependencias, servidores ni llamadas de red.

## Qué cambia

- Cuando Instinct lo considera útil, escribe en su chat de WhatsApp hasta tres líneas `#insto <orden>` por mensaje, por ejemplo `#insto poner el volumen al 30 por ciento`. No hace falta que antes le hayas escrito desde Insto.
- Insto detecta la notificación de ese contacto y la interpreta solo con la gramática local. La valida como cualquier pedido, la **ejecuta sin preguntar** y deja un aviso «Instinct hizo: …» en el canal «Insto · acciones de Instinct».
- Lista blanca v1: alarmas, temporizadores, volumen, linterna, silencio/vibrar/No molestar, multimedia, abrir una app, ver alarmas o temporizadores, búsquedas de música, Spotify o YouTube y eventos de calendario. Quedan prohibidos las llamadas, los mensajes, los archivos, los lugares, las rutinas y los planes de varios pasos.
- **Esperan tu toque** (notificación con **Confirmar**/**Descartar**) en estos casos:
  - si encendiste «Pedirme confirmación antes de ejecutar»;
  - con el teléfono bloqueado;
  - cuando la orden abre otra app y la Accesibilidad de Insto no está conectada;
  - después de 6 órdenes en 10 minutos;
  - cuando la acción necesita algo tuyo, como un permiso o confirmar una hora que ya pasó.
- En **Ajustes → Propuestas de Instinct** hay dos interruptores: «Recibir órdenes de Instinct» y «Pedirme confirmación antes de ejecutar». También hay un **diagnóstico** sin contenido: acceso a notificaciones, contacto esperado, Accesibilidad y motivo del último resultado.

Detalle completo, privacidad, límites y el bloque para pegar en el prompt de Instinct: [Órdenes de Instinct](../INSTINCT-PROPUESTAS.md).

## Cambio de privacidad

Hasta ahora, `NotificationAccessService` leía contenido solo cuando se lo pedías explícitamente. Con el nuevo interruptor encendido, Insto lee **de forma pasiva** el último mensaje del chat individual con el contacto configurado, en la app de WhatsApp configurada, y de ese mensaje solo usa las líneas `#insto`.

Con el interruptor apagado, `onNotificationPosted` retorna sin leer nada. El texto no se guarda, no se registra (Logcat solo muestra contadores), no sale del teléfono y no entra en copias de seguridad. Los interruptores viven en `noBackupFilesDir`, así que una restauración nunca los enciende.

## Garantías que se conservan

- Primero lo local, con comandos tipados. `RequestSafetyPolicy`, `PhoneCommandParser`, `SemanticCommandGuard.review` y la lista blanca siguen siendo el último control.
- Nada se ejecuta dos veces: el `claim` en `ProposalBook` es atómico y hay dedupe por notificación. Lo que espera confirmación vence a los 10 minutos y se rechaza si cambió la configuración.
- Nunca se repite un efecto incierto: si el resultado es incierto, se avisa y no se vuelve a ofrecer.
- Las órdenes no alimentan frases ni estructuras aprendidas y no cuentan como turnos de `OperationalContext`.
- El flotante no pisa un borrador, una grabación ni una confirmación en curso: la orden espera.

## Límites conocidos

- La coincidencia es por el nombre que muestra WhatsApp, no por el número. El contacto de Instinct necesita un nombre único.
- Desde Android 10 no se pueden abrir pantallas en segundo plano. Sin la Accesibilidad de Insto, todo lo que abre otra app espera tu toque.
- Requiere Android 9 o posterior. Las órdenes pendientes viven en memoria.

## Verificación

En este entorno, con el SDK de Android 36.1 instalado y el keystore efímero que también crea el CI:

- `./gradlew testDebugUnitTest assembleDebug lintDebug`: correcto. Incluye `InstinctProposalsTest` (JVM) e `InstinctProposalFlowTest` (Robolectric).
- `python3 scripts/test-phone-core.py`: 497 pruebas aprobadas.

**Pendiente en el teléfono:** recibir órdenes reales de Instinct sin escribirle antes, con y sin Accesibilidad, en WhatsApp y WhatsApp Business, y comprobar con `adb logcat -s InstoProposals` que solo aparecen contadores.
