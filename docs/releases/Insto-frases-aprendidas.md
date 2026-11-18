# Insto · frases aprendidas

En IA → Frases aprendidas podés ver qué aprendió Insto, borrar una frase o todas
y apagar el aprendizaje. Está activado de entrada. Si lo apagás, las frases
guardadas quedan disponibles para administrar desde IA y se dejan de usar al
interpretar pedidos. Decí o escribí «olvidá esta frase» para borrar la última
coincidencia que usaste.

El aprendizaje se aplica a pedidos nuevos de un solo paso, sin borrador ni
contexto compartido. Insto aprende cuando propone una acción admitida con el
modelo, la confirmás y Android informa que pudo ejecutarla. Para abrir una app
se guarda el paquete que se lanzó. Si ya no se puede abrir, el próximo pedido
invalida la entrada. Las aperturas de cámara y app cuentan cuando Android
acepta el lanzamiento. El cronómetro requiere un resultado confirmado por el
ejecutor para aprender.

La coincidencia es exacta después de normalizar. El parser local tiene
prioridad y el comando aprendido pasa por el mismo ejecutor, con los permisos
y confirmaciones de siempre. Los pedidos de texto aprendidos se interpretan sin
red. Para voz, Groq sigue transcribiendo el audio y la interpretación posterior
se hace en el teléfono.

El registro queda en el teléfono, fuera del respaldo y de la transferencia de
datos. Se guardan hasta 200 frases, con prioridad para las usadas más
recientemente.

## Verificación

`testDebugUnitTest` completo quedó bloqueado al descargar
`org.robolectric:nativeruntime-dist-compat:1.0.18`. El espejo de Maven respondió
HTTP 403.

En el teléfono queda probar una confirmación con éxito, cancelación y permisos
rechazados, repetición de texto en modo avión, desinstalación de una app,
reinicio del cronómetro y «olvidá esta frase» por texto y con el micrófono.
