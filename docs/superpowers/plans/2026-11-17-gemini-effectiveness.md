# Gemini Decisions: mejora integral

Diseño aprobado en la conversación del 17/11/2026. Base: de25ed4.

## Decisiones y alcance

- Kotlin/coroutines/Compose existentes. Mantener Groq, grabación original, WhatsApp, permisos, confirmaciones sensibles y WorkflowRunner.
- Reglas y aclaraciones locales primero; Decisions para desconocidos recuperables; generateContent únicamente para parámetros, secuencias o respaldo de endpoint.
- Acciones simples cerradas de Decisions: ejecución directa con los cuatro valores de confianza >=0,95. Parámetros/secuencias generativas: vista previa. Respaldo generativo: confirmar incluso consulta a Instinct.
- Sin nuevas capacidades Android, backend, rutinas guardadas, memoria personal permanente ni lectura adicional de datos.

## Componentes

1. CapabilityCatalog: opciones de Decisions, campos y variantes explícitas de esquemas; incluir multimedia, modos, apps y ajustes existentes. PhoneCommandCodec sigue validando comandos completos.
2. CommandDraft/DraftPlan: datos parciales validados, pasos s1..s10, revisiones, preguntas por campo y selección explícita ante correcciones ambiguas. Nunca ejecutar partes de un plan incompleto.
3. InterpretationRequest/Result y GeminiCoordinator: separar consulta, acción simple, plan, borrador, rechazo y fallo; conservar contexto mínimo y no confundir fallos con consultas.
4. GeminiTransport: headers sin claves en URLs, cuerpos limitados, cierre y cancelación; 12 s de presupuesto global, Decisions <=4 s, extracción <=8 s, una llamada a cada endpoint como máximo. Sin redirecciones ni reintentos automáticos.
5. MainViewModel/panel: aplicar resultados solo en la sesión vigente; conservar audio/transcripción al fallar; corregir/invalidate previews antes de ejecutar; respaldo visible y consulta confirmada sin reinterpretación o envío duplicado.
6. Configuración: prueba con frase sintética sin ejecución; diagnóstico agregado en memoria, sin contenido ni credenciales.

## Respaldo y límites

Solo HTTP 404/405/501 de Decisions habilitan generateContent de respaldo. Auth, cuota, red, servidor, JSON inválido, rechazo o baja confianza conservan el pedido. Cancelar/editar/pausar impide aplicar respuestas tardías.

Los adaptadores conservan modelos y endpoints existentes. La documentación oficial y discovery de Google siguen bloqueados por el proxy; disponibilidad y contrato real requieren verificación con una cuenta habilitada. La salida estructurada y los mocks no demuestran precisión semántica: controles locales cubren restricciones reconocidas, la vista previa permite revisar parámetros y el corpus mide casos concretos.

## Verificación

- `python scripts/test-phone-core.py`: compila el núcleo Kotlin real y ejecuta JUnit con dependencias fijadas; descarga a caché del usuario, sin Android ni cambios de firma.
- `./gradlew compileDebugKotlin compileDebugUnitTestKotlin`: compila app y pruebas Android con JDK 21, Gradle 9.3.1 y SDK 36.1.
- `./gradlew testDebugUnitTest`: suite completa, sin omitir fallos ni presentar bloqueos como aprobados.
- Corpus español en recursos de pruebas: comandos claros, paráfrasis, negaciones/restricciones y contexto. Las fixtures de modelos comprueban coordinación, no calidad real del proveedor.
- Evaluación real opcional: `INSTO_GEMINI_LIVE=1 GEMINI_API_KEY=... python scripts/test-phone-core.py com.example.phone.GeminiCorpusTest`; mantener la clave en el entorno local, nunca en documentos/comandos guardados ni chat. Solo interpreta: no ejecuta acciones. Reporta aciertos exactos del parser/híbrido, rechazos, llamadas y latencias p50/p95.
- Comprobar físicamente permisos, handoffs y audio original en el teléfono antes de afirmar verificación del dispositivo.
