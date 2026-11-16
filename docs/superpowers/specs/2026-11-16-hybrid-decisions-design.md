# Insto: enrutamiento híbrido con Gemini Decisions

Estado: aprobado por el usuario el 2026-11-16. Fecha: 2026-11-16.

## Intención y alcance

El usuario eligió comenzar con el híbrido recomendado: resolver pedidos claros
con las reglas locales y consultar Gemini Decisions cuando esas reglas no
alcancen. La primera entrega es una ampliación acotada del enrutamiento, con
clave personal y sin backend. Conserva Groq para transcribir una sola grabación
y entrega a Instinct el M4A original cuando corresponde.

La implementación debe partir del main que incluye los PR #8 y #9
(e00b8ac3b774630b6a740350f19d32e171b917aa, al revisar). El checkout actual
está en #7; actualizar la base sin perder cambios es una tarea previa.

## Alternativas consideradas

- Híbrido, elegido por el usuario: mantiene la rapidez local y agrega comprensión
  semántica en entradas no resueltas. Requiere distinguir desconocido de consulta.
- Decisions en todos los pedidos: clasificación uniforme, pero agrega una
  dependencia de red a cada solicitud y no extrae parámetros arbitrarios.
- Gemini generativo con JSON: permite extraer parámetros, pero amplía el alcance
  y añade generación y validación de campos. Se deja para una entrega posterior.

## Comportamiento propuesto

1. Cancelaciones, confirmaciones, opciones de contactos/apps y aclaraciones
   pendientes se resuelven con el estado local antes de consultar un modelo.
2. Local y Clarify del parser conservan su comportamiento. Los rechazos explícitos
   por condiciones, operaciones no admitidas o múltiples acciones no se eluden.
3. Separar el resultado desconocido del reconocimiento explícito de una consulta
   o de una solicitud dirigida a Instinct. Los desconocidos y los rechazos por
   redacción no comprendida consultan Decisions si está configurado y habilitado.
4. Una llamada clasifica destino/capacidad y comprueba negación, condiciones y
   múltiples acciones usando el texto original, el estado pendiente pertinente y
   el catálogo de capacidades. No enviar libreta de contactos, audio o adjuntos.
5. Consulta general reconocida con suficiente confianza: usar el despacho
   existente a Instinct, conservando audio original, destinatario y guardas.
6. Acción local: aceptar únicamente comandos tipados de un catálogo cerrado.
   Para acciones sin parámetros libres (por ejemplo, encender/apagar linterna),
   Decisions puede seleccionar un candidato completo. Para hora, duración,
   destino, número o título, exigir extracción determinista completa; si falla,
   pedir una reformulación específica. Nunca inventar parámetros ni quitar
   condiciones para conseguir que el parser acepte una orden.
7. Baja confianza, resultados contradictorios o acción no admitida: conservar el
   pedido y pedir aclaración o explicar la limitación. Error de red, cuota,
   credenciales o esquema inválido: conservar y ofrecer reintentar; no despachar.

Esto mejora el destino y recupera algunas acciones expresadas de otras maneras;
no promete entender cualquier alarma o evento que hoy el parser no soporte.

## Configuración y decisiones del implementador

Clave Gemini ingresada en el dispositivo, cifrada con Android Keystore y fuera
de backups, separada de Groq. Activar el híbrido al guardar la clave, con opción
de desactivarlo/eliminarla. Sin clave o desactivado se conserva el modo actual.
Explicar qué texto se comparte con Google y las condiciones del nivel gratuito.
No incluir claves en Git, APK, logs ni URL.

REST HTTPS POST /v1beta/decisions con modelo gemini-decision-1 y header
x-goog-api-key. Reutilizar las herramientas de red existentes sin dependencias
nuevas; no seguir redirecciones ni reintentar automáticamente. Propuesta del
implementador: timeout total de 10 s y respuesta máxima de 64 KiB.

Umbral inicial propuesto: probabilidad de la opción elegida y confianza >= 0,95;
rechazar valores no finitos, categorías desconocidas y respuestas inconsistentes.
Es un umbral provisional que exige evaluación con español real, no una garantía
de precisión. Cerrar, cambiar el pedido o pasar a segundo plano cancela trabajo;
una generación obsoleta nunca ejecuta ni envía. Reintentar reutiliza transcripción
y grabación. Mantener permisos, confirmaciones y distinción HANDOFF/COMPLETED.

## Verificación y límites

Pruebas de política y contrato HTTP con respuestas simuladas: comandos claros
sin llamadas; desconocidos semánticos; consultas frente a órdenes; negación,
condiciones y múltiples acciones; parámetros no extraíbles; baja confianza;
401/403/429, timeout, JSON inválido y cancelación; reintentos sin duplicar acciones
ni perder el M4A. Compilar APK y ejecutar las pruebas disponibles, declarando los
bloqueos de Robolectric ya documentados si persisten.

Comparar el parser y el híbrido sobre frases españolas etiquetadas y reportar
aciertos, rechazos y latencia. Sin una clave configurada no se puede afirmar
validación con el servicio real; sin teléfono no se verifica la entrega Android.
No modificar ni fusionar los PR #5/#6, no ampliar capacidades nativas y no
reemplazar Groq o automatización WhatsApp en esta entrega.

## Referencias

- https://aistudio.google.com/docs/decisions-api
- https://aistudio.google.com/docs/models/gemini-decision-1
- https://aistudio.google.com/docs/pricing#gemini-decision
