# Acciones del teléfono desde el floating

Estado: flujo aprobado por el usuario e implementado en la rama de trabajo; compilación y pruebas JVM verificadas, validación Android/Groq real pendiente. El usuario eligió uso personal y propuso Groq Whisper; se adopta Groq como reconocedor remoto sin backend propio.

## Intención y decisiones del usuario

El floating debe resolver pedidos normales de un asistente Android, especialmente alarmas, temporizadores y cronómetro, sin depender de Instinct. El usuario eligió distinguir automáticamente las acciones del teléfono de las consultas para Instinct. Aclaró que debe haber un único micrófono y una sola grabación: el audio se interpreta para decidir el destino y, cuando corresponde a Instinct, se envía el audio original, no su transcripción. No debe haber selector, botón de nota de voz aparte ni pasos adicionales para elegir destino. No hace falta recibir comandos desde WhatsApp.

## Enfoques

Se ofrecieron detección automática y un selector Teléfono/Instinct. El usuario eligió detección automática para evitar un paso extra. La propuesta técnica es un intérprete local de acciones explícitas en español, con capacidades Android concretas; no requiere otro modelo ni servidor. Las consultas generales mantienen la integración existente con Instinct. La interpretación de voz debe consumir el mismo audio que ya captura la aplicación, sin abrir otro micrófono. La selección técnica del reconocedor requiere comprobar compatibilidad con audio externo y español antes de implementarla.

## Alcance inicial propuesto

- Crear alarmas por hora, etiqueta y días de repetición cuando se indiquen; abrir las alarmas existentes. Delegar al Reloj mediante intents públicos. Android no permite expresar cualquier fecha futura arbitraria en SET_ALARM: solicitudes de fechas que no puedan representarse fielmente deben explicarse y abrir el Reloj para completarlas, nunca convertirse silenciosamente en otra alarma.
- Crear temporizadores de duración positiva en segundos, minutos y horas, con etiqueta opcional; abrir la lista de temporizadores. Se usa el Reloj instalado. Pausar o cancelar un temporizador específico no se promete sin una API fiable: se abre la pantalla correspondiente.
- Cronómetro propio con iniciar, consultar, pausar, continuar y reiniciar. Un cronómetro activo por aplicación. Reiniciar uno activo lo deja en cero y detenido, y pide confirmación para descartar el tiempo actual.
- Abrir aplicaciones visibles y lanzables, desambiguando nombres duplicados.
- Subir, bajar y fijar volumen multimedia; encender y apagar linterna donde exista hardware y permiso.
- Abrir ajustes de Wi-Fi, Bluetooth y conectividad cuando Android impida cambiarlos directamente.

No incluye automatización arbitraria de pantallas, compras, envío a otros contactos, borrado de datos, llamadas, mensajes SMS ni un catálogo completo equivalente al asistente de Google. Son ampliaciones futuras, no comportamientos implícitos.

## Entrada y enrutamiento

Texto y voz reconocida pasan por el mismo intérprete. La salida es una acción válida, un pedido local que necesita aclaración, una acción local no disponible o una consulta para Instinct. No clasificar por palabras sueltas: «explicame cómo funciona un cronómetro» va a Instinct; «iniciá el cronómetro» se ejecuta localmente. Un pedido local incompleto o no soportado permanece en el floating y no se envía como consulta general.

Las aclaraciones guardan la intención pendiente: «poné una alarma» → «¿A qué hora?» → «a las siete de la mañana». Cancelar, cerrar el panel o un nuevo pedido completo descarta esa intención. Las interpretaciones ambiguas de hora requieren aclaración; una hora pasada sin fecha explícita se propone para la próxima ocurrencia y se muestra antes de despacharla. Ejecutar solamente resultados finales de voz y proteger contra doble envío.

Flujo de voz obligatorio: conservar la captura AudioRecord de 16 kHz mono y el archivo AAC/M4A existente. Compartir las muestras PCM de esa misma sesión con un reconocedor que acepte audio externo, o transcribir una copia tras finalizar. No arrancar otro capturador de micrófono, no pedir repetir el mensaje y no sustituir el audio original por texto para enviarlo a Instinct. Mantener el preroll, la finalización por silencio y el envío manual anticipado; estos ahora finalizan captura y disparan interpretación antes de cualquier despacho.

El intérprete usa la transcripción internamente. Si es una orden local válida, la ejecuta y no manda audio a WhatsApp; si es una consulta general, usa el archivo M4A original y el envío de audio existente. Si necesita aclaración, la muestra y escucha la respuesta por el mismo micrófono al activarlo. Los adjuntos siguen el envío existente a Instinct.

Una falla de transcripción o clasificación no demuestra que el pedido sea para Instinct: conservar la grabación y mostrar el problema, sin ejecutarla ni despacharla automáticamente. Los reintentos de interpretación reutilizan el audio guardado. Solo en esta situación excepcional puede ofrecerse una salida manual; el flujo normal nunca requiere elegir destino.

Reconocimiento elegido: Groq, endpoint HTTPS /openai/v1/audio/transcriptions, modelo whisper-large-v3-turbo, language=es y respuesta JSON. Enviar una copia del archivo M4A existente como multipart; preservar el original para Instinct. No requiere captura PCM adicional ni motor local. Tiene dependencia de internet y cuotas del proveedor; no asumir gratuidad ilimitada ni prometer límites no verificados.

Uso personal: pantalla de configuración para cargar, reemplazar y borrar la clave una vez. Cifrarla con AES/GCM usando Android Keystore, almacenamiento privado excluido de copias de seguridad, sin BuildConfig, logs, repositorio ni textos de errores remotos. Informar en esa configuración que la voz se envía a Groq antes de decidir destino. Límites de tamaño, tiempos de espera y cancelación explícitos; sin reintentos automáticos de acciones o envíos. Si no hay clave, conservar audio y abrir configuración sin perderlo.

## Integración y presentación

Mantener Kotlin, Compose, estilo oscuro y accesos existentes. Quitar la exigencia de configurar un contacto antes de abrir el floating: solo pedirlo al intentar despachar a Instinct. Las acciones locales deben funcionar sin WhatsApp instalado, sin contacto y sin Accesibilidad activada.

El floating muestra el texto reconocido, aclaraciones y resultado. Un cronómetro muestra tiempo y controles en el panel; no mantener el panel abierto como requisito para medir. Mantener el botón de adjuntos y un único control de micrófono para todos los pedidos; no añadir un modo separado de nota de voz. El aviso de Accesibilidad corresponde a envíos de WhatsApp y no debe presentarse como requisito para usar el teléfono.

Separar intérprete puro, catálogo de capacidades, ejecutor Android y estado conversacional del ViewModel. Los eventos de ejecución se consumen una vez, incluso al rotar. El reconocedor recibe muestras fuera del hilo principal, respeta la propiedad de cada sesión y se cancela al cerrar o interrumpir. Las colas de audio deben estar limitadas y no bloquear captura ni codificación. No reanudar escucha de fondo ni activar micrófono permanente.

## Ejecución, permisos y resultados

Resolver intents y capturar errores de lanzamiento. Añadir SET_ALARM y consultas de paquetes pertinentes; permiso CAMERA en tiempo de ejecución solo cuando sea necesario para la linterna. Respetar restricciones del bloqueo de pantalla: pedir desbloqueo cuando corresponda, sin saltarlo. No añadir servicios de lectura de notificaciones ni ampliar Accesibilidad para ejecutar acciones locales.

Para alarmas y timers usar los extras públicos admitidos por el Reloj, incluyendo petición de omitir UI cuando proceda. El lanzamiento no prueba que se haya creado la alarma: mostrar «Solicitud enviada al Reloj» salvo que exista confirmación verificable. Si no hay aplicación compatible o faltan permisos, mostrar una explicación y conservar el pedido. Las acciones propias pueden confirmar éxito tras completar la operación.

Persistir estado del cronómetro con tiempo acumulado y referencia SystemClock.elapsedRealtime. Debe sobrevivir al cierre del panel y muerte del proceso sin servicio de fondo. Detectar reinicio del dispositivo usando identidad de arranque disponible: conservar tiempo acumulado conocido y dejar pausado, sin calcular duraciones con referencias de otro arranque. No necesita alarmas exactas, notificaciones ni wake lock porque no emite un aviso al vencer.

## Verificación y límites

Pruebas de interpretación en español: duraciones, números escritos y dígitos, horarios, días, aclaraciones, negaciones, consultas explicativas y entradas no soportadas. Verificar enrutamiento, no repetición de acciones, resultados de voz parciales/finales, integridad del M4A original enviado a Instinct, una sola captura de micrófono, fallas de reconocimiento sin despacho, contacto ausente, y tiempo del cronómetro con reloj inyectado, restauración y reinicio.

Pruebas de integración para intents/extras, errores, permisos y conservación del envío de WhatsApp de texto, audio y adjuntos. Ejecutar las pruebas viables y ensamblar APK; registrar bloqueos reales sin presentar compilación como validación del dispositivo. Reloj, reconocimiento de voz, linterna, permisos, bloqueo de pantalla y envío real requieren comprobación en el Android del usuario.

## Decisiones del implementador

El implementador elige clases, gramática inicial de sinónimos, presentación exacta de controles y almacenamiento de estado dentro de las restricciones anteriores. No incorporar un segundo modelo de interpretación, nuevos canales de WhatsApp ni capacidades fuera del alcance para resolver casos desconocidos.
