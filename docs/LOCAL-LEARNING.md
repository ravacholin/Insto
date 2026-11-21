# Estructuras aprendidas y contexto operativo breve

Dos mejoras de la interpretación local. Su objetivo es resolver más órdenes en el teléfono, sin Gemini Decisions ni Groq, y entender pedidos consecutivos. No agregan modelos, librerías, servidores ni llamadas de red.

## Orden de interpretación

`MainViewModel.routeFloatingRequest` → `GeminiCoordinator.interpret` → `PhoneActionExecutor`.

1. **Validación y seguridad.** `RequestSafetyPolicy.rejection` rechaza citas, hipótesis, negaciones y condiciones antes de cualquier otra etapa. La clave de frases aprendidas (`LearnedPhraseStore.key`) también descarta preguntas, órdenes compuestas, borradores y texto compartido.
2. **Cancelaciones, confirmaciones y borradores.** Se resuelven antes de clasificar el pedido, igual que hasta ahora: «cancelar», «dale», elegir una opción, continuar una secuencia, aclarar una hora o completar un borrador. Estas respuestas no cuentan como turnos del contexto.
3. **Reconocimiento local explícito** (`PhoneCommandParser`). Una orden explícita bien parseada se ejecuta tal cual. La única excepción es un pedido de música que no nombró aplicación: el contexto puede completarla. Una aplicación nombrada siempre prevalece.
4. **Frases aprendidas**, manuales o automáticas. Primero la frase exacta. Desde 1.12.0 también la misma frase en forma canónica (voseo, tuteo, infinitivo y muletillas) o con una letra de diferencia, y después las frases con un valor nuevo («bajá la música al {número}»): primero las manuales y después las automáticas. Ver [frases aprendidas](releases/Insto-frases-aprendidas.md).
5. **Estructuras aprendidas** con un parámetro tipado. Si no coincide la forma exacta, se compara también en forma canónica.
6. **Contexto operativo**, solo para referencias claramente definidas.
7. **Gemini Decisions, extracción y respaldo de Groq.** No cambian y nunca reciben el contexto.
8. **Ejecución Android** con los permisos, confirmaciones y reintentos de siempre.

Las etapas 4 a 6 solo se consultan si el parser no resolvió el pedido ni pidió una aclaración, y si no es una capacidad rechazada definitivamente. Las etapas 5 y 6 requieren además un turno nuevo, de un solo paso y sin texto compartido. El orden propuesto se mantiene. La única diferencia es que el paso 2 vive en el `ViewModel` y en `ConversationSession`, antes de llegar al coordinador, como ya ocurría.

## Estructuras aprendidas

**Ejemplo.** Insto necesita a Gemini para entender «Tirame una alarma para las 8», «… para las 9 de la noche» y «… para las 7:30». Se ejecutan bien. Desde entonces, «Tirame una alarma para las 6:45» se resuelve en el teléfono como «tirame una alarma para las {hora}».

### Cómo se aprende

- Solo aprende de interpretaciones de un modelo que se ejecutaron correctamente. Valen las propuestas que confirmaste y las que Gemini ejecutó directamente. No aprende de comandos locales, frases aprendidas, estructuras ni del contexto.
- Una alarma o un temporizador cuentan cuando se entregan al Reloj. El volumen tiene que aplicarse (`COMPLETED`). No cuentan los pedidos rechazados, inciertos, pendientes de confirmación ni los que necesitan otro dato.
- Busca en el texto un único tramo que el parser existente decodifique **exactamente** al valor ejecutado. Para eso arma un pedido canónico que ya entiende: «poner una alarma a las …», «poner un temporizador de …» o «poner el volumen al …». Si Gemini propuso un valor que no está en el texto, no aprende nada.
- Una hora sin período, como «a las 8», sirve como ejemplo de estructura si una de sus lecturas coincide con la alarma ejecutada.
- La estructura se activa con **tres valores distintos** (`PhrasePatterns.MIN_EXAMPLES`). Repetir el mismo valor no cuenta como diversidad.
- La coincidencia de intención no alcanza. Las tres frases deben compartir el mismo texto literal antes y después del parámetro.

### Límites

- Solo hay tres tipos de parámetro, cada uno con validación determinista: hora (`ClockTime`), duración (`CommandDuration`) y porcentaje de volumen (0–100).
- Solo se aprenden alarmas sin días, fecha ni etiqueta, temporizadores sin etiqueta y volumen fijo. Mensajes, llamadas, archivos, calendario y demás acciones sensibles nunca se generalizan.
- El texto literal necesita al menos dos palabras y una de contenido. No puede tener números, fechas, días, repeticiones, etiquetas ni unidades. Así se descartan «a las {hora}» o «tirame 2 alarmas para las {hora}».
- No se generan expresiones regulares. Una estructura es prefijo literal + parámetro + sufijo literal, anclada en ambos extremos.
- El parámetro debe tener exactamente la forma de su tipo. «… las 8 llamada gimnasio», «… las 8 todos los días», «… las 8 mañana» y «avisame en 10 minutos que salga» no coinciden y siguen el circuito normal.
- Una hora ambigua no se adivina. Insto hace la pregunta habitual («¿De la mañana o de la tarde?») y la respuesta completa la alarma, como en una orden explícita.
- Si dos estructuras dan lecturas distintas para el mismo pedido, por ejemplo «dejalo en 8» como hora y como porcentaje, Insto no ejecuta nada y pasa al circuito normal.
- Las frases manuales y automáticas tienen prioridad, porque se consultan antes. Una frase manual nunca se reemplaza.

### Almacenamiento y rendimiento

- Se guarda en el mismo DataStore `learned_phrases`, con la clave nueva `patterns`: `{"version":1,"patterns":[…]}`. La lista de frases (`entries`) no cambia. Un almacenamiento anterior queda intacto y se lee sin estructuras.
- Al leer, se ignoran las versiones desconocidas, las acciones no admitidas y los elementos dañados. Cada estructura se vuelve a validar, así que un archivo editado a mano no puede introducir una estructura más amplia.
- Admite hasta 60 estructuras. Al llegar al límite se descartan primero las que todavía no tienen tres ejemplos.
- Las estructuras se decodifican y se indexan por primera palabra una sola vez por actualización. La caché se prepara al escribir, en el hilo de almacenamiento. Un pedido solo compara con las estructuras que empiezan igual.
- «Aprender y usar frases» apagado también desactiva las estructuras.

### Configuración

**IA → Frases aprendidas → Estructuras aprendidas** muestra cada estructura activa con su acción, sus ejemplos y sus usos. Cada una tiene un interruptor para desactivarla sin perder los ejemplos y un botón **Borrar**. Una estructura desactivada no vuelve a activarse sola. **Borrar todas** borra también las estructuras. Las estructuras no se incluyen en la copia exportada de frases.

## Contexto operativo breve

**Ejemplo.** «Abrí Spotify» → Spotify se abre y el panel se cierra. «Ahora poné Soda Stereo» → reproduce en Spotify, sin Gemini. «Bajá un poco el volumen» → ejecuta la orden local y no modifica el contexto.

### Qué guarda

`OperationalContext` vive en memoria y nunca se persiste. Guarda solo dos datos:

- la aplicación multimedia comprobada: Spotify o YouTube;
- la última aplicación que Insto abrió con un paquete resuelto (paquete y nombre).

No guarda transcripciones, conversaciones, contactos, mensajes, archivos ni eventos.

Solo un resultado comprobado establece identidad:

- `OpenApp` con `launchedAppPackage`, es decir, con el paquete resuelto y lanzado;
- búsquedas o reproducción con aplicación explícita entregadas;
- controles multimedia de una app indicada.

Una apertura rechazada, con opciones o sin paquete no establece nada. Un `HANDOFF` prueba qué app se abrió, no que algo esté sonando. Abrir otra aplicación que no es multimedia descarta la app multimedia anterior.

### Qué resuelve

- «(Ahora/Dale/Y ahora) poné | reproducí <título>» → `MusicSearch(título, app del contexto)`. Se excluyen ajustes y dispositivos (volumen, alarma, linterna, tele…), pedidos vagos («algo», «eso»), números y otras plataformas nombradas.
- Una orden explícita de música sin aplicación («Poné música de Los Redondos») recibe la app del contexto. Una app nombrada («… en YouTube») prevalece.
- «Volvé a abrirla», «Abrila de nuevo» → abre la última app comprobada.
- «Ahora, bajá el volumen» → el marcador discursivo se quita y el resto pasa por el parser. No necesita contexto. «Ahora,» separado por coma no cuenta como otro paso.
- Una pausa o un «siguiente» sin app se ejecutan como siempre. Si Android ofrecería elegir entre varias sesiones, se usa la app del contexto. Si esa app no tiene sesión, se sigue preguntando.

### Cuándo termina

- A los **120 s** de la última referencia comprobada o tras **tres turnos pertinentes**, lo que ocurra primero. Los turnos pertinentes son pedidos nuevos. No cuentan los de volumen ni los controles multimedia. El reloj es inyectable.
- Al decir «cancelar» o cerrar el asistente manualmente.

El panel también se cierra solo después de cada entrega a otra app. Ese cierre no termina el contexto, porque de lo contrario el ejemplo de Spotify sería imposible. El contexto dura lo que dura el proceso y no pasa a otra sesión.

### Qué no hace

- No crea referencias genéricas a contactos, mensajes, archivos o eventos. «Llamalo», «Borralo» y «Mandale un mensaje» siguen el circuito normal.
- No completa datos de acciones sensibles.
- No agrega confirmaciones ni saltea las que exigen Android o Insto.
- No se agrega al texto que reciben Gemini o Groq.

## Órdenes de Instinct: no se aprenden

Las [órdenes de Instinct](INSTINCT-PROPUESTAS.md) (líneas `#insto <orden>`, desde 1.13.0) quedan fuera de las dos mejoras:

- **No se interpretan con lo aprendido.** Solo pasan por la gramática explícita (`PhoneCommandParser`). No consultan frases aprendidas, estructuras ni el contexto operativo.
- **No enseñan nada.** Ejecutar o confirmar una orden de Instinct no crea ni refuerza frases en `LearnedPhraseStore`, no registra un uso y no aporta ejemplos a `PhrasePatterns`, aunque la acción salga bien. El texto lo escribió Instinct, no la persona.
- **No son turnos de `OperationalContext`.** No cuentan como turno pertinente ni establecen la app o el medio de referencia para la próxima orden. (Al ejecutar un control multimedia sin app indicada, el ejecutor sigue leyendo el contexto vigente para elegir la sesión, como con cualquier pedido.)

## Mediciones

Ver [métricas de aprendizaje local](metrics/local-learning.md).
