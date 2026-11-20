# Captura de voz y rectificaciones

## Captura

Sigue siendo `AudioRecord` (16 kHz, mono) → WebRTC VAD → AAC en M4A → Groq Whisper. No hay transcripción local,
modelos nuevos ni dependencias nuevas. Cambian el modo del VAD, quién aplica la histéresis y los tiempos de
`VoiceSessionPolicy`.

### Qué hacía 1.12.0 y por qué fallaba

- `VadWebRTC(..., Mode.VERY_AGGRESSIVE, speechDurationMs = 60, silenceDurationMs = 0)`. El suavizado de
  android-vad 2.0.10 (leído del bytecode de `isContinuousSpeech`) da «voz» solo tras más de 3 cuadros vocales
  seguidos, y cualquier cuadro no vocal lo reinicia. Con voz baja o ruido, las decisiones crudas parpadean y la
  salida suavizada queda en «silencio» aunque la persona esté hablando.
- Un único cuadro de voz suavizado empezaba la grabación: un golpe o un clic generaba un audio que se enviaba a
  Whisper.
- 900 ms de silencio final en tramos de menos de 4 s, medidos desde el último cuadro de voz suavizado.
- 300 ms de pre-roll.

### Cómo se midió

No hay teléfono en este entorno. Se usó el mismo algoritmo de WebRTC VAD (`py-webrtcvad 2.0.14`) sobre habla
real en español: dos lecturas de dominio público de LibriVox (Quiroga y d'Aulnoy), unos 20 minutos. Con
`scripts/voice/build-vad-traces.py` se armaron 186 sesiones de captura: silencio inicial, una orden breve
(0,5–1,8 s), larga (4–9 s) o con una vacilación de 600, 900 o 1200 ms en el medio, y silencio final; en cuatro
condiciones: tranquila, voz baja (−20 dB), voz baja con ruido y ruido. Además, solo silencio y ruidos breves
(clic, golpe, dos golpes, pitido de 200 ms, ráfaga de 250 ms). Cada sesión pasa por un VAD nuevo, como en la app.
`app/src/test/resources/vad-traces-es.json` guarda solo las decisiones por cuadro (sin audio) y
`VoiceCaptureTraceTest` ejecuta `VoiceSessionPolicy` sobre ellas y compara con la política anterior.

Datos medidos que fijan los valores:

| Medición | Valor |
| --- | --- |
| Voz detectada con voz baja y ruido, modo 3 / modo 2 | 20–23 % / 59–73 % |
| Frases con un hueco interno ≥ 900 ms en esa condición, modo 3 / modo 2 | 56–72 % / 3–39 % |
| Hueco máximo dentro de una orden breve o larga (modo 2, crudo) | 860 ms |
| Una pausa real de 600 ms medida por el VAD | 660–1040 ms |
| Una pausa real de 900 ms medida por el VAD | 940–1260 ms |
| Retardo del primer cuadro de voz respecto del comienzo real (modo 2) | p99 397 ms, máx. 460 ms |
| Voz total de clics y un golpe | 160–220 ms en total, rachas ≤ 120 ms |
| Voz total de dos golpes, pitidos y ráfagas | 280–420 ms (igual que una palabra corta) |

### Política nueva

`FloatingVoiceCapture` pide al VAD decisiones crudas (`speechDurationMs = 0`, `silenceDurationMs = 0`) en modo
`AGGRESSIVE`, y `VoiceSessionPolicy` aplica la histéresis:

| Constante | Valor | Por qué |
| --- | ---: | --- |
| `ONSET_VOICED_MS` | 160 ms | Supera clics y golpes sueltos; no descarta ninguna orden del corpus |
| `ONSET_GAP_MS` | 300 ms | Un candidato que se calla antes de confirmarse era ruido |
| `PREROLL_MS` | 500 ms | Cubre el retardo máximo medido (460 ms) |
| `SHORT_TRAILING_MS` (< 4 s) | 1100 ms | Cubre los huecos internos (≤ 860 ms) y vacilaciones de 600 ms (≤ 1040 ms) |
| `MEDIUM_TRAILING_MS` / `LONG_TRAILING_MS` | 1600 / 2400 ms | Sin cambios |
| `HESITATION_MS` + `PAUSE_MARGIN_MS`, tope `MAX_ADAPTIVE_MS` | 600 + 400 ms, 2000 ms | Si la persona ya pausó y siguió, la siguiente pausa espera esa duración más un margen |

Los cuadros del candidato y el pre-roll se guardan en memoria hasta confirmar; si no se confirma, no se crea
ningún audio. Si la persona toca enviar, o el micrófono falla, mientras el candidato se confirma, ese habla se
escribe en lugar de descartarse. Los errores del micrófono (lectura fallida, Android que silencia la app, límite
de 5 minutos) conservan el audio ya grabado, como antes.

`FloatingVoiceCapture` recibe la entrada, el detector y el codificador por constructor interno solo para probar
el ciclo de vida en la JVM (`FloatingVoiceCaptureTest`): cancelación, segunda sesión, errores, envío manual y
limpieza de archivos. La app usa siempre `AudioRecord`, WebRTC VAD y AAC.

### Resultado (simulado, 140 sesiones con voz, 30 con ruido breve, 16 de silencio)

| Métrica | 1.12.0 | Ahora |
| --- | ---: | ---: |
| Envíos prematuros (cortan la orden) | 71 | 30 |
| … órdenes breves / largas / con pausa de 600 ms | 9 / 10 / 12 | 0 / 0 / 0 |
| … con pausa de 900 ms / 1200 ms | 20 / 20 | 10 / 20 |
| Inicio recortado (el pre-roll no alcanza) | 10 | 0 |
| Grabaciones por ruidos breves | 30 / 30 | 18 / 30 |
| … clics y golpes sueltos | 12 / 12 | 0 / 12 |
| Envíos en sesiones de silencio | 5 / 16 | 0 / 16 |
| Fin de una orden breve tras la voz, p50 / p95 | 720 / 800 ms | 940 / 1020 ms |
| Fin de una orden larga tras la voz, p50 / p95 | 1440 / 1520 ms | 1440 / 1540 ms |

El costo es de unos 220 ms más de espera al final de una orden breve. A cambio, ninguna orden breve, larga o con
una vacilación de 600 ms se corta, en ninguna de las cuatro condiciones.

### Límites conocidos

- Todo lo anterior es simulado: habla leída (no órdenes espontáneas), un VAD de escritorio del mismo algoritmo y
  ruido sintético. No se midió en un teléfono, con su micrófono, su control automático de ganancia ni
  `VOICE_RECOGNITION`. Antes de fijar los valores hay que repetir la medición con `scripts/measure-voice-latency.py`
  y personas reales, en silencio y con ruido.
- Una vacilación de 900 ms o más en una orden breve, antes de cualquier otra pausa, todavía se puede cortar: con
  silencio solo no se distingue del final. El botón LISTO sigue disponible.
- Pitidos, ráfagas y dos golpes seguidos duran lo mismo que una palabra corta («sí», «pausá») y siguen generando
  una grabación que llega a Whisper.

## Negaciones y rectificaciones

`RequestSafetyPolicy.rejection` no cambia: cualquier pedido que empieza con «no», «nunca», «ni», «jamás» o
«tampoco», o que tiene una condición, se rechaza. Antes de aplicarla, el `MainViewModel` reconoce dos casos
conversacionales:

- **Rectificación** (`RequestSafetyPolicy.rectification`): un «no» aislado, seguido de puntuación («No,», «No.»,
  «No…»), y después una orden afirmativa completa cuyo verbo no es una forma de prohibición («prendé», no
  «prendas») y que pasa por sí sola las reglas de seguridad. «No, prendé la linterna» ejecuta «prendé la
  linterna» desde el principio del circuito, con sus confirmaciones y la verificación semántica final. «No
  prendas…», «No quiero que…», «No, no prendas…» y «No prendé…» (sin puntuación) no son rectificaciones.
  Si había algo pendiente de confirmar, se cancela antes.
- **Rectificación anafórica** («No, mejor ponelo al 30»): se resuelve solo si lo pendiente (el plan a confirmar,
  su borrador o la aclaración de volumen) tiene un único nivel ajustable: volumen, volumen temporal o brillo. Si
  no, se pide la orden completa. No se usan acciones ya ejecutadas como referencia.
- **Cancelación** (`RequestSafetyPolicy.cancelsPending`): «No lo hagas», «No envíes ese mensaje», «Mejor no»,
  «No, cancelá». Cancelan lo pendiente sin ejecutar ni enviar; si no hay nada pendiente, no se ejecuta nada y se
  avisa.

«No molestes» sigue siendo una negación (nunca activa No molestar) y «Activá No molestar» funciona igual.

Corpus de 32 frases (`ConversationalNegationTest.negationMetrics`): antes 0 falsos positivos y 8 falsos negativos
(rectificaciones legítimas rechazadas); ahora 0 y 0. Es un corpus escrito para esta tarea, no habla real.
