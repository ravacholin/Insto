# Insto · frases aprendidas

Una frase aprendida es un atajo: una frase tuya que Insto traduce a un comando
que ya sabe ejecutar. Todo queda en el teléfono. Se administra en
**Configuración → Frases aprendidas**, que también podés abrir diciendo
«mostrame las frases aprendidas».

## Cómo aprende solo

Cuando Insto necesita el modelo para entender un pedido nuevo de un solo paso,
sin borrador ni contexto compartido, te muestra la acción propuesta. Si la
confirmás y Android informa que pudo ejecutarla, guarda la frase. La próxima
vez se resuelve en el teléfono, sin red. Para voz, Groq sigue transcribiendo el
audio y la interpretación posterior se hace en el teléfono.

Se aprenden linterna, volumen, modo silencio o vibración, apertura de cámara o
app, controles multimedia y cronómetro. Para abrir una app se guarda el paquete
que se lanzó. Si ya no se puede abrir, el próximo pedido invalida la entrada.
Las aperturas de cámara y app cuentan cuando Android acepta el lanzamiento. El
cronómetro requiere un resultado confirmado por el ejecutor.

El aprendizaje automático nunca pisa una frase que agregaste vos.

## Frases con un valor y formas parecidas (1.12.0)

Una frase que contiene un valor sirve también con otros valores. Si Insto
aprendió «bajá la música al 30», entonces «bajá la música al 50» pone el volumen
en 50 sin consultar a Gemini. En la lista aparece como «Bajá la música al
{número}».

| Valor | Se muestra como | Para qué acciones |
| --- | --- | --- |
| Porcentaje de volumen | `{número}` | Fijar el volumen (0 a 100). |
| Duración | `{duración}` | Temporizador sin nombre, solo en frases que agregaste vos. |
| Hora | `{hora}` | Alarma sin fecha ni repetición, solo en frases que agregaste vos. |
| App | `{app}` | Abrir una app. El nombre tiene que coincidir con una sola app instalada. |

El valor nuevo se interpreta igual que en el pedido completo, con los mismos
límites. «Al 150», una hora sin «de la mañana» o «de la tarde», un nombre que
coincide con dos apps o un valor con palabras de más no se ejecutan: Insto sigue
el camino de siempre. Las búsquedas, la navegación y las rutinas no se
generalizan, porque su texto es libre. El interruptor «Usar también con otros
valores», en el editor de cada frase, desactiva la generalización sin borrar la
frase.

También se reconocen formas parecidas de la misma frase:

- voseo, tuteo e infinitivo del mismo verbo: «prendé», «prende», «prender»,
  «prendeme»;
- muletillas: «che», «dale», «bueno», «a ver» al principio, y «porfa» o «por
  favor» en cualquier lugar;
- una sola palabra de cinco letras o más con una letra de diferencia, como
  «músca» por «música». Nunca se aplica a verbos, números ni negaciones.

No se mezclan sinónimos: «prendé» y «encendé» siguen siendo frases distintas. Si
dos frases guardadas quedan iguales al compararlas así, o un pedido coincide con
dos acciones distintas, Insto no ejecuta ninguna y sigue el camino de siempre.
Las negaciones y las condiciones nunca coinciden: tampoco «Dale, no, luz del
pasillo no», «Ni se te ocurra…» o «… salvo que…».

Una frase que agregaste vos tiene prioridad sobre una aprendida sola, y una
frase exacta tiene prioridad sobre una con valor. El aprendizaje automático
tampoco guarda una frase que, comparada así, coincide con una tuya.

## Cómo agregar y editar frases

Todo se hace desde **Configuración → Frases aprendidas → Editar frases**, sin
escribir comandos:

1. Tocá **Agregar frase**.
2. En «Cuando diga» escribí la frase, por ejemplo «luz».
3. Tocá **Elegir acción** y elegí de la lista: encender la linterna, subir o
   fijar el volumen, modo silencio, No molestar, pausar la música, abrir una
   app instalada, ejecutar una rutina, poner un temporizador, ir a un lugar y
   más. Si la acción necesita un dato, como el porcentaje o los minutos, te lo
   pide.
4. Tocá **Guardar**. Si la frase no sirve, el diálogo explica por qué.

Para editar, tocá cualquier frase de la lista. Podés cambiar el texto, la
acción o las dos. Las frases que Insto aprendió solo se editan igual.

Quien prefiera escribir la acción puede elegir **Escribir un pedido
(avanzado)** al final de la lista. Ahí va un pedido que Insto ya entiende, como
«prendé la linterna». También se puede enseñar con la voz o el teclado del
asistente:

- «Cuando diga luz, prendé la linterna». Hace falta una pausa o coma entre la
  frase y la acción.
- «Aprendé que modo cine es poné el volumen al 20».

Las frases manuales admiten, además de lo que se aprende solo, temporizadores,
alarmas sin fecha, rutinas configuradas, navegación y búsquedas en el mapa,
búsquedas de música y video, ajustes, No molestar, batería y las listas de
alarmas, rutinas y notificaciones. Para varios pasos, armá una rutina y asociá
la frase a «ejecutá la rutina …». La rutina se muestra para confirmar antes de
ejecutar.

No se pueden guardar:

- llamadas, mensajes, archivos ni cambios de calendario;
- frases que Insto ya entiende, porque el catálogo local tiene prioridad;
- preguntas, negaciones, condiciones («si», «cuando») ni frases de varios pasos;
- órdenes de control como «sí», «dale», «cancelar» o «continuar».

## Cómo corregir

- «Corregí esta frase: abrí YouTube» o «esa frase significa abrí YouTube»
  cambia la acción de la última frase usada o aprendida.
- «Olvidá esta frase» la borra. Sirve también justo después de que Insto
  aprendió algo que no querías.
- En la lista, tocá la frase para cambiar el texto o la acción.

Una frase corregida o editada pasa a figurar como «La agregaste vos».

## Lista, límite y copia

Cada frase muestra la acción, el origen («Aprendida sola» o «La agregaste
vos»), los usos y la fecha del último uso. Con más de seis aparece un buscador.
Se guardan hasta 200 frases. Al llegar al límite se descartan primero las
aprendidas solas que hace más tiempo que no se usan.

El registro queda fuera del respaldo y de la transferencia de datos de
Android. **Exportar** guarda un archivo JSON y **Importar** lo vuelve a cargar
en este u otro teléfono. El archivo se valida completo antes de cambiar nada.
Las frases importadas reemplazan a las que tengan el mismo texto. Para guardar frases, estructuras y el resto de la configuración en un solo archivo cifrado, usá **Configuración → Copia de seguridad y restauración** ([detalles](Insto-1.12.0-backup.md)). Esa copia nunca reemplaza una frase que agregaste vos al fusionar.

Si apagás «Aprender y usar frases», las frases se conservan pero no se usan ni
se aprenden nuevas.

## Verificación de la primera versión

- GitHub Actions aprobó `testDebugUnitTest` completo y `assembleDebug`.
  [Ejecución de CI](https://github.com/ravacholin/Insto/actions/runs/44348071914).
- En este entorno, la suite completa se frenó al descargar
  `org.robolectric:nativeruntime-dist-compat:1.0.18`. El espejo de Maven respondió
  HTTP 403. Las pruebas de Robolectric se compilaron y quedaron sin ejecutar
  localmente.
- Pasaron 59 pruebas JVM con un filtro local y un script de Gradle temporal que
  excluyó solo ese artefacto de Robolectric.

En el teléfono queda probar una confirmación con éxito, cancelación y permisos
rechazados, repetición de texto en modo avión, desinstalación de una app,
reinicio del cronómetro y «olvidá esta frase» por texto y con el micrófono.
