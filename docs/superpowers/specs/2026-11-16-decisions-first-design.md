# Insto: Gemini Decisions primero, acciones locales e Instinct

Fecha: 2026-11-16. Estado: diseño para revisión; código de producto sin modificar.

## Objetivo solicitado

Toda entrada nueva, texto o transcripción, pasa primero por Gemini Decisions. Su responsabilidad es clasificar semánticamente contra un catálogo conocido. Un intent reconocido se valida y ejecuta mediante mecanismos Android autorizados. Todo lo que no encaja con suficiente confianza pasa a Instinct con el pedido original completo. Gemini no responde consultas generales, no inventa capacidades y no planifica instrucciones complejas.

El catálogo es extensible: añadir una acción incorpora una definición, un esquema y un ejecutor; no cambia el coordinador. Debe cubrir las catorce categorías solicitadas, distinguiendo capacidades disponibles, permisos pendientes y limitaciones reales de Android.

## Revisión del estado actual

Base local: `de25ed4`, merge del PR #12. Revisados el historial, el código y las descripciones remotas de los PR #10, #11 y #12. No se evaluaron cambios remotos posteriores ni se realizaron pruebas con proveedor o teléfono.

Las discusiones de los tres PR no devolvieron comentarios adicionales. El intento de consultar la documentación pública de Decisions desde este entorno recibió HTTP 403 del túnel de red: el contrato vigente de extracción sigue sin comprobarse, no se da por inexistente. La revisión independiente del diseño no encontró preguntas materiales adicionales a la adaptación de extracción ya explicitada al final.

| Origen | Comportamiento actual | Cambio necesario |
| --- | --- | --- |
| PR #10, `bfca05e` | Gemini Decisions respalda al parser y solo cuando está habilitado. Veinticuatro opciones, incluidas `instinct` y `unsupported`. | Clasificación inicial para toda solicitud nueva; catálogo único y fallback por falta de coincidencia. |
| `HybridPhoneRouter.needsDecision` | Omite acciones reconocidas localmente y determinadas consultas por prefijos. | Quitar decisiones por regex antes de clasificar. |
| `HybridPhoneRouter.route` | Baja confianza y opciones desconocidas bloquean el pedido; parámetros libres piden repetición. | Baja confianza válida deriva a Instinct; intent conocido con campos faltantes pide solamente esos campos. |
| PR #11, `2cbeb0a` | Configuración de clave y cronómetro mediante accesibilidad. | Conservar credenciales y ejecutor; revisar la opción de modo local que contradice el nuevo flujo obligatorio. |
| PR #12, `b332654` / `90511b7` | `ConversationSession` interpreta nuevas entradas antes de Gemini y `StructuredCommandClient` intenta hasta diez acciones antes de Decisions. | Retirar ambos caminos de la entrada inicial; conservar interacción contextual y ejecución de rutinas ya definidas. |
| `PhoneCommandParser` | Rechaza alarmas con fecha específica; convierte órdenes Wi-Fi/Bluetooth en abrir ajustes. | Preservar fecha y separar explícitamente cambiar conectividad de abrir configuración. |
| `GeminiDecisionsClient` | El contrato implementado devuelve `choice` y puntuaciones, no parámetros libres. | Resolver explícitamente el mecanismo de extracción; no asumir que cambiar un prompt agrega campos al endpoint. |
| `PhoneActionExecutor` | Ya diferencia resultados completados, entregados, permisos/datos pendientes, rechazados e inciertos. | Conservar la separación y no activar fallback después del reconocimiento. |
| `sendFloatingText` / `sendFloatingAudio` | Envían el texto capturado o el M4A original a Instinct. | Conservarlos sin resumir ni reconstruir el pedido. |

Las pruebas existentes fijan parte del comportamiento que se debe reemplazar: comandos locales que nunca consultan Google, baja confianza bloqueada y secuencias libres resueltas localmente. No basta con agregar pruebas nuevas: esas expectativas deben actualizarse expresamente.

## Enfoques

1. Ampliar el parser y mantener Gemini como respaldo: no satisface el orden solicitado ni el reconocimiento semántico uniforme.
2. Decisions primero, registro tipado compartido y extracción limitada al intent seleccionado: enfoque que implementa la arquitectura solicitada. Reutiliza ejecutores y guardas existentes.
3. Modelo generativo general que produzca planes antes de clasificar: reproduce el problema introducido en PR #12 y contradice la prohibición de razonamiento abierto.

El enfoque 2 deriva de la especificación del usuario. La siguiente adaptación técnica es una propuesta del constructor, pendiente de revisión: mantener `/v1beta/decisions` para clasificar y usar una extracción estructurada restringida solo cuando el intent reconocido requiere parámetros libres. No sustituye Decisions por un agente generativo. Antes de implementarla se comprobará el contrato vigente del proveedor; si Decisions permite extracción tipada real, se preferirá esa capacidad y se evitará la segunda llamada.

## Contratos y flujo

`OriginalRequest` conserva identificador de solicitud, texto exacto, origen, referencia opcional al audio y generación de sesión. Normalizar para validar no modifica el original.

`IntentDefinition` declara ID estable, categoría, descripción semántica, ejemplos equivalentes, parámetros con tipos y validaciones, requisitos de acceso, mecanismo Android, adaptador, efectos, confirmación y resultado esperado. Las expresiones son ejemplos semánticos, nunca una tabla literal de coincidencias.

`IntentRegistry` verifica unicidad de IDs y que toda definición ejecutable tenga esquema y adaptador. Provee las opciones para Decisions, los esquemas para extracción y la resolución de ejecución. Ningún `when` paralelo mantiene otro catálogo. El modelo nunca entrega componentes Android, intents arbitrarios, código o URLs ejecutables.

`ClassificationResult` diferencia `Recognized(intentId, scores)`, `NoMatch` y fallo técnico. Una respuesta válida cuyo máximo queda por debajo de 0,95, un pedido general o una instrucción compleja son `NoMatch`. Se conserva inicialmente el umbral 0,95 de la implementación actual, sujeto a evaluación en español. NaN, probabilidades contradictorias, JSON malformado y un ID ajeno a las opciones enviadas son errores del proveedor, no confianza baja válida. Se conserva el pedido y se ofrece reintentar ante ese fallo técnico, sin ejecutar ni enviar automáticamente.

La validación de una acción directa exige además que el clasificador descarte negaciones, citas, hipótesis y condiciones no representables. Una consulta local como «¿cuánta batería queda?» sí pertenece al catálogo; la comprobación no puede exigir un verbo imperativo para toda categoría.

Flujo de una entrada nueva:

1. Conservar el original e iniciar una generación cancelable.
2. Clasificar con Decisions contra el catálogo completo. No invocar antes el parser, la sesión conversacional ni el extractor generativo.
3. `NoMatch`: entregar exactamente el original al transporte existente de Instinct. No generar una respuesta intermedia ni resumir.
4. `Recognized`: crear el candidato del intent seleccionado. Las acciones sin campos libres no requieren otra llamada. Para las demás, extraer solamente campos de ese esquema; no permitir seleccionar otro intent, añadir pasos o completar condiciones omitidas.
5. Validar tipos, límites, campos desconocidos, referencias, zona horaria y fidelidad de todas las restricciones. Un campo requerido ausente produce una pregunta concreta; no se inventa un valor.
6. Resolver permisos, selección de entidades y confirmaciones. Un intent reconocido con permiso denegado o sin app compatible sigue siendo un caso local.
7. Ejecutar el adaptador registrado y presentar su resultado real. Abrir una pantalla no demuestra que se haya realizado la acción.

### Contexto existente

Confirmar, cancelar, elegir «el segundo» y responder un campo pendiente son continuaciones de un intent ya clasificado, no solicitudes nuevas. Solo se consumen si corresponden inequívocamente al estado pendiente. Una entrada que expresa un pedido nuevo vuelve a Decisions; no se descarta ni se interpreta como respuesta forzada. Cambiar el texto, cerrar, pausar o cambiar de sesión cancela operaciones y descarta resultados tardíos tanto de clasificación como de extracción.

Las secuencias arbitrarias expresadas en una solicitud nueva pasan completas a Instinct. `WorkflowRunner` puede ejecutar una rutina previamente registrada como una sola capacidad local y puede recuperar una ejecución ya autorizada; no interpreta nuevos planes. Las rutinas persistidas no existen hoy: su soporte requiere un repositorio explícito, con alta/importación revisable, IDs estables y comandos tipados validados. No se crean rutinas deduciéndolas de pedidos casuales.

### Configuración

El flujo nuevo requiere configurar Decisions. Sin clave, desactivado, sin red o sin acceso al modelo se presenta un error de configuración/conexión y se conserva el original. No se ejecutan órdenes por parser como sustitución silenciosa. Se mantiene la opción explícita de enviar el original a Instinct, con las mismas guardas del transporte actual. Las claves siguen cifradas fuera de backups y logs. No se envían contactos, archivos ni contenido de notificaciones para clasificar.

## Catálogo por categorías

Cada ID de las familias siguientes tendrá su propia definición de parámetros, ejemplos, permisos, ejecución y resultado. Las familias no autorizan operaciones genéricas ni sustituyen los IDs individuales. Los parámetros de selección de entidades se resuelven contra Android y nunca se inventan identificadores.

| Categoría | IDs y parámetros esenciales | Expresiones equivalentes | Permisos / mecanismo / resultado esperado |
| --- | --- | --- | --- |
| Alarmas | `alarm.create` (hora, fecha o repetición, etiqueta opcional), `alarm.list`, `alarm.dismiss` (selector inequívoco), `alarm.snooze` (minutos cuando la API lo admite), `alarm.update` (alarma, cambios) | «poné una alarma», «despertame»; «mostrá mis alarmas»; «cancelá la de las ocho»; «posponé la alarma»; «cambiá la hora» | SET_ALARM; AlarmClock para crear/listar/descartar/posponer. Editar o fechas no representables requieren integración verificada o revisión en Reloj. Nunca cancelar otra alarma ni crear una repetición para simular una fecha. |
| Temporizadores | `timer.create` (duración, etiqueta opcional), `timer.list`, `timer.dismiss` (selector), `timer.update` (selector, duración), `timer.pause`, `timer.resume` (selector) | «timer de diez minutos», «avisame en diez minutos»; «cancelá el timer»; «dale cinco minutos más» | AlarmClock y adaptador de reloj con capacidades comprobadas; los cambios sin API pública requieren integración autorizada. Resultado es entrega o estado confirmado, según evidencia. |
| Cronómetro | `stopwatch.start`, `.pause`, `.resume`, `.reset`, `.show` | «iniciá el cronómetro», «empezá a contar»; «detené el cronómetro» | Ejecutor existente de Reloj/accesibilidad; reset conserva confirmación. No reemplaza temporizadores. |
| Calendario | `calendar.create` (título, inicio, zona, fin/duración), `.agenda` (intervalo), `.update` (evento, cambios), `.delete` (evento) | «agendá reunión», «creá un evento»; «qué tengo mañana»; «mové la reunión»; «cancelá el evento» | INSERT entrega borrador; READ_CALENDAR para agenda/resolver eventos; WRITE_CALENDAR para cambios directos confirmados. IDs y calendarios se eligen en datos reales. Múltiples coincidencias piden selección. |
| Llamadas | `call.dial` (contacto/número), `.start` (destinatario), `.open_dialer` | «marcá a Ana», «prepará este número»; «llamá a Ana»; «abrí el marcador» | DIAL, CALL con CALL_PHONE o integración de telefonía admitida; READ_CONTACTS solo para buscar nombres. Marcar e iniciar llamada son acciones distintas. |
| Mensajería | `message.open` (integración, destinatario), `.draft` (integración, destinatario, cuerpo), `.send` (integración, destinatario, cuerpo) | «abrí el chat con Ana»; «redactá para Ana»; «mandale este mensaje» | SENDTO para SMS/borrador, integración explícita para envío. Confirmar destinatario y contenido cuando corresponda. No prometer envío mediante una app que solo permite abrir un borrador. La salida a Instinct sigue siendo un transporte independiente. |
| Aplicaciones | `app.open` (app), `.search` (app, consulta), `.share` (contenido autorizado, app opcional), `.settings` (app) | «abrí Spotify», «iniciá Spotify»; «buscá en YouTube»; «compartí esto»; «configuración de WhatsApp» | PackageManager, búsqueda registrada por app, ACTION_SEND y APPLICATION_DETAILS_SETTINGS. Sin selección de contenido no se adivina «esto». No se garantiza reproducción por abrir una app. |
| Android | `volume.up`, `.down`, `.set` (canal, porcentaje); `brightness.set` (porcentaje); `torch.on`, `.off`; `wifi.enable`, `.disable`, `.settings`; `bluetooth.enable`, `.disable`, `.settings`; `silent.enable`, `.disable`; `dnd.enable`, `.disable` | «subí el volumen», «más fuerte»; «bajá el brillo»; «prendé la linterna», «iluminá con la linterna»; «activá Wi-Fi»; «abrí los ajustes de Wi-Fi» | AudioManager, WRITE_SETTINGS para brillo, CAMERA para linterna, accesos DND y Bluetooth según versión. Android moderno restringe conmutar Wi-Fi/Bluetooth: explicar la limitación y ofrecer panel para intervención explícita; no declarar cambio realizado ni confundirlo con el intent de abrir ajustes. |
| Multimedia | `media.play`, `.pause`, `.next`, `.previous`, `.status` (sesión/app opcional); `media.search` (consulta, app opcional) | «seguí la música», «reanudá»; «pausá»; «pasá de tema»; «tema anterior» | Sesiones multimedia y acceso especial actual; validar acción admitida y elegir sesión si hay varias. La búsqueda usa la integración registrada y no implica éxito de reproducción. Volumen comparte los IDs de Android. |
| Navegación | `navigation.map` (consulta), `.route` (destino, modo), `.save_place` (alias, dirección) | «buscá farmacias», «mostrá farmacias en el mapa»; «llevame a casa», «iniciá ruta a casa» | Intents/URLs constantes del adaptador actual; parámetros codificados; alias resuelto desde repositorio. Rutas con restricciones solo si el adaptador puede representarlas todas. |
| Contactos | `contact.search` (nombre), `.phones` (contacto); comunicación delega en `call` / `message` | «buscá a Ana», «encontrá el contacto Ana»; «qué teléfono tiene Ana» | READ_CONTACTS y ContactsContract, o selector nativo. No envía la agenda a Gemini. El resultado muestra solo contactos realmente encontrados. |
| Archivos | `file.open`, `.search` (nombre/tipo); `.share` (URI autorizada), `.rename` (URI, nombre), `.move` / `.copy` (URI, destino), `.delete` (URI) | «abrí un PDF», «buscá el informe»; «compartí el archivo»; «renombrá», «mové», «copiá», «borrá» | Storage Access Framework / DocumentsContract y concesiones de URI. Elegir archivos fuera del ámbito autorizado; no pedir acceso global como sustituto. Confirmar eliminación y validar permiso de proveedor. Abrir selector es HANDOFF, no prueba que se abrió el archivo solicitado. |
| Cámara y grabación | `camera.open_photo`, `.open_video`; `camera.capture_photo`, `.capture_video`; `recorder.start` (opciones admitidas), `.stop` (sesión propia) | «abrí cámara», «cámara de fotos»; «sacá una foto»; «grabá un video»; «grabá una nota de voz» | Intents públicos de captura y entrega de URI cuando corresponde; RECORD_AUDIO para grabación propia. No convertir «sacá una foto» en solo abrir la cámara ni terminar una grabación que Insto no controla. |
| Notificaciones | `notification.list` (filtro opcional), `.open` (clave real), `.dismiss` (clave real) | «qué notificaciones tengo»; «abrí la de Ana»; «descartá esa notificación» | NotificationListenerService habilitado expresamente para este uso, actualmente limitado a multimedia; PendingIntent original y cancelNotification. No leer contenido como efecto de habilitar control multimedia. No inventar claves ni descartar notificaciones no cancelables. |
| Dispositivo | `device.battery`, `.settings`, `.accessibility`, `.shortcut` (ID registrado) | «cuánta batería queda», «estado de batería»; «abrí ajustes», «abrí accesibilidad»; «ejecutá mi acceso directo» | BatteryManager/estado de batería; Settings; ShortcutManager o integración exportada/autorizada. Solo accesos directos realmente accesibles a Insto, sin lanzar componentes arbitrarios. |
| Automatización local | `routine.run` (ID previamente configurado), `.list`; `mode.temporary` (tipo, duración, parámetros), `.end` | «ejecutá estudio», «activá mi rutina estudio»; «mis rutinas»; «silencio por una hora»; «restaurá el modo» | Repositorio local de rutinas más WorkflowRunner; TemporaryModeManager conserva permisos, persistencia y propiedad de ajustes. No interpreta instrucciones complejas nuevas ni configura disparadores implícitos. |

### Disponibilidad y fidelidad

El catálogo semántico enumera operaciones conocidas, pero cada definición declara su disponibilidad real. Un adaptador ausente no puede anunciarse como implementado. La entrega debe incluir adaptadores para las operaciones implementables con APIs públicas e integraciones autorizadas, y explicar limitaciones de plataforma para el resto. Crear una entrada con un rechazo constante no cuenta como implementar la acción.

Un intent reconocido que no puede ejecutarse en ese dispositivo se informa localmente como no disponible o acceso pendiente. No se inventa otra acción ni se manda a Instinct como si no se hubiera reconocido. Ofrecer abrir ajustes/Reloj es una alternativa explícita, cuya aceptación crea su propio intent.

Para «mañana a las ocho» se conserva fecha y hora. Si la hora es ambigua según el contexto, se pregunta el período; si no hay API capaz de crear la alarma para esa fecha, se comunica esa limitación. No se elimina «mañana», no se sustituye por un día semanal y no se promete una fecha que AlarmClock no permite verificar.

Los valores por defecto admitidos se especifican por intent, se muestran cuando afectan el resultado y nunca completan destinatarios, fechas, contenido o restricciones que el usuario no dio. Si una extracción no puede representar fielmente el pedido, se pide una aclaración local; no se ejecuta un subconjunto.

## Resultados y errores

- `NoMatch`: original completo a Instinct, sin acciones locales parciales.
- `NeedsParameters` / `NeedsSelection` / `NeedsPermission` / `NeedsConfirmation`: conserva el intent y el original; interacción local.
- `Completed`: efecto comprobado por API o estado observado.
- `Handoff`: solicitud entregada a otra app; el usuario debe completar/verificar allí.
- `ExecutionFailed` / `Unavailable`: intent reconocido, ejecución impedida; sin fallback automático.
- `Uncertain`: pudo existir efecto; no repetir automáticamente.
- `ClassificationFailed` / `ExtractionFailed`: problema técnico del proveedor o contrato; conserva el original y ofrece reintento. Nunca se transforma silenciosamente en otro comando.

No registrar contenido sensible para diagnóstico. La telemetría local de desarrollo puede medir latencia, etapa y estado sin claves, texto, destinatarios ni cuerpos de notificaciones.

## Verificación de aceptación

1. Toda entrada nueva consulta Decisions antes de parser/extractor/ejecutor; incluso una orden literal conocida o una consulta general.
2. «Poné una alarma…» y «despertame…» llegan al mismo ID; extracción conserva fecha, hora y todas las restricciones. Se prueban respuestas simuladas y un conjunto etiquetado en español; simulación no prueba precisión del proveedor real.
3. Las opciones de Decisions y los esquemas de extracción se generan desde el mismo registro. IDs duplicados, parámetros desconocidos y ausencia de adaptador impiden registrar capacidades ejecutables.
4. `NoMatch`, baja confianza válida, consultas generales, citas y planes nuevos complejos envían exactamente el texto/M4A original una sola vez.
5. Una respuesta de extracción no puede cambiar de ID ni agregar comandos. Ausencia de campos, tipos inválidos, horario ambiguo, DST, fechas pasadas y restricciones no representables nunca producen efectos parciales.
6. Permisos denegados, app ausente, operación no admitida, error de API y resultado incierto de un intent reconocido nunca despachan a Instinct ni otra acción.
7. Confirmaciones/selecciones/aclaraciones inequívocas conservan contexto; un pedido nuevo vuelve a clasificar. Editar/pausar/cerrar descarta respuestas tardías; reintentar conserva la grabación y transcripción.
8. Alarmas, agenda, llamadas, mensajería, archivos y notificaciones tienen pruebas de adaptadores que verifican destinatario/recurso y contrato de resultado. Cambios destructivos no afectan entidades vecinas.
9. Rutinas configuradas ejecutan solamente sus pasos registrados, conservando recuperación y sin repetir efectos inciertos. Una nueva secuencia libre no se convierte en rutina.
10. Ejecutar compilación y suite completa con las herramientas disponibles, informar bloqueos reales de SDK/dependencias por separado. Validación física y del proveedor se declara pendiente si faltan dispositivo o credenciales.

## Ámbito de cambios previsto

Introducir el registro y coordinador de decisiones; refactorizar HybridPhoneRouter, GeminiDecisionsClient y StructuredCommandClient; integrar la nueva entrada en MainViewModel; restringir ConversationSession a continuaciones; ampliar PhoneCommand/Codec y los adaptadores por categoría; declarar accesos Android únicamente para capacidades implementadas; añadir el repositorio de rutinas configuradas; actualizar configuración/UI y pruebas que hoy fijan el orden anterior.

Se conserva Kotlin, coroutines, Compose, Groq, las guardas de WhatsApp, Android Keystore y las políticas de persistencia de workflows/modos. El constructor decide separación concreta de archivos y detalles internos. No se añaden razonamiento general, operación universal de pantallas, lectura continua de notificaciones ni nuevas integraciones externas sin soporte real.

## Punto técnico que requiere revisión del usuario

La especificación describe Decisions identificando intent y extrayendo parámetros; el contrato actualmente implementado solo clasifica. La propuesta es una etapa inicial compuesta: Decisions selecciona el intent y, solo si hacen falta campos libres, una llamada de extracción estructurada restringida a ese único intent obtiene los parámetros. Instinct sigue siendo el segundo nivel para todo lo demás. Esta adaptación añade una llamada para esos intents y debe aprobarse antes de implementarla; no se presenta como extracción nativa ya comprobada de Decisions.
