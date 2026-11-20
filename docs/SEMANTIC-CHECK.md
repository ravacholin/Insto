# Verificación semántica final

`SemanticCommandGuard` es la última comprobación entre una interpretación y su ejecución. `PhoneCommandCodec`
ya valida formatos y rangos; esta capa valida el significado: que los comandos propuestos representen el pedido
que la persona dijo o escribió, sin perder ni cambiar datos.

## Dónde corre

`GeminiCoordinator.interpret` aplica `SemanticCommandGuard.review` una sola vez al resultado final, sea cual sea
su origen: gramática local, frase o estructura aprendida, contexto operativo, Gemini Decisions o Groq. Ningún
`Direct` ni `Plan` sale del coordinador sin pasar por ella, así que el `MainViewModel` no necesita validar otra
vez: los planes confirmados ejecutan exactamente los comandos que ya se verificaron al proponerlos.

No hace llamadas a modelos ni a la red. Cuesta unos 28 µs de mediana y 64 µs en el p95 por pedido
(`SemanticGuardCorpusTest.guardCost`, JVM local).

Las continuaciones de un borrador (`request.draft != null`) no se revalidan: el texto es la respuesta a una
pregunta («diez», «de la tarde») y el borrador conserva los datos ya reconocidos.

## Qué comprueba

Solo datos explícitos. Lo que no está en el texto no se exige.

| Dato del pedido | Debe estar representado por |
| --- | --- |
| Texto entre comillas | El contenido, la consulta, el título o la etiqueta, sin cambios |
| Cantidad de pasos («abrí Spotify y bajá el volumen») | Al menos un comando por paso |
| Duración o plazo («durante veinte minutos», «en diez minutos») | Temporizador, modo temporal, duración del evento o el texto del mensaje |
| Porcentaje («al 30 %», «treinta por ciento», «al 40» con volumen o brillo) | `Volume(SET)`, modo temporal de volumen o brillo |
| Hora («a las 7:30», «hasta las 23») | Alarma, fin del modo temporal o comienzo del evento |
| Día («mañana», «pasado mañana», «hoy») | Fecha de la alarma, del fin del modo o del evento |
| Repetición («todos los lunes») | Días de la alarma |
| Dirección («apagá», «bajá», «desactivá») | La misma dirección en linterna, volumen y No molestar |
| Objeto de una búsqueda, mensaje, destino o app | Palabras que figuran en el pedido |

Además, una acción del teléfono no puede salir de un pedido cuyo verbo principal es buscar, mostrar, mandar,
escribir, decir o contar: en «Buscá videos sobre cómo apagar la linterna» la linterna es el tema.

Las condiciones, negaciones, preguntas, citas e hipótesis se rechazan antes, en `RequestSafetyPolicy`, en el
mismo punto de entrada; el guard no las vuelve a evaluar.

### Detalles prescindibles

No bloquean: cortesía («por favor», «che»), interjecciones, diminutivos («un poquito»), etiquetas de
temporizador sin comillas y palabras genéricas que un proveedor agrega a una consulta sin cambiar su objeto
(«farmacias por acá» → «farmacias cerca»; lista `fillers`).

### Frases aprendidas y contexto

Una frase aprendida es un alias de la persona: su acción no tiene por qué aparecer en el texto, así que no se
exige que la consulta o el destino figuren en el pedido. Sí se exigen los datos explícitos: «luz del pasillo
durante diez minutos» no puede ejecutar solo la linterna. La enseñanza (`PhraseTeaching.checkPhrase`) aplica la
misma regla y explica qué dato no conserva la acción elegida. Lo mismo vale para el contexto operativo.

## Resultado

`SemanticCommandGuard.Verdict` es tipado:

- `Accepted`: el resultado sigue igual.
- `NeedsClarification(prompt, context)`: el pedido se entendió, pero falta un dato que el usuario no dijo. Hoy
  ocurre con una hora ambigua («mañana a las ocho») cuando alguien eligió el período por su cuenta. Se usa la
  misma pregunta y el mismo contexto que la gramática local, incluido `intent:alarm` cuando hay fecha, para que
  la respuesta conserve el día.
- `Rejected(reason)`: la interpretación perdió o cambió un dato. El pedido y el audio se conservan para
  corregirlo; nunca se deriva a Instinct.

## Defectos corregidos en el camino

- «Por favor, prendé la linterna» se rechazaba: la coma separaba «Por favor» como un paso propio.
  `ConversationSession.splitSteps` ya no corta después de cortesías o interjecciones iniciales.
- «Abrime Spotify y después bajale el volumen» abría una app llamada «spotify y despues bajale el volumen»: los
  verbos con clítico («bajale», «subile») no separaban pasos ni se reconocían. Ahora separan y se canonizan.

## Medición

Ver [metrics/semantic-and-voice.md](metrics/semantic-and-voice.md).
