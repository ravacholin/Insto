# Callar alarma con la voz (1.25.0)

Función opcional, **apagada por defecto**. Cuando suena una alarma o termina un temporizador de la app Reloj,
Insto abre una pantalla sobre el bloqueo, escucha unos segundos y, si decís una de tus palabras, apaga la alarma
con su botón «Detener». Con el switch apagado Insto se comporta exactamente igual que en 1.24.0.

Se activa en **Configuración → Callar alarma con la voz**. Teléfono de referencia: Moto g con Android 16.

## Privacidad

- No se graba, guarda ni envía audio. `AudioRecord` lee cuadros de 100 ms a memoria, Vosk los procesa en el
  teléfono y se descartan.
- El reconocimiento es 100 % on-device (Vosk). La única conexión es la descarga del modelo, una vez, desde
  `alphacephei.com`.
- De las notificaciones solo se mira primero el **paquete**. Si no es un reloj conocido, no se toca nada más.
  De las del Reloj se leen la **categoría**, los **títulos de los botones**, el id de canal y dos marcas
  estructurales (si tiene *full-screen intent* y si es insistente). Nunca el título ni el texto de la notificación.
- El registro local (`noBackupFilesDir/alarm-voice-log.txt`, 60 líneas) guarda solo eventos: detectada, estrategia
  de lanzamiento, escuchó, cómo terminó (voz / tiempo agotado / manual). No va a Instinct ni a WhatsApp.
- Los ajustes viven en `noBackupFilesDir`: una restauración en otro teléfono no activa la función sola.

## Flujo

```
Reloj publica la notificación
  └─ NotificationAccessService.onNotificationPosted
       ├─ rama nueva (solo si «Callar alarma con la voz» está activa) → AlarmVoiceCoordinator.onPosted
       │     ├─ paquete en ClockPackages.KNOWN o com.motorola.*clock*/*alarm*
       │     ├─ ¿suena? full-screen intent, insistente o tiene «Posponer»
       │     ├─ category == alarm, o parece un temporizador cumplido (canal «timer», o «Detener» + «+1 min»)
       │     ├─ guarda en memoria sbn.key + PendingIntent de «Detener» (nunca «Posponer»)
       │     ├─ precarga el modelo de Vosk en un hilo
       │     └─ lanza AlarmVoiceStopActivity (estrategias abajo)
       └─ chequeo de InstinctProposalSettings y su return temprano, sin cambios
AlarmVoiceStopActivity (setShowWhenLocked / setTurnScreenOn)
  └─ onResume → AlarmVoiceStopService (foregroundServiceType="microphone")
       ├─ baja STREAM_ALARM al % configurado (guarda el original en un archivo)
       ├─ AudioRecord 16 kHz mono, VOICE_RECOGNITION, AcousticEchoCanceler + NoiseSuppressor si existen
       ├─ Vosk con gramática = tus palabras (+ su forma sin tilde) + "[unk]"
       ├─ coincidencia: palabra completa, sin tildes; final con confianza ≥ 0,55, o dos parciales seguidos iguales
       ├─ al reconocer: PendingIntent.send() de «Detener»; si no hay, falla o la alarma sigue a los 2,5 s:
       │     AlarmClock.ACTION_DISMISS_ALARM (ALARM_SEARCH_MODE_NEXT) o ACTION_DISMISS_TIMER
       ├─ vibración corta (EyesFreeFeedbackManager.playSentCue sin sonido)
       └─ finally: restaura el volumen, libera micrófono y modelo, stopForeground, stopSelf; la pantalla se cierra
onNotificationRemoved con la misma key (la apagaste a mano) → corta la escucha
```

La ventana de escucha es de 60 s por defecto (15–120 s). Al vencer, deja de escuchar y **no toca la alarma**.

En la pantalla: estado «Escuchando…», segundos restantes, las palabras válidas, un botón grande **Detener** (apaga
igual que la voz) y **Dejar de escuchar** (corta sin apagar). La notificación en curso del servicio también tiene
«Dejar de escuchar».

## Estrategias para pasar a primer plano

Android no deja abrir el micrófono desde segundo plano, así que primero tiene que verse una activity. En orden:

1. **Insto es el asistente digital predeterminado** (`RoleManager.ROLE_ASSISTANT`, `PingVoiceInteractionService`).
   El sistema exime al asistente de las restricciones de inicio en segundo plano, así que Insto intenta
   `startActivity` directo. `startActivity` no avisa si el sistema lo bloqueó: la pantalla, al verse, se lo
   informa al coordinador; si a los 1,5 s no lo hizo, se registra «El sistema no dejó abrir la pantalla
   directamente» y se pasa a la estrategia 2.
2. **Notificación con full-screen intent** en el canal «Insto · callar alarma con la voz» (importancia alta, sin
   sonido). En Android 14+ se comprueba `NotificationManager.canUseFullScreenIntent()`; si falta, Ajustes ofrece
   abrir `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`.
3. **Notificación normal con la acción «Escuchar»**: tocarla cuenta como interacción y abre la pantalla.

El registro dice qué estrategia abrió la pantalla.

## Permisos y requisitos

| Requisito | Para qué | Dónde se resuelve |
| --- | --- | --- |
| Acceso a notificaciones («Insto · notificaciones») | ver que suena el Reloj | botón en Ajustes |
| Micrófono (`RECORD_AUDIO`) | escuchar | diálogo del sistema |
| Modelo descargado (~40 MB, 57 MB descomprimido) | reconocer sin conexión | botón «Descargar modelo» |
| Asistente predeterminado **o** pantalla completa (`USE_FULL_SCREEN_INTENT`) | abrir la pantalla sola | botones en Ajustes |
| Notificaciones de Insto (`POST_NOTIFICATIONS`) | estrategias 2 y 3 | diálogo del sistema |
| Batería «Sin restricciones» | Motorola mata servicios con facilidad | Info de la app → Batería |

Manifest: `FOREGROUND_SERVICE_MICROPHONE`, `USE_FULL_SCREEN_INTENT`, la activity y el servicio. El servicio se
inicia **solo** desde la activity visible (`onResume`), con `FOREGROUND_SERVICE_TYPE_MICROPHONE`.

## Modelo de voz

`vosk-model-small-es-0.42` desde `https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip`. No va en el
APK. Se descarga al activar la función, con progreso visible, a `cacheDir`; se verifica el tamaño
(39 817 833 bytes) y el SHA-256 (`09b23988…c6511f`, en `AlarmVoiceModel`) antes de descomprimirlo en
`filesDir/vosk/`. Sin modelo la función no se puede activar. «Borrar el modelo» la desactiva.

El modelo chico no tiene «pará» en su vocabulario, pero sí «para»: por eso la gramática lleva cada palabra tal
cual y sin tilde (Vosk ignora la forma que no conoce) y la comparación es sin tildes. «basta», «listo» y «ok» están.
No se usa el VAD de WebRTC: con una gramática de cinco palabras Vosk es barato, y cortar silencios puede comerse el
principio de la palabra.

## Volumen de la alarma

«Bajar el volumen de la alarma mientras escucho» está activa por defecto: baja `STREAM_ALARM` al 40 % (20–60 %) del
volumen que tenía. El original se guarda en `noBackupFilesDir/alarm-voice-volume-original` **antes** de cambiar
nada y se restaura en el `finally` de la escucha, al cerrar la pantalla, al destruir el servicio y, si el proceso
murió, en el próximo arranque de Insto (`InstoApplication.onCreate`) o del listener (`onListenerConnected`).

## Limitaciones conocidas

- **Eco**: el micrófono oye la alarma. El cancelador de eco de Android no siempre actúa sobre `STREAM_ALARM`; bajar el
  volumen ayuda mucho. Con el teléfono lejos o la alarma muy fuerte puede no reconocer: hablá cerca y claro.
- **Reloj de terceros**: solo se reconocen los paquetes de `ClockPackages.KNOWN` (más `com.motorola.*` con «clock» o
  «alarm»). Otros relojes se ven en el modo diagnóstico como «reloj no reconocido»; hay que agregar su paquete.
  Si su botón de apagar no se llama «Detener», «Descartar», «Parar», «Stop» o «Dismiss», se usa el intent del
  sistema, que no todas las apps implementan.
- Si la pantalla de alarma del Reloj está adelante con el teléfono desbloqueado, el full-screen intent de Insto se
  muestra como notificación flotante: tocala.
- La alarma pospuesta vuelve a sonar como una alarma nueva y se ofrece escuchar de nuevo.
- Si cambiás el volumen de alarma a mano mientras Insto escucha, al terminar vuelve al valor anterior.
- Vosk 0.3.47 trae `libvosk.so` alineada a 4 KB: anda en teléfonos con páginas de 4 KB (los Moto g actuales); en
  equipos con páginas de 16 KB dependería del modo de compatibilidad de Android. Suma unos 9 MB por ABI al APK.

## Diagnóstico

En Ajustes → Callar alarma con la voz → Diagnóstico se ve la **última notificación de reloj**: paquete, categoría,
canal, títulos de los botones, si tiene pantalla completa, si es insistente, la hora y el resultado («alarma sonando
· acción «Descartar»», «no es una alarma sonando», «reloj no reconocido»). El **Modo diagnóstico** registra además
apps con nombre de reloj que todavía no están en la lista. Debajo, el registro local de eventos.

Para ver qué publica el Reloj del Moto con una alarma sonando:

```sh
adb shell dumpsys notification --noredact | grep -A40 "pkg=com.google.android.deskclock"
```

Buscá `category=alarm`, `fullScreenIntent=`, `flags=` (insistente es `0x4`), `channel=` y la lista `actions={…}` con
los títulos. Si el paquete del reloj es otro, agregalo a `ClockPackages.KNOWN` en
`app/src/main/java/com/example/alarmvoice/AlarmVoiceLogic.kt`. Para ver si el sistema bloqueó el lanzamiento directo:

```sh
adb logcat | grep -iE "Background activity (start|launch)|AlarmVoice"
```

## Pruebas

`app/src/test/java/com/example/alarmvoice/AlarmVoiceLogicTest.kt` (JVM puro, también en `scripts/test-phone-core.py`):
detección de la notificación de alarma y de temporizador (y que no detecte la de «próxima alarma», un temporizador
en marcha ni otras apps), elección de «Detener» sin elegir nunca «Posponer», y coincidencia de palabras completas.

Código: `app/src/main/java/com/example/alarmvoice/` y `ui/screens/AlarmVoiceSettings.kt`.
