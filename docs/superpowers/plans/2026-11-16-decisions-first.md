# Decisions-first Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. Ejecución nativa autorizada por «Dale...implementar eso» tras rechazar la extracción generativa.

**Goal:** Clasificar primero con Decisions contra un catálogo extensible y derivar las no coincidencias a Instinct con el original completo.
**Architecture:** Un registro único declara IDs, ejemplos semánticos, parámetros, permisos, ejecución y resultado. Decisions selecciona el ID; parámetros y continuaciones se validan localmente para ese ID. No hay extractor generativo.
**Tech Stack:** Kotlin, coroutines, Android, pruebas JUnit/Robolectric existentes.
**Spec:** ../specs/2026-11-16-decisions-first-design.md, con la corrección aprobada al principio como autoridad.

## Global Constraints

- Decisions clasifica toda solicitud nueva antes de cualquier parser o ejecutor.
- No coincidencia / confianza insuficiente: original completo a Instinct.
- Intent reconocido con fallo de ejecución: error local, nunca fallback automático.
- Sin llamada generativa adicional ni planes libres nuevos ejecutados localmente.
- No inventar parámetros, entidades ni capacidades Android; conservar accesos y confirmaciones.

## Review Focus

- Parser reconoce otra acción que la elegida: nunca ejecutar esa acción distinta.
- Consulta nueva durante una aclaración: volver a Decisions sin perder el original.
- Respuesta tardía después de editar: cero ejecución o envío.
- Falta de clave o fallo del proveedor: conservar pedido sin ejecución implícita.
- Capacidad Android restringida: informar límite real sin simular éxito ni abrir una alternativa automáticamente.

### Task 1: Registro y routing

**Files:** crear phone/LocalIntentRegistry.kt, modificar phone/HybridPhoneRouter.kt y phone/GeminiDecisionsClient.kt; pruebas phone/HybridPhoneRouterTest.kt y phone/LocalIntentRegistryTest.kt.
**Interfaces:** LocalIntentRegistry.definitions/choices/resolve(id,text), IntentDefinition con metadatos y resolución local; HybridPhoneRouter.route(text) clasifica primero, permite proveedor simulado por DecisionClassifier.

- [x] Escribir y ejecutar regresiones: comandos literales llaman Decisions, no coincidencia/baja confianza van a Instinct, IDs desconocidos no ejecutan, cancelación se propaga y errores técnicos conservan pedido.
- [x] Implementar registro por categorías con esquemas/metadatos y resolución restringida por ID. Reutilizar comandos existentes y declarar límites reales de integraciones nuevas.
- [x] Generar choices e instrucciones desde el registro; impedir que extracción local cambie el ID seleccionado.
- [x] Verificar pruebas de routing, catálogo y contrato HTTP.

### Task 2: Integración Android y floating

**Files:** modificar ui/MainViewModel.kt, configuración Gemini y pruebas FloatingHybridRoutingTest/FloatingWorkflowTest/FloatingPhoneRoutingTest; adaptar PhoneCommand/Codec/PhoneActionExecutor para acciones públicas nuevas del catálogo.
**Interfaces:** entrada nueva llama solamente hybridRouter.route(text); continuaciones inequívocas resuelven un ID ya reconocido; resultado PhoneActionResult conserva HANDOFF/COMPLETED/REJECTED.

- [x] Escribir regresiones: extractor generativo nunca llamado, original exacto a Instinct, orden literal rechazado por Decisions no ejecuta, fallo local no despacha, aclaración y edición/cancelación.
- [x] Retirar extracción generativa de la ruta inicial y el bypass de ConversationSession. Mantener recuperación/confirmaciones de ejecuciones ya reconocidas.
- [x] Añadir acciones Android públicas con parámetros validados y acceso específico; no afirmar que un handoff completó el efecto.
- [x] Actualizar expectativas antiguas que fijaban parser primero; compilar fuentes de pruebas y ejecutar verificaciones disponibles.

### Task 3: Verificación, documentación y revisión

**Files:** docs/FLOATING-ASSISTANT.md, catálogo generado/documentado, esta bitácora y pruebas afectadas.

- [x] Ejecutar suite completa y compilación, o registrar el bloqueo exacto sin afirmar éxito.
- [x] Revisar independientemente el cambio completo y resolver hallazgos materiales con regresiones.
- [x] Documentar flujo, categorías, límites reales de parámetros/permisos/proveedor y resultados de verificación.

## Execution ledger

- Base: ff443d8, checkout limpio en rama work; se trabaja en el entorno de tarea existente, sin tocar main ni hacer push.
- Pre-flight: Task 1 produce registro/routing consumido por Task 2; Task 3 verifica ambas. La corrección del usuario excluye StructuredCommandClient de toda ruta de producción.
- Baseline: ./gradlew testDebugUnitTest bloqueado antes de compilar por UnknownHostException de services.gradle.org. Se prepara tooling externo al repositorio con proxy disponible; no se modifica el wrapper del proyecto.

- Task 1: completa. Registro compartido de 87 intents, catorce categorías, opciones del proveedor generadas desde él; clasificación obligatoria, fallback por no coincidencia/confianza válida insuficiente y rechazo de IDs/puntuaciones inválidos. Regresiones de routing observadas RED→GREEN.
- Task 2: completa. Se eliminó StructuredCommandClient y el bypass de interpretación previa. Parámetros/selecciones conservan el intent; editar un fallo exige nueva clasificación del texto exacto. Integrados permisos y adaptadores Android públicos.
- Ampliación por hallazgo de revisión: las capacidades que inicialmente solo declaraban unavailable pero tenían API pública se implementaron con actividades nativas: SAF (abrir/buscar/compartir/renombrar/copiar/mover/eliminar), agenda (consultar/editar/eliminar), captura, llamada directa, envío WhatsApp y rutinas importadas/listadas/ejecutadas. Quedan diez límites explícitos de API/rol/proveedor, sin simular éxito.
- Task 3: verificación completada dentro de las capacidades del entorno. Producción y todos los fuentes de pruebas compilan. 146/146 pruebas JVM en 25 clases pasan, incluidas regresiones anteriores y catálogo, codec, nombres de archivo, campos, HTTP simulado y rutinas. Se usó un init script externo que excluye únicamente org.robolectric:nativeruntime-dist-compat:1.0.18; no se alteraron las dependencias del proyecto.
- Suite completa normal: intentada, bloqueada antes de ejecutar assertions por HTTP 403 al descargar nativeruntime-dist-compat:1.0.18 desde el mirror Maven. No se declara aprobada.
- Integración Robolectric: FloatingHybridRoutingTest, FloatingWorkflowTest y PlatformActionExecutorTest intentadas con la exclusión externa; las tres fallaron en inicialización de clase al descargar android-all-instrumented:9-robolectric-4913185-2-i7 por HTTP 403. Sus assertions Android no llegaron a ejecutarse.
- Revisión independiente: sin Critical; cuatro Important. Corregidos en una pasada: captura genérica OpenApp bloqueaba intents nuevos; preguntas se consumían como campos; cancelar selecciones podía enviarse a Instinct; faltaban adaptadores públicos de categorías declaradas. La ampliación de actividades nativas se revisó estáticamente y compila; no se afirma validación física.
- Regresiones adicionales: fecha relativa de Calendario fijada al primer turno (probe rojo esperaba 17 y obtenía 18, luego verde); nombres de app/rutina completan el intent existente (probe rojo devolvía null, luego verde); fecha/período de alarma conservados; nombre exacto de archivo diferencia puntuación y mayúsculas. Las preguntas nuevas vuelven al clasificador.
- Documentación: especificación corregida sin propuesta generativa pendiente, catálogo completo con permisos/mecanismos/resultados, ejemplo JSON de rutina importable, guía de flujo actualizada. Versión 1.8.0 / código 13.
- Proveedor/dispositivo: no se probó con credenciales Gemini ni teléfono. La documentación pública del proveedor recibió 403; no se afirma precisión, latencia ni compatibilidad física comprobada.
- Integración de rama: se conserva work localmente. No se hizo push, merge, publicación ni modificación remota de PR.

- Cierre: ejecución fresca de las 25 clases con --rerun: 146/146 aprobadas, BUILD SUCCESSFUL (23 s); git diff --check correcto. Se conserva el bloqueo externo de la suite completa documentado arriba.
