# Acciones del teléfono desde el floating

Estado: propuesta para aprobación. No implementado.

## Intención y decisiones del usuario

El floating debe resolver pedidos normales de un asistente Android, especialmente alarmas, temporizadores y cronómetro, sin depender de Instinct. El usuario eligió distinguir automáticamente las acciones del teléfono de las consultas para Instinct. No hace falta recibir comandos desde WhatsApp.

## Enfoques

Se ofrecieron detección automática y un selector Teléfono/Instinct. El usuario eligió detección automática para evitar un paso extra. La propuesta técnica es un intérprete local de acciones explícitas en español, con capacidades Android concretas; no requiere otro modelo ni servidor. Las consultas generales mantienen la integración existente con Instinct. El reconocimiento de voz usa el servicio de Android disponible en el dispositivo.

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

Propuesta de voz: el micrófono principal pasa a dictado con SpeechRecognizer, preferentemente reconocimiento en el dispositivo si está disponible. Si solo hay un servicio de reconocimiento remoto, indicar esa circunstancia antes de activarlo por primera vez; el servicio del sistema puede requerir conexión. Si no hay reconocedor, ofrecer texto y nota de voz, sin mandar una grabación fallida automáticamente.

Las consultas dictadas que vayan a Instinct se envían como texto reconocido. Esta es una decisión propuesta, no una preferencia ya confirmada del usuario. Conservar un control explícito de nota de voz para grabar y enviar audio mediante la captura AAC actual. Ese modo y los adjuntos se dirigen explícitamente a Instinct, sin interpretar el contenido como órdenes locales. No ejecutar SpeechRecognizer y la captura AAC al mismo tiempo. Mantener los disparadores explícitos heredados de grabación.

## Integración y presentación

Mantener Kotlin, Compose, estilo oscuro y accesos existentes. Quitar la exigencia de configurar un contacto antes de abrir el floating: solo pedirlo al intentar despachar a Instinct. Las acciones locales deben funcionar sin WhatsApp instalado, sin contacto y sin Accesibilidad activada.

El floating muestra el texto reconocido, aclaraciones y resultado. Un cronómetro muestra tiempo y controles en el panel; no mantener el panel abierto como requisito para medir. Mantener el botón de adjuntos y añadir acceso explícito a nota de voz. El aviso de Accesibilidad corresponde a envíos de WhatsApp y no debe presentarse como requisito para usar el teléfono.

Separar intérprete puro, catálogo de capacidades, ejecutor Android y estado conversacional del ViewModel. Los eventos de ejecución se consumen una vez, incluso al rotar. SpeechRecognizer se maneja en Main y se cancela al cerrar o interrumpir. No reanudar escucha de fondo ni activar micrófono permanente.

## Ejecución, permisos y resultados

Resolver intents y capturar errores de lanzamiento. Añadir SET_ALARM y consultas de paquetes pertinentes; permiso CAMERA en tiempo de ejecución solo cuando sea necesario para la linterna. Respetar restricciones del bloqueo de pantalla: pedir desbloqueo cuando corresponda, sin saltarlo. No añadir servicios de lectura de notificaciones ni ampliar Accesibilidad para ejecutar acciones locales.

Para alarmas y timers usar los extras públicos admitidos por el Reloj, incluyendo petición de omitir UI cuando proceda. El lanzamiento no prueba que se haya creado la alarma: mostrar «Solicitud enviada al Reloj» salvo que exista confirmación verificable. Si no hay aplicación compatible o faltan permisos, mostrar una explicación y conservar el pedido. Las acciones propias pueden confirmar éxito tras completar la operación.

Persistir estado del cronómetro con tiempo acumulado y referencia SystemClock.elapsedRealtime. Debe sobrevivir al cierre del panel y muerte del proceso sin servicio de fondo. Detectar reinicio del dispositivo usando identidad de arranque disponible: conservar tiempo acumulado conocido y dejar pausado, sin calcular duraciones con referencias de otro arranque. No necesita alarmas exactas, notificaciones ni wake lock porque no emite un aviso al vencer.

## Verificación y límites

Pruebas de interpretación en español: duraciones, números escritos y dígitos, horarios, días, aclaraciones, negaciones, consultas explicativas y entradas no soportadas. Verificar enrutamiento, no repetición de acciones, resultados de voz parciales/finales, contacto ausente, y tiempo del cronómetro con reloj inyectado, restauración y reinicio.

Pruebas de integración para intents/extras, errores, permisos y conservación del envío de WhatsApp de texto, audio y adjuntos. Ejecutar las pruebas viables y ensamblar APK; registrar bloqueos reales sin presentar compilación como validación del dispositivo. Reloj, reconocimiento de voz, linterna, permisos, bloqueo de pantalla y envío real requieren comprobación en el Android del usuario.

## Decisiones del implementador

El implementador elige clases, gramática inicial de sinónimos, presentación exacta de controles y almacenamiento de estado dentro de las restricciones anteriores. No incorporar un modelo remoto, nuevos canales de WhatsApp ni capacidades fuera del alcance para resolver casos desconocidos.
