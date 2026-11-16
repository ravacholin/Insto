# Acciones nativas útiles

El usuario autorizó aplicar las funciones más útiles de la propuesta, después de instalar el APK 1.4.4. Se elige un conjunto nativo que funciona sin privilegios ADB: navegación/búsquedas en Maps, casa/trabajo guardados por pedido explícito, marcador con número o contacto, borrador de evento en Calendario, cámara de foto/video, búsqueda de YouTube y búsqueda/reproducción de música en una app compatible.

## Enfoque elegido

Se amplían el intérprete local y el ejecutor existentes. Las consultas generales conservan el audio original para Instinct. Los pedidos nativos incompletos/no disponibles permanecen en el floating. Se consideraron APIs directas para escribir el calendario/llamar y automatización de pantallas; se eligen los intents públicos y borradores nativos porque requieren menos permisos y permiten revisar destinatarios y horarios en su app.

Los textos libres (direcciones, títulos, búsquedas y nombres) conservan acentos, mayúsculas y signos; solo se normaliza la sintaxis del pedido. Los parámetros se codifican para no transformarse en otros parámetros de URL. Negaciones, explicaciones y pedidos con detalles no interpretables no deben ejecutar acciones parciales.

Maps admite destinos explícitos, modos auto/a pie/bicicleta/transporte público y búsquedas cercanas; usar URLs oficiales con api=1 y destino/query codificado. «Casa» y «trabajo» requieren una dirección guardada explícitamente, no asumir el historial de Google. «Guardá mi casa en …» guarda solo en almacenamiento privado local. No se solicita ubicación porque Maps gestiona su propia ubicación de origen.

«Llamá a …» usa ACTION_DIAL, nunca ACTION_CALL. Si se acota una búsqueda demasiado extensa, nunca elegir implícitamente un contacto a partir de datos incompletos. Un número explícito se valida y normaliza sin aceptar USSD ni extensiones silenciosamente ignoradas. Los nombres requieren READ_CONTACTS solo al pedirlos. Coincidencias duplicadas o aproximadas requieren elegir nombre y número; nunca elegir arbitrariamente. La lectura de contactos no sale del teléfono. Denegar permiso conserva el pedido. No ampliar la automatización de WhatsApp.

Calendario usa ACTION_INSERT con título, inicio y fin. Soportar hoy, mañana, pasado mañana y dd/MM[/yyyy], hora de 24 h o palabras/AM-PM explícitas, duración opcional; por defecto una hora, revisable en el borrador. Validar fecha estrictamente y rechazar horarios pasados, ambiguos, fechas inválidas o calificadores no soportados. El reloj y zona se inyectan en pruebas. No pedir permisos de calendario ni afirmar que el evento quedó guardado.

Cámara abre su modo foto/video: no promete disparar ni grabar sin intervención. YouTube abre búsqueda, no promete reproducción. Música usa MEDIA_PLAY_FROM_SEARCH con QUERY y requiere una app receptora; no añadir acceso a notificaciones. Las entregas exitosas cierran el floating mediante el observador de ciclo de vida existente; permisos, elecciones, errores y aclaraciones lo mantienen abierto.

Se añaden ejemplos accesibles desde «Ayuda» en el floating. Se conserva una sola captura de audio, la cancelación de resultados tardíos y todos los comandos previos. Sin rutinas arbitrarias o No molestar en esta entrega: todavía requieren definir sus efectos y permisos. No agregar dependencias.

## Verificación y entrega

Pruebas JVM puras para reconocimiento, texto intacto, parámetros/contratos de intents, fechas con zona, alias y selección de contactos. Pruebas Robolectric para intents entregados, ausencia de apps/permisos, retención, voz y elecciones sin WhatsApp; compilar aunque el runtime siga bloqueado por HTTP403. Intentar suite completa, compilar APK y verificar firma/alineación. Usar la misma clave de 1.4.4 conservada en el entorno. Entregar PR borrador y APK en Drive según la preferencia establecida; no fusionar automáticamente. Validación física pendiente si no hay teléfono conectado.
