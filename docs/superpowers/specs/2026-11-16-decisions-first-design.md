# Insto: Decisions primero e Instinct para el resto

Fecha: 2026-11-16. Estado: implementación autorizada por el usuario y realizada en la rama de trabajo.

## Decisión del usuario

Entrada escrita o audio transcripto → Gemini Decisions clasifica semánticamente contra una lista de intents → intent conocido se valida y ejecuta mediante Android; sin coincidencia suficientemente confiable → pedido original completo a Instinct.

El usuario rechazó expresamente una segunda llamada generativa y autorizó implementar este flujo. El diseño anterior proponía extracción estructurada por otro modelo; esa propuesta quedó descartada. El cliente `StructuredCommandClient` y su llamada `generateContent` se eliminaron de producción.

Decisions solo selecciona una opción del catálogo y evalúa si el pedido constituye una acción simple representable. No responde consultas, crea planes ni genera código, componentes Android o nuevos IDs. Las expresiones de ejemplo describen equivalencias semánticas, no una tabla de clasificación literal. Los parámetros se interpretan localmente después de elegir el intent. Cuando falta un campo, se pide ese dato; no se inventa un valor.

## Revisión de commits y PR

Base revisada: `de25ed4`, merge del PR #12. Se leyeron historial, código, descripciones remotas y discusiones de los PR #10, #11 y #12; no había comentarios adicionales en esas discusiones.

| Origen | Comportamiento anterior | Cambio |
| --- | --- | --- |
| PR #10, `bfca05e` | Parser primero; Decisions solo como respaldo opcional. Baja confianza bloqueada. | Toda solicitud nueva pasa por Decisions; baja confianza válida deriva a Instinct. |
| PR #11, `2cbeb0a` | Configuración de clave, modo local y cronómetro por accesibilidad. | Se conservan credenciales y cronómetro; sin Decisions disponible se conserva el pedido, sin bypass silencioso por parser. |
| PR #12, `b332654` / `90511b7` | Conversación y extracción generativa de planes antes de clasificar. | Se retira ese orden. Solo continúan localmente campos y ejecuciones ya reconocidos. Las secuencias nuevas libres van a Instinct. |

## Implementación

- `LocalIntentRegistry` es el registro compartido: ID, categoría, ejemplos, parámetros obligatorios/opcionales, permisos, mecanismo, resultado, resolución tipada y disponibilidad. Rechaza IDs duplicados o reservados. Puede recibir definiciones adicionales sin cambiar el router.
- `GeminiDecisionsClient` obtiene las opciones desde ese registro. El contrato sigue siendo `/v1beta/decisions`, preguntas `choice` y `noul`, sin generación de parámetros.
- `HybridPhoneRouter` consulta al clasificador antes de interpretar campos locales. El candidato del parser anterior nunca autoriza una ejecución por sí mismo ni sustituye el intent elegido.
- `PhoneCommandCodec` valida campos y tipos, límites y comandos persistidos/importados. `PhoneActionExecutor` comprueba bloqueo del dispositivo y requisitos de ejecución.
- `MainViewModel` conserva el transporte original a Instinct y las guardas de generación, cancelación, grabación y transcripción. Nunca sustituye el texto o el M4A por un resumen.

Si se usa un registro personalizado, se suministra la misma instancia tanto al cliente Decisions como al router para mantener catálogo y validación alineados.

## Resultados y confianza

El umbral inicial conserva el valor 0,95 del proyecto para las puntuaciones que autorizan una acción. Es una política conservadora; no se afirma precisión ni latencia real sin evaluar el proveedor.

- Respuesta válida sin coincidencia, instrucción compleja o confianza insuficiente: `PhoneRoute.Instinct`, con el original íntegro.
- ID inventado, JSON inválido, puntuaciones no finitas/inconsistentes, falta de clave, cuota o error de red: fallo técnico, conserva el pedido para reintentar.
- Intent reconocido con campos faltantes: aclaración local vinculada a ese ID.
- Permiso denegado, recurso inexistente, app ausente o error de ejecución: resultado local; nunca fallback automático a Instinct.
- Solicitud entregada a otra app o a una selección nativa: `HANDOFF`, sin afirmar que se guardó, envió o completó.
- Efecto incierto: se informa y no se reproduce automáticamente.

## Catálogo y mecanismos Android

El catálogo completo está en [LOCAL-INTENTS.md](../../LOCAL-INTENTS.md): 87 entradas en las catorce categorías solicitadas. Incluye acciones existentes y adaptadores públicos nuevos para batería, brillo, silencio/No molestar, contactos, SMS en borrador, compartir texto, llamada directa a número y envío por la integración WhatsApp disponible.

Las llamadas directas piden CALL_PHONE y revisión del número. El envío WhatsApp exige número y cuerpo explícitos, confirmación y las guardas existentes de destinatario, contenido y accesibilidad. No utiliza el contacto de Instinct como destinatario por defecto.

### Agenda

`CalendarActionActivity` solicita READ_CALENDAR para consultar o resolver eventos reales, y WRITE_CALENDAR únicamente para eliminar. La consulta cubre el día solicitado. Modificar abre el editor del evento elegido y entrega cambios tipados cuando se proporcionaron; conservar la duración no implica inventar una nueva. Eliminar exige elegir y confirmar el evento. Las series recurrentes se revisan en Calendario, sin eliminar toda la serie como consecuencia implícita de una ocurrencia.

Los borradores de eventos conservan fecha, título y campos. «Mañana» se fija a una fecha al iniciar el borrador, para no desplazarse otro día si se completa después de medianoche. Las alarmas con hoy/mañana conservan la fecha; se rechazan si el contrato de Reloj solo permite una próxima ocurrencia distinta.

### Archivos

`FileActionActivity` usa Storage Access Framework y permisos de URI elegidos por el usuario. Abrir, buscar, compartir, renombrar, copiar, mover y eliminar no requieren acceso global al almacenamiento. Si se indicó un nombre, se comprueba exactamente, conservando puntuación y mayúsculas. Mover requiere origen/destino autorizados y soporte del proveedor; no se convierte silenciosamente en copiar. Las escrituras y eliminaciones se revisan antes de ejecutarse.

Las selecciones nativas y errores de proveedor se informan dentro de Insto. Una interrupción del flujo no reproduce automáticamente un efecto ni supone que no ocurrió.

### Captura y notificaciones

`CaptureActionActivity` utiliza cámara/grabadora nativa con resultados y URIs autorizadas. Solo informa una captura recibida cuando la aplicación devuelve un resultado válido. Las fotos y videos se pueden ver o compartir; las capturas se guardan en archivos privados de Insto. Abrir cámara y capturar siguen siendo intents distintos.

`NotificationAccessService` requiere habilitación explícita separada del acceso multimedia. Solo consulta notificaciones para un pedido de notificaciones. Abrir/descartar exige un título inequívoco y se utiliza el PendingIntent o la clave reales; no se envía su contenido a Gemini.

### Rutinas previamente configuradas

`ConfiguredRoutineStore` importa plantillas JSON revisadas mediante `RoutineImportActivity`. No aprende rutinas de pedidos casuales ni interpreta un plan nuevo. El archivo admite hasta 65.536 caracteres, cincuenta plantillas y diez comandos por plantilla. Rechaza campos desconocidos, nombres duplicados, comandos inválidos, importaciones y ejecución de otras rutinas dentro de una rutina.

Guardar reemplaza atómicamente la configuración después de la revisión. Ejecutar resuelve un nombre guardado, presenta los pasos y utiliza `WorkflowRunner`, conservando persistencia, confirmaciones, entregas, fallos y recuperación sin repetir efectos inciertos. Listar no ejecuta acciones.

Ejemplo importable: [routines.json](../../examples/routines.json). «Importá rutinas» abre el selector; «mostrá mis rutinas» lista; «ejecutá la rutina Estudio» presenta la plantilla para ejecutar.

### Límites explícitos

Diez entradas declaran ausencia de API pública uniforme o de la integración/rol autorizado necesarios: modificar/pausar/reanudar determinados objetos de Reloj, conmutar conectividad restringida, detener una grabación controlada por otra app y ejecutar accesos directos sin proveedor registrado. No tienen un adaptador ficticio que afirme éxito. El reconocimiento informa la limitación localmente y ofrece una alternativa solo como otro pedido explícito.

## Continuaciones y ciclo de vida

Confirmar, cancelar, elegir una opción o completar un campo inequívoco continúa un intent ya reconocido. Las preguntas y solicitudes nuevas vuelven a Decisions. Las aclaraciones no pueden cambiar el tipo de comando. Las respuestas de alarma conservan la fecha; un cambio de día/zona exige repetir los datos completos. Editar un pedido fallido vuelve a clasificar incluso cuando solo cambió su puntuación; un reintento del texto idéntico conserva el comando reconocido.

Editar, pausar o cerrar invalida generaciones anteriores. Una respuesta tardía de red no ejecuta ni envía. El audio se transcribe una sola vez por intento conservado y, cuando corresponde Instinct, se comparte el M4A original.

## Verificación

La bitácora y los resultados exactos están en [el plan](../plans/2026-11-16-decisions-first.md). Se verifican política de routing, catálogo y esquemas, contrato HTTP simulado, conservación de campos, importación de rutinas, identidad de archivos y regresiones anteriores. Compilar fuentes de pruebas no equivale a ejecutar sus assertions Android.

La suite completa y la inicialización Robolectric encontraron bloqueos HTTP 403 al descargar artefactos externos. No hubo dispositivo ni credenciales para probar el proveedor y las aplicaciones reales; esa validación sigue pendiente.
