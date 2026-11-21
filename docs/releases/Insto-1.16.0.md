# Insto 1.16.0 (código 25): autonomía por acción, estado del teléfono y buzón firmado

Tres cambios para que Instinct actúe con más criterio y menos ruido. Qué hace solo se elige **por tipo de acción**, con horas de silencio y un ajuste automático cuando deshacés mucho. Con la bitácora configurada, Insto publica un **resumen del estado del teléfono** para que Instinct decida con contexto. Y las órdenes pueden llegar, además de por WhatsApp, por un **buzón firmado** en la misma planilla. Con los valores por defecto todo funciona igual que en 1.15.0. No suma dependencias. Protocolo Insto v4.

Detalle completo: [Órdenes de Instinct](../INSTINCT-PROPUESTAS.md): [Autonomía por acción](../INSTINCT-PROPUESTAS.md#autonomía-por-acción-1160), [Estado del teléfono](../INSTINCT-PROPUESTAS.md#estado-del-teléfono-1160) y [Buzón firmado](../INSTINCT-PROPUESTAS.md#buzón-firmado-1160).

## Autonomía por acción

- **Ajustes → Propuestas de Instinct → Autonomía** reemplaza «Pedirme confirmación antes de ejecutar». Una fila por categoría (volumen, linterna, silencio y No molestar, música, alarmas y temporizadores, abrir apps y búsquedas, calendario, rutinas, avisos para vos) con tres niveles: **Solo** (por defecto), **Confirmar** o **Nunca**.
- Una diferida usa la categoría de su orden interior; una rutina usa «rutinas» y además la más estricta de sus pasos.
- **Migración:** si tenías encendido «Pedirme confirmación antes de ejecutar», todas las categorías arrancan en Confirmar y el archivo viejo se borra. La política vive en `noBackupFilesDir` (`instinct-autonomy.json`).
- **Solo puede endurecer:** `InstinctProposalCenter.gate` decide primero los bloqueos de siempre (teléfono bloqueado, otra app sin Accesibilidad, diferida atrasada, rutina cambiada) y después la política; el freno se consume último. Nunca gana sobre todo.
- **Horas de silencio** opcionales (por ejemplo, 00:00 a 07:00), que cruzan la medianoche: dentro del rango cada Solo pasa a Confirmar, salvo silenciar, vibrar o activar No molestar si dejás encendida la excepción.
- **Ajuste adaptativo** (`InstinctTrustLedger`, solo contadores, 30 días, `instinct-trust.json`): 3 Deshacer en 7 días, o 30 % con al menos 5 ejecutadas, bajan la categoría a Confirmar con un aviso y el botón «Volver a automático». 8 confirmaciones sin descartes en 14 días traen una sola sugerencia para subirla («Sí» / «No»; el «No» la silencia 30 días). Nunca sube sola y nunca toca «Nunca».
- Los acuses dicen el motivo concreto: `#insto-espera … → la persona pide confirmación para alarmas y temporizadores`, `… → horas de silencio`, `#insto-error … → la persona no permite esta acción`.
- El catálogo suma «Qué hace Insto solo y qué espera confirmación», generado desde la política vigente.

## Estado del teléfono

- Siempre activo con la bitácora configurada y «Recibir órdenes de Instinct» encendido. Campos fijos: `sonido`, `dnd`, `alarma` (solo la hora), `evento` (en curso y próximo en 12 horas, o «libre»), `audio` (tipo, nunca el nombre), `bloqueado`, `modo_temporal` y `programadas`. Nunca volumen, batería, red, ubicación, contactos, notificaciones ni identificadores.
- Evento de bitácora `kind=estado` con una línea `clave=valor; …` de hasta 600 caracteres, por la misma bandeja y con la misma guardia anti-bucle (un título de evento no puede escribir «#insto …»).
- Se publica cuando algo cambia (agrupado 30 segundos), cada 30 minutos como latido (`JobScheduler` con red), después de cada orden que se ejecutó y con `#insto ver estado` (solo lectura, no consume el freno). Sale solo por la bitácora: con el canal en WhatsApp, `ver estado` responde `#insto-error ver estado → el estado solo se publica en la bitácora`.
- `READ_CALENDAR` se pide una sola vez, desde Ajustes; sin permiso el campo dice `sin permiso`. Un campo que falla dice `sin datos` y el estado igual se publica.
- Ajustes: «Estado para Instinct: último envío HH:MM» y el botón «Publicar estado ahora».
- Apps Script: `GET ?token=…&action=estado` devuelve el último evento `estado`.

## Buzón firmado

- Órdenes por la misma planilla, firmadas con **HMAC-SHA256** y una clave que conocen solo Insto e Instinct. La clave **no vive en el Apps Script ni en la planilla**: con el token o la planilla no alcanza para inyectar órdenes. Cadena canónica `insto-v4\n<id>\n<at>\n<líneas…>`, firma en hex, comparación en tiempo constante.
- Se rechazan las firmas inválidas (sin respuesta, solo cuentan en el diagnóstico), las órdenes de más de 15 minutos o más de 2 minutos en el futuro y los ids repetidos (48 horas en disco, `instinct-mailbox-seen.json`). Hasta 3 líneas por orden y 10 órdenes por sondeo.
- Sondeo con `JobScheduler` cada 2, 5 (por defecto) o 15 minutos, y enseguida al abrir Insto o el panel y al llegar un mensaje de Instinct por WhatsApp. El id se marca visto en disco antes de despachar; `POST tomadas` lleva todas las leídas.
- Mismo camino que WhatsApp (`ProposalSource.BUZON`): validador, lista blanca, freno compartido, autonomía, Deshacer y diferidas. Los acuses del buzón van siempre a la bitácora.
- Nueva acción **`#insto avisarme <texto>`**, solo por el buzón: una notificación de Insto para vos («Insto · mensajes de Instinct»), 1 a 200 caracteres en texto plano, 3 por hora, sin sonido en las horas de silencio. No es un mensaje a nadie.
- Ajustes → **Buzón**: «Recibir órdenes por el buzón», «Aceptar órdenes de» (WhatsApp y buzón / Solo buzón), el tope «Las órdenes por WhatsApp (sin firma) como máximo en» (Solo / Confirmar), el intervalo, «Generar clave», «Copiar clave» y «Probar buzón».
- Apps Script compatible con 1.15.0: hoja `Buzón` y las acciones `orden`, `ordenes` y `tomadas`; un `POST` o `GET` sin `action` funciona exactamente igual que antes. La doc trae una prueba manual con `curl`.

## Catálogo: Protocolo Insto v4

- Secciones nuevas: «Qué hace Insto solo y qué espera confirmación», «Estado del teléfono» (con bitácora) y «Buzón firmado» (con el buzón encendido), con un ejemplo de cadena canónica y un pseudocódigo HMAC.
- **Nunca incluye la clave**, ni en «Copiar instrucciones» ni en la copia por WhatsApp, que además sigue sin URL ni token; Insto comprueba las tres antes de enviarla.
- Copias de referencia: `docs/instinct-catalog.txt`, `docs/instinct-catalog-bitacora.txt` y la nueva `docs/instinct-catalog-buzon.txt`; un test compara cada una con el código.
- La doc suma «Qué tiene que hacer Instinct», con un ejemplo de orden firmada en Python y su vector de prueba.

## Privacidad

Todo lo nuevo vive en `noBackupFilesDir` y nunca entra en copias de seguridad ni se restaura. El ledger de autonomía y los ids vistos del buzón son solo contadores e identificadores. El estado sale únicamente hacia tu Apps Script y solo con los campos fijos. La clave del buzón nunca se muestra entera, no va a Logcat, al diagnóstico ni a WhatsApp.

## Verificación

- `python3 scripts/test-phone-core.py`: suite JVM sin Android, con las pruebas nuevas `InstinctAutonomyTest` (política por categoría y por rutina, migración, que la política no afloje ningún bloqueo, horas de silencio que cruzan la medianoche, las dos reglas del ledger con reloj falso y sus bordes, reinicio de contadores, el «No» de 30 días, «Nunca» intacto), `InstinctStateTest` (línea, recorte, campos prohibidos, título de evento malicioso, *debounce*, latido, `sin permiso` y `sin datos`) e `InstinctMailboxTest` (vectores HMAC, comparación en tiempo constante, firma, ventana de tiempo, id repetido después de releer el archivo, límites de 3 líneas y 10 órdenes, id visto antes del despacho, `avisarme`, tope de WhatsApp y catálogo sin la clave).
- `./gradlew testDebugUnitTest assembleDebug`: con Robolectric, `InstinctAutonomyFlowTest`, `InstinctStateFlowTest` e `InstinctMailboxFlowTest` (freno compartido entre fuentes, acuses del buzón a la bitácora con el canal en WhatsApp, `avisarme` rechazado desde WhatsApp y con su freno, «Solo buzón», el tope de WhatsApp, `ver estado` sin consumir el freno).
- El Apps Script se probó en Node con dobles de `SpreadsheetApp`, `ContentService` y `LockService` (compatibilidad con 1.15.0, `estado`, `orden`, `ordenes`, `tomadas`). No se probó contra un Apps Script real ni en un teléfono físico: la redirección de Google, las cuotas, Doze y los broadcasts del sistema quedan para la prueba manual.
