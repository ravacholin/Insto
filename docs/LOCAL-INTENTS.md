# Catálogo de intents locales de Insto

El catálogo reúne `LocalIntentRegistry`, `LocalIntentPhrases`, `NativeActionParameters` y `ExtendedLocalIntents`. Las órdenes completas se reconocen localmente con verbos en infinitivo, tuteo o voseo y fórmulas como «¿podés…?» o «quiero que…». Los pedidos que requieren interpretación adicional pueden usar Gemini con revisión. El fallo de Gemini no bloquea las consultas a Instinct.

Podés decir «Modo silenciar», «Cuenta atrás de cinco minutos», «Más volumen», «Siguiente canción» o «Guíame hasta Córdoba en bici». Con «Modo silencio por veinte minutos», Insto programa la restauración del ajuste anterior.

Una capacidad sin integración se reconoce y explica su limitación localmente. No se anuncia como implementada ni se ejecuta una alternativa. `instinct` y `unsupported` son salidas de derivación y no acciones Android.

Total de entradas: 94.

## `timer` · CLOCK

Crear temporizador con duración y etiqueta opcional.

- Equivalentes: poné un timer de diez minutos / temporizador de diez minutos
- Parámetros: seconds (entero 1..2147483647, obligatorio): valor explícito del usuario; label (texto, opcional): valor explícito del usuario
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: AlarmClock.ACTION_SET_TIMER
- Resultado: Solicitud entregada al Reloj
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `alarm` · CLOCK

Crear alarma con hora, período y repetición opcional; no omitir fechas.

- Equivalentes: poné una alarma a las ocho de la mañana / despertame a las ocho de la mañana
- Parámetros: hour (entero 0..23, obligatorio): valor explícito del usuario; minute (entero 0..59, obligatorio): valor explícito del usuario; days (días 1..7, opcional): valor explícito del usuario; date (fecha local hoy/mañana sin repetición, opcional): valor explícito del usuario; label (texto, opcional): valor explícito del usuario
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: AlarmClock.ACTION_SET_ALARM
- Resultado: Solicitud entregada al Reloj; fechas especiales requieren soporte explícito
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `alarms_show` · CLOCK

Consultar alarmas.

- Equivalentes: mostrá mis alarmas / abrí las alarmas
- Parámetros: ninguno
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: AlarmClock.ACTION_SHOW_ALARMS
- Resultado: Consultar alarmas
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `timers_show` · CLOCK

Consultar temporizadores.

- Equivalentes: mostrá los timers / ver temporizadores
- Parámetros: ninguno
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: AlarmClock.ACTION_SHOW_TIMERS
- Resultado: Consultar temporizadores
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `alarm_snooze` · CLOCK

Posponer la alarma que está sonando con la duración predeterminada del Reloj.

- Equivalentes: posponé la alarma / dame unos minutos más con la alarma
- Parámetros: ninguno
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: AlarmClock.ACTION_SNOOZE_ALARM
- Resultado: Solicitud de posposición entregada al Reloj
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `timer_dismiss` · CLOCK

Descartar temporizador mediante la interfaz del Reloj; sin seleccionar otro temporizador.

- Equivalentes: cancelá un temporizador / descartá el timer
- Parámetros: ninguno
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: AlarmClock.ACTION_DISMISS_TIMER
- Resultado: Solicitud entregada al Reloj con interfaz visible
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `stopwatch_start` · CLOCK

iniciar el cronómetro.

- Equivalentes: iniciar el cronómetro / quiero iniciar el cronómetro
- Parámetros: ninguno
- Permisos: accesibilidad si el Reloj no exporta la acción
- Ejecución: NativeStopwatchController y accesibilidad autorizada
- Resultado: iniciar el cronómetro
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `stopwatch_pause` · CLOCK

pausar el cronómetro.

- Equivalentes: pausar el cronómetro / quiero pausar el cronómetro
- Parámetros: ninguno
- Permisos: accesibilidad si el Reloj no exporta la acción
- Ejecución: NativeStopwatchController y accesibilidad autorizada
- Resultado: pausar el cronómetro
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `stopwatch_resume` · CLOCK

continuar el cronómetro.

- Equivalentes: continuar el cronómetro / quiero continuar el cronómetro
- Parámetros: ninguno
- Permisos: accesibilidad si el Reloj no exporta la acción
- Ejecución: NativeStopwatchController y accesibilidad autorizada
- Resultado: continuar el cronómetro
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `stopwatch_reset` · CLOCK

reiniciar el cronómetro.

- Equivalentes: reiniciar el cronómetro / quiero reiniciar el cronómetro
- Parámetros: ninguno
- Permisos: accesibilidad si el Reloj no exporta la acción
- Ejecución: NativeStopwatchController y accesibilidad autorizada
- Resultado: reiniciar el cronómetro
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `stopwatch_show` · CLOCK

mostrar el cronómetro.

- Equivalentes: mostrar el cronómetro / quiero mostrar el cronómetro
- Parámetros: ninguno
- Permisos: accesibilidad si el Reloj no exporta la acción
- Ejecución: NativeStopwatchController y accesibilidad autorizada
- Resultado: mostrar el cronómetro
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `alarm_update` · CLOCK

Modificar una alarma existente.

- Equivalentes: modificar una alarma existente / quiero modificar una alarma existente
- Parámetros: selector (texto, obligatorio): valor explícito del usuario; changes (texto, opcional): valor explícito del usuario
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: Integración específica de Reloj; Android no ofrece CRUD universal
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Reconocí el pedido, pero este Reloj no tiene una integración verificada para esa operación. Podés pedir abrir las alarmas o los temporizadores.

## `timer_update` · CLOCK

Modificar un temporizador existente.

- Equivalentes: modificar un temporizador existente / quiero modificar un temporizador existente
- Parámetros: selector (texto, obligatorio): valor explícito del usuario; changes (texto, opcional): valor explícito del usuario
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: Integración específica de Reloj; Android no ofrece CRUD universal
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Reconocí el pedido, pero este Reloj no tiene una integración verificada para esa operación. Podés pedir abrir las alarmas o los temporizadores.

## `timer_pause` · CLOCK

Pausar un temporizador.

- Equivalentes: pausar un temporizador / quiero pausar un temporizador
- Parámetros: selector (texto, obligatorio): valor explícito del usuario; changes (texto, opcional): valor explícito del usuario
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: Integración específica de Reloj; Android no ofrece CRUD universal
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Reconocí el pedido, pero este Reloj no tiene una integración verificada para esa operación. Podés pedir abrir las alarmas o los temporizadores.

## `timer_resume` · CLOCK

Reanudar un temporizador.

- Equivalentes: reanudar un temporizador / quiero reanudar un temporizador
- Parámetros: selector (texto, obligatorio): valor explícito del usuario; changes (texto, opcional): valor explícito del usuario
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: Integración específica de Reloj; Android no ofrece CRUD universal
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Reconocí el pedido, pero este Reloj no tiene una integración verificada para esa operación. Podés pedir abrir las alarmas o los temporizadores.

## `calendar` · CALENDAR

Crear evento con título, fecha, hora y duración.

- Equivalentes: agendá reunión mañana a las 17:00 / creá reunión mañana a las 17:00
- Parámetros: title (texto, obligatorio): valor explícito del usuario; beginMillis (fecha futura en zona del teléfono, obligatorio): valor explícito del usuario; endMillis (fecha posterior al inicio, opcional): duración predeterminada de una hora si no se indica otra
- Permisos: sin acceso especial adicional
- Ejecución: Intent.ACTION_INSERT CalendarContract.Events
- Resultado: Borrador entregado a Calendario para revisión y guardado
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `calendar_agenda` · CALENDAR

Abrir la agenda actual sin filtros de fecha adicionales.

- Equivalentes: abrí mi agenda / mostrá el calendario
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Intent.ACTION_MAIN/CATEGORY_APP_CALENDAR
- Resultado: Calendario abierto; no se afirma haber leído eventos
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `dial` · CALLS

Preparar llamada a contacto o número en el marcador.

- Equivalentes: llamá a Ana / marcá el número de Ana
- Parámetros: target (texto, obligatorio): valor explícito del usuario
- Permisos: android.permission.READ_CONTACTS para buscar nombres
- Ejecución: Intent.ACTION_DIAL y ContactsContract
- Resultado: Número preparado; el usuario inicia la llamada en Teléfono
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `dialer_open` · CALLS

Abrir marcador sin destinatario.

- Equivalentes: abrí el marcador / abrí el teclado del teléfono
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Intent.ACTION_DIAL
- Resultado: Abrir marcador sin destinatario
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `alarm_dismiss` · CLOCK

Descartar alarma por etiqueta explícita sin elegir otra por defecto.

- Equivalentes: cancelá la alarma llamada Trabajo / descartá la alarma con nombre Trabajo
- Parámetros: label (texto, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: com.android.alarm.permission.SET_ALARM
- Ejecución: AlarmClock.ACTION_DISMISS_ALARM con búsqueda por etiqueta e interfaz visible
- Resultado: Solicitud para la etiqueta exacta entregada al Reloj; revisar allí la selección
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `brightness_set` · ANDROID

Fijar porcentaje del brillo de pantalla.

- Equivalentes: poné el brillo al cuarenta por ciento / ajustá el brillo al 40%
- Parámetros: percent (entero 0..100, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: android.permission.WRITE_SETTINGS (acceso especial)
- Ejecución: Settings.System.SCREEN_BRIGHTNESS
- Resultado: Brillo manual aplicado con acceso especial
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `contact_search` · CONTACTS

Buscar contacto por nombre.

- Equivalentes: buscá el contacto José Pérez / encontrá el contacto José Pérez
- Parámetros: name (texto, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: sin acceso especial adicional
- Ejecución: ContactsContract / Intent.ACTION_SEARCH
- Resultado: Búsqueda entregada a Contactos
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `contact_phones` · CONTACTS

Consultar teléfonos de un contacto.

- Equivalentes: qué teléfono tiene José Pérez / decime el teléfono de José Pérez
- Parámetros: name (texto, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: android.permission.READ_CONTACTS
- Ejecución: ContactsContract.CommonDataKinds.Phone
- Resultado: Solo números realmente encontrados; selección si hay homónimos
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `app_settings` · APPLICATIONS

Abrir configuración de una aplicación.

- Equivalentes: abrí ajustes de Spotify / abrí configuración de Spotify
- Parámetros: app (texto, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: sin acceso especial adicional
- Ejecución: Settings.ACTION_APPLICATION_DETAILS_SETTINGS
- Resultado: Ajustes de la aplicación elegida abiertos
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `message_open` · MESSAGING

Abrir conversación SMS para un número explícito.

- Equivalentes: abrí el chat SMS con +541112345678 / abrí SMS con +541112345678
- Parámetros: number (teléfono 3..15 dígitos, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: sin acceso especial adicional
- Ejecución: Intent.ACTION_SENDTO smsto:
- Resultado: Conversación preparada en app SMS; sin envío automático
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `message_draft` · MESSAGING

Redactar SMS para un número explícito sin enviarlo.

- Equivalentes: redactá SMS a +541112345678 que diga Estoy llegando / prepará SMS para +541112345678 que diga Estoy llegando
- Parámetros: number (teléfono 3..15 dígitos, obligatorio): dato explícito; sin completar valores ausentes; body (texto, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: sin acceso especial adicional
- Ejecución: Intent.ACTION_SENDTO con sms_body
- Resultado: Borrador preparado; el usuario revisa y envía en SMS
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `text_share` · APPLICATIONS

Compartir texto explícito con una aplicación elegida por el usuario.

- Equivalentes: compartí el texto Estoy llegando / compartir texto Estoy llegando
- Parámetros: text (texto, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: sin acceso especial adicional
- Ejecución: Intent.ACTION_SEND text/plain con chooser
- Resultado: Selector de aplicación abierto; contenido entregado, no envío confirmado
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `files_browse` · FILES

Abrir el administrador de archivos sin operar sobre un archivo por defecto.

- Equivalentes: abrí mis archivos / abrí el administrador de archivos
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: DocumentsContract root con Intent.ACTION_VIEW
- Resultado: Administrador de archivos abierto para intervención del usuario
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `notification_list` · NOTIFICATIONS

Consultar notificaciones activas con acceso explícito.

- Equivalentes: mostrá mis notificaciones / qué notificaciones tengo
- Parámetros: ninguno
- Permisos: acceso especial a notificaciones de Insto (separado del acceso multimedia)
- Ejecución: NotificationListenerService.getActiveNotifications
- Resultado: Lista real de notificaciones activas; sin enviar su contenido a Gemini
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `notification_open` · NOTIFICATIONS

Abrir una notificación identificada por título.

- Equivalentes: abrí la notificación Reunión / quiero abrir la notificación Reunión
- Parámetros: title (texto, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: acceso especial a notificaciones de Insto
- Ejecución: NotificationListenerService y PendingIntent original
- Resultado: Se ejecuta el PendingIntent de una notificación inequívoca
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `notification_dismiss` · NOTIFICATIONS

Descartar una notificación identificada por título.

- Equivalentes: descartá la notificación Reunión / quiero descartar la notificación Reunión
- Parámetros: title (texto, obligatorio): dato explícito; sin completar valores ausentes
- Permisos: acceso especial a notificaciones de Insto
- Ejecución: NotificationListenerService y PendingIntent original
- Resultado: Se descarta solamente la notificación elegida si es cancelable
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `app_open` · APPLICATIONS

Abrir una aplicación instalada.

- Equivalentes: abrí Spotify / iniciá Spotify
- Parámetros: name (texto, obligatorio): valor explícito del usuario
- Permisos: sin acceso especial adicional
- Ejecución: PackageManager: launcher exportado
- Resultado: Aplicación abierta o selección de coincidencias
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `youtube` · APPLICATIONS

Buscar contenido en YouTube.

- Equivalentes: buscá jazz en YouTube / buscá en YouTube jazz
- Parámetros: query (texto, obligatorio): valor explícito del usuario
- Permisos: sin acceso especial adicional
- Ejecución: Búsqueda pública de YouTube
- Resultado: Búsqueda abierta, no reproducción garantizada
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `torch_on` · ANDROID

Encender linterna ahora sin duración ni condiciones.

- Equivalentes: encendé la linterna / iluminá con la linterna
- Parámetros: ninguno
- Permisos: android.permission.CAMERA
- Ejecución: CameraManager.setTorchMode
- Resultado: Encender linterna ahora sin duración ni condiciones
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `torch_off` · ANDROID

Apagar linterna ahora.

- Equivalentes: apagá la linterna / dejá de iluminar con la linterna
- Parámetros: ninguno
- Permisos: android.permission.CAMERA
- Ejecución: CameraManager.setTorchMode
- Resultado: Apagar linterna ahora
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `volume_up` · ANDROID

Subir volumen multimedia un paso.

- Equivalentes: subí el volumen / más fuerte el sonido
- Parámetros: ninguno
- Permisos: android.permission.MODIFY_AUDIO_SETTINGS
- Ejecución: AudioManager.adjustStreamVolume
- Resultado: Subir volumen multimedia un paso
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `volume_down` · ANDROID

Bajar volumen multimedia un paso.

- Equivalentes: bajá el volumen / menos fuerte el sonido
- Parámetros: ninguno
- Permisos: android.permission.MODIFY_AUDIO_SETTINGS
- Ejecución: AudioManager.adjustStreamVolume
- Resultado: Bajar volumen multimedia un paso
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `volume_set` · ANDROID

Fijar porcentaje del volumen multimedia.

- Equivalentes: poné el volumen al 40 por ciento / ajustá el volumen al cuarenta por ciento
- Parámetros: percent (entero 0..100, obligatorio): valor explícito del usuario
- Permisos: android.permission.MODIFY_AUDIO_SETTINGS
- Ejecución: AudioManager.setStreamVolume
- Resultado: Fijar porcentaje del volumen multimedia
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `settings_wifi` · ANDROID

Abrir ajustes de wifi, sin prometer cambiar el estado.

- Equivalentes: abrí ajustes de wifi / mostrá configuración de wifi
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Settings activity/panel
- Resultado: Panel abierto para intervención del usuario
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `settings_bluetooth` · ANDROID

Abrir ajustes de bluetooth, sin prometer cambiar el estado.

- Equivalentes: abrí ajustes de bluetooth / mostrá configuración de bluetooth
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Settings activity/panel
- Resultado: Panel abierto para intervención del usuario
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `settings_internet` · ANDROID

Abrir ajustes de internet, sin prometer cambiar el estado.

- Equivalentes: abrí ajustes de internet / mostrá configuración de internet
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Settings activity/panel
- Resultado: Panel abierto para intervención del usuario
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `settings_open` · DEVICE

Abrir ajustes de ajustes, sin prometer cambiar el estado.

- Equivalentes: abrí ajustes de ajustes / mostrá configuración de ajustes
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Settings activity/panel
- Resultado: Panel abierto para intervención del usuario
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `wifi_on` · ANDROID

Activar Wi-Fi.

- Equivalentes: activar wi-fi / quiero activar wi-fi
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Conmutación restringida a sistema/device owner en Android moderno
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Android no permite a esta app cambiar ese estado directamente. No cambié la conectividad. Podés pedir abrir los ajustes correspondientes.

## `wifi_off` · ANDROID

Desactivar Wi-Fi.

- Equivalentes: desactivar wi-fi / quiero desactivar wi-fi
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Conmutación restringida a sistema/device owner en Android moderno
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Android no permite a esta app cambiar ese estado directamente. No cambié la conectividad. Podés pedir abrir los ajustes correspondientes.

## `bluetooth_on` · ANDROID

Activar Bluetooth.

- Equivalentes: activar bluetooth / quiero activar bluetooth
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Conmutación restringida a sistema/device owner en Android moderno
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Android no permite a esta app cambiar ese estado directamente. No cambié la conectividad. Podés pedir abrir los ajustes correspondientes.

## `bluetooth_off` · ANDROID

Desactivar Bluetooth.

- Equivalentes: desactivar bluetooth / quiero desactivar bluetooth
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Conmutación restringida a sistema/device owner en Android moderno
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Android no permite a esta app cambiar ese estado directamente. No cambié la conectividad. Podés pedir abrir los ajustes correspondientes.

## `silent_on` · ANDROID

Silenciar llamadas y notificaciones.

- Equivalentes: silenciar / modo silenciar / poner en modo silencioso / dejá el teléfono en silencio
- Parámetros: ninguno
- Permisos: android.permission.ACCESS_NOTIFICATION_POLICY; android.permission.MODIFY_AUDIO_SETTINGS
- Ejecución: AudioManager.ringerMode
- Resultado: Silenciar llamadas y notificaciones
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `vibrate_on` · ANDROID

Activa el modo vibración con `modo vibrar` o `poner en vibrar`. Usa `AudioManager.ringerMode`. Android puede pedir acceso a No molestar al cambiar de modo.

## `silent_off` · ANDROID

Desactivar silencio de llamadas.

- Equivalentes: desactivar silencio de llamadas / quiero desactivar silencio de llamadas
- Parámetros: ninguno
- Permisos: android.permission.ACCESS_NOTIFICATION_POLICY; android.permission.MODIFY_AUDIO_SETTINGS
- Ejecución: AudioManager / NotificationManager con acceso de política
- Resultado: Desactivar silencio de llamadas
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `dnd_on` · ANDROID

Activar No molestar.

- Equivalentes: activar no molestar / quiero activar no molestar
- Parámetros: ninguno
- Permisos: android.permission.ACCESS_NOTIFICATION_POLICY
- Ejecución: AudioManager / NotificationManager con acceso de política
- Resultado: Activar No molestar
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `dnd_off` · ANDROID

Desactivar No molestar.

- Equivalentes: desactivar no molestar / quiero desactivar no molestar
- Parámetros: ninguno
- Permisos: android.permission.ACCESS_NOTIFICATION_POLICY
- Ejecución: AudioManager / NotificationManager con acceso de política
- Resultado: Desactivar No molestar
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `media_play` · MEDIA

Reanudar reproducción.

- Equivalentes: reanudar reproducción / quiero reanudar reproducción
- Parámetros: ninguno
- Permisos: acceso especial a sesiones multimedia
- Ejecución: MediaSession.TransportControls
- Resultado: Acción o estado de una sesión compatible; elegir si hay varias
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `media_pause` · MEDIA

Pausar reproducción.

- Equivalentes: pausar reproducción / quiero pausar reproducción
- Parámetros: ninguno
- Permisos: acceso especial a sesiones multimedia
- Ejecución: MediaSession.TransportControls
- Resultado: Acción o estado de una sesión compatible; elegir si hay varias
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `media_next` · MEDIA

Siguiente tema.

- Equivalentes: siguiente tema / quiero siguiente tema
- Parámetros: ninguno
- Permisos: acceso especial a sesiones multimedia
- Ejecución: MediaSession.TransportControls
- Resultado: Acción o estado de una sesión compatible; elegir si hay varias
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `media_previous` · MEDIA

Tema anterior.

- Equivalentes: tema anterior / quiero tema anterior
- Parámetros: ninguno
- Permisos: acceso especial a sesiones multimedia
- Ejecución: MediaSession.TransportControls
- Resultado: Acción o estado de una sesión compatible; elegir si hay varias
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `media_status` · MEDIA

Consultar qué está sonando.

- Equivalentes: consultar qué está sonando / quiero consultar qué está sonando
- Parámetros: ninguno
- Permisos: acceso especial a sesiones multimedia
- Ejecución: MediaSession.TransportControls
- Resultado: Acción o estado de una sesión compatible; elegir si hay varias
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `spotify` · MEDIA

Buscar contenido en Spotify.

- Equivalentes: buscar rebord en Spotify / buscá en Spotify jazz
- Parámetros: query (texto, obligatorio): consulta explícita del usuario
- Permisos: sin acceso especial adicional
- Ejecución: ACTION_VIEW https://open.spotify.com/search/ con consulta codificada como segmento de ruta; se prefiere com.spotify.music y se permite la misma URL pública si la app no la resuelve
- Resultado: Resultados de búsqueda abiertos; no se afirma reproducción
- Disponibilidad: adaptador implementado; depende de app, versión y handler disponible

## `music` · MEDIA

Solicitar la reproducción por título en una aplicación compatible.

- Equivalentes: reproducir De música ligera en Spotify / reproducir De música ligera en YouTube
- Parámetros: query (título, obligatorio) y app (Spotify o YouTube, opcional)
- Permisos: acceso a notificaciones si se usa la sesión multimedia
- Ejecución: `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` o `MediaSession`
- Resultado: Se envía el pedido de reproducción. Si la app elegida no admite ese control, se abre su búsqueda.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `media_stop`, `media_pause_spotify`, `media_stop_spotify`, `media_pause_youtube`, `media_stop_youtube` · MEDIA

`Pausar Spotify`, `pausar YouTube`, `parar Spotify` y `parar YouTube` controlan la sesión de la app nombrada. `Parar la música` elige la sesión si solo hay una reproduciendo. Si hay varias, Insto pide elegir una. El control requiere acceso a notificaciones. Cuando la app solo ofrece pausa, `parar` envía la orden de pausa.

## `navigate` · NAVIGATION

Iniciar ruta al destino con modo de viaje admitido.

- Equivalentes: llevame a Córdoba / navegá a Córdoba
- Parámetros: destination (texto, obligatorio): valor explícito del usuario; mode (DRIVING/WALKING/BICYCLING/TRANSIT, opcional): valor explícito del usuario
- Permisos: sin acceso especial adicional
- Ejecución: Maps directions
- Resultado: Ruta entregada a Maps
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `maps` · NAVIGATION

Buscar un lugar en el mapa.

- Equivalentes: buscá en Maps farmacias / buscá farmacias cerca de mí
- Parámetros: query (texto, obligatorio): valor explícito del usuario
- Permisos: sin acceso especial adicional
- Ejecución: Maps search
- Resultado: Buscar un lugar en el mapa
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `place_save` · NAVIGATION

Guardar dirección de casa o trabajo.

- Equivalentes: guardá mi casa en Avenida Colón 123 / mi casa es Avenida Colón 123
- Parámetros: alias (casa/trabajo, obligatorio): valor explícito del usuario; address (texto, obligatorio): valor explícito del usuario
- Permisos: sin acceso especial adicional
- Ejecución: SavedPlacesRepository
- Resultado: Guardar dirección de casa o trabajo
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `camera_photo` · CAMERA_RECORDING

Abrir cámara en modo foto sin disparar.

- Equivalentes: abrí la cámara / abrí la cámara de fotos
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
- Resultado: Abrir cámara en modo foto sin disparar
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `camera_video` · CAMERA_RECORDING

Abrir cámara en modo video sin iniciar grabación.

- Equivalentes: abrí cámara de video / abrí la cámara en modo video
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: MediaStore.INTENT_ACTION_VIDEO_CAMERA
- Resultado: Abrir cámara en modo video sin iniciar grabación
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `recorder_open` · CAMERA_RECORDING

Abrir grabadora para iniciar grabación con intervención del usuario.

- Equivalentes: abrí la grabadora / prepará una grabación de audio
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: MediaStore.Audio.Media.RECORD_SOUND_ACTION
- Resultado: Grabadora abierta; no se afirma grabación iniciada
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `recorder_stop` · CAMERA_RECORDING

Detener una grabación de audio.

- Equivalentes: detener una grabación de audio / quiero detener una grabación de audio
- Parámetros: ninguno
- Permisos: CAMERA/RECORD_AUDIO según captura
- Ejecución: Sesión de captura y URI de salida controlada por Insto
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Reconocí la captura, pero Insto no controla una sesión de grabación para esa acción. No inicié ni detuve ninguna captura. Podés pedir abrir cámara o grabadora.

## `battery` · DEVICE

Consultar porcentaje de batería.

- Equivalentes: cuánta batería queda / decime el nivel de batería
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: BatteryManager.BATTERY_PROPERTY_CAPACITY
- Resultado: Porcentaje real de batería o error si no disponible
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `accessibility_settings` · DEVICE

Abrir ajustes de accesibilidad.

- Equivalentes: abrí accesibilidad / mostrá configuración de accesibilidad
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Settings.ACTION_ACCESSIBILITY_SETTINGS
- Resultado: Abrir ajustes de accesibilidad
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `display_settings` · DEVICE

Abrir ajustes de pantalla.

- Equivalentes: abrí ajustes de pantalla / mostrá configuración de pantalla
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: Settings.ACTION_DISPLAY_SETTINGS
- Resultado: Abrir ajustes de pantalla
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `shortcut_run` · DEVICE

Ejecutar un acceso directo previamente integrado.

- Equivalentes: ejecutá mi acceso directo / activá mi atajo
- Parámetros: shortcutId (texto, obligatorio): valor explícito del usuario
- Permisos: sin acceso especial adicional
- Ejecución: ShortcutManager/launcher autorizado o integración exportada
- Resultado: Se informa la limitación local; no se cambia la acción ni se afirma éxito
- Disponibilidad: Ese acceso directo necesita una integración registrada. No ejecuté otro componente.

## `temporary_mode` · AUTOMATION

Aplicar modo temporal con duración explícita y restauración.

- Equivalentes: silenciá el teléfono durante una hora / poné no molestar durante una hora
- Parámetros: kind (MEDIA_VOLUME/SILENT/DND, obligatorio): valor explícito del usuario; seconds (entero 1..86400, obligatorio): valor explícito del usuario; percent (entero 0..100, opcional): valor explícito del usuario
- Permisos: android.permission.ACCESS_NOTIFICATION_POLICY; android.permission.MODIFY_AUDIO_SETTINGS para volumen/silencio; android.permission.POST_NOTIFICATIONS en Android 13+
- Ejecución: TemporaryModeManager y restauración con propiedad de ajustes
- Resultado: Modo aplicado solo con accesos y programación disponibles
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `temporary_mode_end` · AUTOMATION

Terminar el modo temporal y restaurar ahora.

- Equivalentes: terminá el modo y restaurá ahora / restaurá el volumen
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: TemporaryModeManager.end
- Resultado: Restauración del estado guardado si existe
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `call_direct` · CALLS

Iniciar llamada directa a un número explícito, con permiso y confirmación.

- Equivalentes: llamá directamente al +541112345678 / iniciá una llamada al +541112345678
- Parámetros: number (texto, obligatorio): valor literal o selección explícita en Android
- Permisos: android.permission.CALL_PHONE
- Ejecución: Intent.ACTION_CALL tras confirmación local
- Resultado: Android inicia la llamada solicitada; no se afirma conexión
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `message_send` · MESSAGING

Enviar texto por la integración de WhatsApp a un número explícito, tras revisar destinatario y contenido.

- Equivalentes: mandá por WhatsApp a +541112345678 que diga Estoy llegando / enviá por WhatsApp a +541112345678 que diga Hola José
- Parámetros: number (texto, obligatorio): valor literal o selección explícita en Android; body (texto, obligatorio): valor literal o selección explícita en Android
- Permisos: WhatsApp instalado y accesibilidad conectada/autorizada
- Ejecución: WhatsAppDispatcher con guardas de destinatario y texto existentes
- Resultado: Solicitud de envío entregada a WhatsApp; la automatización verifica destinatario y cuerpo. Sin otro destinatario por defecto.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `file_open` · FILES

Abrir un archivo con selección y permisos de Android.

- Equivalentes: abrir el archivo informe.pdf / abrir un archivo
- Parámetros: name (texto, opcional): valor literal o selección explícita en Android
- Permisos: concesión de URI del Storage Access Framework
- Ejecución: ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE y DocumentsContract
- Resultado: Selección nativa; se verifica nombre si se indicó, destino y confirmación antes de escribir. Eliminar no usa otro archivo por defecto.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `file_search` · FILES

Buscar un archivo con selección y permisos de Android.

- Equivalentes: buscar el archivo informe.pdf / buscar un archivo
- Parámetros: name (texto, opcional): valor literal o selección explícita en Android
- Permisos: concesión de URI del Storage Access Framework
- Ejecución: ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE y DocumentsContract
- Resultado: Selección nativa; se verifica nombre si se indicó, destino y confirmación antes de escribir. Eliminar no usa otro archivo por defecto.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `file_share` · FILES

Compartir un archivo con selección y permisos de Android.

- Equivalentes: compartir el archivo informe.pdf / compartir un archivo
- Parámetros: name (texto, opcional): valor literal o selección explícita en Android
- Permisos: concesión de URI del Storage Access Framework
- Ejecución: ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE y DocumentsContract
- Resultado: Selección nativa; se verifica nombre si se indicó, destino y confirmación antes de escribir. Eliminar no usa otro archivo por defecto.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `file_delete` · FILES

Eliminar un archivo con selección y permisos de Android.

- Equivalentes: eliminar el archivo informe.pdf / eliminar un archivo
- Parámetros: name (texto, opcional): valor literal o selección explícita en Android
- Permisos: concesión de URI del Storage Access Framework
- Ejecución: ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE y DocumentsContract
- Resultado: Selección nativa; se verifica nombre si se indicó, destino y confirmación antes de escribir. Eliminar no usa otro archivo por defecto.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `file_copy` · FILES

Copiar un archivo con selección y permisos de Android.

- Equivalentes: copiar el archivo informe.pdf / copiar un archivo
- Parámetros: name (texto, opcional): valor literal o selección explícita en Android
- Permisos: concesión de URI del Storage Access Framework
- Ejecución: ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE y DocumentsContract
- Resultado: Selección nativa; se verifica nombre si se indicó, destino y confirmación antes de escribir. Eliminar no usa otro archivo por defecto.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `file_move` · FILES

Mover un archivo con selección y permisos de Android.

- Equivalentes: mover el archivo informe.pdf / mover un archivo
- Parámetros: name (texto, opcional): valor literal o selección explícita en Android
- Permisos: concesión de URI del Storage Access Framework
- Ejecución: ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE y DocumentsContract
- Resultado: Selección nativa; se verifica nombre si se indicó, destino y confirmación antes de escribir. Eliminar no usa otro archivo por defecto.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `file_rename` · FILES

Renombrar un archivo con nombres exactos y selección Android.

- Equivalentes: renombrá el archivo "informe.pdf" a "final.pdf" / renombrar archivo "foto.jpg" a "viaje.jpg"
- Parámetros: name (texto, obligatorio): valor literal o selección explícita en Android; newName (texto, obligatorio): valor literal o selección explícita en Android
- Permisos: concesión de URI de escritura
- Ejecución: ACTION_OPEN_DOCUMENT y DocumentsContract.renameDocument
- Resultado: Se verifica el archivo y se confirma el nombre antes de renombrar
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `calendar_query` · CALENDAR

Consultar eventos de un día exacto de la agenda.

- Equivalentes: qué tengo mañana / consultá mi agenda hoy
- Parámetros: day (texto, obligatorio): valor literal o selección explícita en Android
- Permisos: android.permission.READ_CALENDAR
- Ejecución: CalendarContract.Instances con intervalo de día local
- Resultado: Eventos reales del día solicitado, solo con permiso
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `calendar_delete` · CALENDAR

Cancelar un evento guardado con título exacto, selección y confirmación.

- Equivalentes: cancelá el evento "Reunión" / eliminá el evento "Reunión"
- Parámetros: title (texto, obligatorio): valor literal o selección explícita en Android
- Permisos: android.permission.READ_CALENDAR; android.permission.WRITE_CALENDAR
- Ejecución: CalendarContract.Events y selección explícita; recurrencias se revisan en Calendario
- Resultado: Solo se elimina el evento elegido y confirmado; nunca una serie recurrente por deducción
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `calendar_update` · CALENDAR

Modificar un evento elegido, o moverlo a fecha y hora completas.

- Equivalentes: editá el evento "Reunión" / mové el evento "Reunión" a mañana a las 17:00
- Parámetros: title (texto, obligatorio): valor literal o selección explícita en Android; newTime (texto, opcional): valor literal o selección explícita en Android
- Permisos: android.permission.READ_CALENDAR
- Ejecución: CalendarContract.Events para selección e Intent.ACTION_EDIT con cambios tipados
- Resultado: Editor de Calendario con evento exacto; conserva duración si no se indicó otra, requiere guardado en Calendario
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `routine_run` · AUTOMATION

Ejecutar una rutina local ya configurada.

- Equivalentes: ejecutá la rutina Estudio / activá mi rutina Estudio
- Parámetros: name (texto, obligatorio): valor literal o selección explícita en Android
- Permisos: sin acceso especial adicional
- Ejecución: ConfiguredRoutineStore y WorkflowRunner
- Resultado: Se revisan los pasos guardados antes de ejecutar, sin crear pasos a partir del pedido
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `camera_capture` · CAMERA_RECORDING

Capturar una foto mediante la aplicación nativa.

- Equivalentes: capturar una foto / quiero capturar una foto
- Parámetros: ninguno
- Permisos: android.permission.CAMERA
- Ejecución: ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE / RECORD_SOUND_ACTION con resultado y URI autorizada
- Resultado: Se abre la interfaz nativa para capturar; solo se informa archivo guardado cuando la app devuelve un resultado válido. Sin filtros ni ajustes adicionales.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `camera_record` · CAMERA_RECORDING

Capturar un video mediante la aplicación nativa.

- Equivalentes: capturar un video / quiero capturar un video
- Parámetros: ninguno
- Permisos: android.permission.CAMERA
- Ejecución: ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE / RECORD_SOUND_ACTION con resultado y URI autorizada
- Resultado: Se abre la interfaz nativa para capturar; solo se informa archivo guardado cuando la app devuelve un resultado válido. Sin filtros ni ajustes adicionales.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `recorder_start` · CAMERA_RECORDING

Capturar una grabación de audio mediante la aplicación nativa.

- Equivalentes: capturar una grabación de audio / quiero capturar una grabación de audio
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE / RECORD_SOUND_ACTION con resultado y URI autorizada
- Resultado: Se abre la interfaz nativa para capturar; solo se informa archivo guardado cuando la app devuelve un resultado válido. Sin filtros ni ajustes adicionales.
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `routine_list` · AUTOMATION

Listar rutinas locales configuradas.

- Equivalentes: mostrá mis rutinas / qué rutinas tengo
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: ConfiguredRoutineStore con selección SAF e importación confirmada
- Resultado: Listar no ejecuta; importar reemplaza solo después de revisar las plantillas
- Disponibilidad: adaptador implementado; depende de app, versión y permisos

## `routine_import` · AUTOMATION

Importar plantillas de rutinas revisadas desde JSON.

- Equivalentes: importá rutinas / configurá rutinas desde un archivo
- Parámetros: ninguno
- Permisos: sin acceso especial adicional
- Ejecución: ConfiguredRoutineStore con selección SAF e importación confirmada
- Resultado: Listar no ejecuta; importar reemplaza solo después de revisar las plantillas
- Disponibilidad: adaptador implementado; depende de app, versión y permisos
