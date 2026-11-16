# Insto: conversación, workflows y control multimedia

Fecha: 2026-11-16. Estado: aprobado el 2026-11-16, con rutinas guardadas excluidas por el usuario.

## Intención y decisiones del usuario

El usuario eligió el paquete A y las propuestas 1, 2 y 4, y luego excluyó las rutinas guardadas de esta entrega: interpretación estructurada, memoria del pedido, secuencias/rutinas y control multimedia/modos temporales. La propuesta 1 forma parte de A. El objetivo es completar tareas del teléfono con menos repeticiones y menos intervenciones manuales, sin depender de Instinct para ejecutarlas.

El resto de las decisiones de este documento son propuestas del asistente, no preferencias ya confirmadas del usuario. La aprobación pedida es sobre este alcance completo.

## Base comprobada

Referencia remota: main en 7522b1687d23bc93910b678c9df726c53be7d673, merge del PR #11. Los PR #7 a #11 incorporan Groq, acciones nativas, Maps/contactos/Calendario, Gemini Decisions y cronómetro con Accesibilidad. El checkout local está en 25d9a9f; antes de implementar habrá que incorporar la base remota sin perder trabajo local.

PhoneCommand representa una acción individual. EverydayCommandParser rechaza órdenes encadenadas. MainViewModel mantiene una categoría de aclaración y algunos comandos pendientes; no un borrador con campos editables. HybridPhoneRouter clasifica capacidades y pide repetir parámetros que el parser no reconoce. Gemini Decisions no genera esos parámetros. PhoneActionOutcome distingue COMPLETED, HANDOFF, NEEDS_INPUT y REJECTED; HANDOFF cierra el floating y no demuestra que el usuario guardó o completó algo en la otra app.

No se ha comprobado en este trabajo la ejecución física en el Motorola; los PR documentan verificaciones de dispositivo pendientes. El diseño no presupone que abrir una app pruebe un cambio de estado.

## Enfoques considerados

1. Ampliar expresiones regulares y añadir macros rígidas: rápido para frases concretas, pero se vuelve frágil para correcciones, referencias y fallas parciales.
2. Borradores tipados, contexto acotado y motor de secuencias; interpretación semántica opcional con validación local: reutiliza las acciones existentes y permite comprobar cada paso. Es el enfoque recomendado para el paquete elegido.
3. Agente general que opere cualquier pantalla: más flexible, pero dependiente de interfaces cambiantes y con resultados difíciles de comprobar. Queda fuera de esta entrega.

El usuario eligió las capacidades; se propone el enfoque 2 para implementarlas. La aprobación del diseño confirmaría esta elección técnica.

## 1. Conversación e interpretación

Un borrador conserva acción, campos conocidos, campos faltantes, origen de cada dato, alternativas y cambios realizados. Fecha, hora, duración, destino y destinatario son campos independientes. La sesión conserva solo el contexto necesario para el pedido activo y su resultado reciente, no memoria personal indefinida.

Ejemplo: «Agendá reunión con Laura mañana» → pregunta por hora → «a las cinco de la tarde» → borrador completo → «mejor a las seis y media hora». Las correcciones se aplican antes de entregar el borrador a Calendario. El evento sigue requiriendo guardarse en Calendario; modificar eventos ya guardados no está incluido.

Otros ejemplos: «el segundo», «a pie», «usá el celular» y «cancelá ese pedido». Un referente solo se resuelve cuando hay una alternativa inequívoca en el pedido activo. Si no, se pregunta. «Deshacé» solo se ofrece cuando existe una operación inversa comprobable; cancelar un pedido no revierte efectos ya realizados.

Para permitir correcciones de acciones con efectos externos, los planes de varios pasos y los borradores de Calendario presentan un resumen y esperan «dale»/Ejecutar. Las acciones simples ya admitidas, como volumen y linterna, conservan ejecución directa. Una corrección posterior a una entrega externa informa que el borrador original ya se entregó y no crea silenciosamente otro.

El parser local sigue siendo la primera vía. Para redacciones no resueltas, una extracción semántica opcional propone un borrador JSON limitado al catálogo. Se mantiene Decisions para clasificación si resulta útil; no se atribuye a su endpoint la capacidad de extracción. Se usará una API de Gemini con salida estructurada cuyo contrato y modelo disponibles se comprobarán al planificar. Si no hay soporte o credencial válida, se conserva la vía local y la aclaración por campos; no se inventan resultados del proveedor.

La salida del modelo es un candidato: se validan tipos, límites, fechas, zona horaria, referencias y capacidades antes de ejecutar. Nunca produce intents, URLs, componentes Android ni código ejecutable. No se eliminan condiciones no soportadas. Solo se envía el texto y contexto mínimo necesario; no toda la libreta de contactos, notificaciones o contenido de otras apps. La confianza declarada por un modelo no reemplaza la validación.

El contexto interactivo termina al cancelar/cerrar el pedido y no se reutiliza en una sesión ajena. Al pasar a otra app se pausa la escucha; volver permite continuar explícitamente. No se agrega escucha permanente. La interacción inicial será por voz de entrada y texto/botones de salida como hoy; respuestas habladas generales quedan fuera de esta entrega.

## 2. Secuencias y rutinas

Ejemplo: «Bajá el volumen al veinte por ciento, poné un timer de veinticinco minutos y abrí Spotify». Antes de ejecutar se muestran los tres pasos y los permisos/datos faltantes. El límite inicial propuesto es diez pasos por pedido, sin bucles, condiciones ni ejecución paralela.

Cada paso tiene identidad estable y estados pendiente, ejecutando, completado, entregado, esperando intervención, fallido e incierto. Se persiste el progreso antes y después de despachar efectos externos. Tras una interrupción nunca se supone que un paso en ejecución no ocurrió. La recuperación se apoya en evidencia o requiere intervención explícita.

«Reintentá» opera sobre el paso fallido cuando es seguro; no repite los completados. Una entrega incierta no se reintenta automáticamente. Esto es especialmente importante para alarmas y temporizadores, que pueden duplicarse. «Cancelá» impide pasos posteriores y explica lo ya realizado. No existe rollback universal.

Abrir una app receptora se informa como entrega, no como tarea completada. Si un paso requiere revisión en Calendario o intervención en otra app, el workflow queda esperando y se continúa al volver y confirmar, sin deducir éxito del regreso. El motor no cierra toda la secuencia después del primer HANDOFF. En la rutina de ejemplo, abrir Spotify será el último paso.

Las rutinas guardan plantillas, no IDs de ejecución ni autorizaciones temporales. Incluyen crear, listar, ejecutar, renombrar, editar y eliminar. Guardar o reemplazar una rutina requiere revisar el contenido; ejecutarla crea una ejecución nueva. No se capturan acciones casuales como rutina sin pedido explícito.

Rutinas de ejemplo a configurar y revisar, no activadas automáticamente:

- Estudio: volumen multimedia al 20 %, timer de 25 minutos, abrir Spotify.
- Cocinar: timer con nombre y música elegida; solo se promete reproducción si existe una integración compatible.
- Salir de casa: volumen elegido y ruta a un destino guardado.

Los disparadores por Bluetooth, ubicación u horario quedan fuera de esta primera entrega. «Dentro de una hora restaurá el modo» es una tarea diferida específica del apartado siguiente, no un motor genérico de automatizaciones.

## 3. Multimedia y modos temporales

Nuevas capacidades: reproducir/pausar/continuar, siguiente/anterior y consultar la sesión multimedia activa cuando el proveedor exponga información suficiente. Una búsqueda de música conserva el contrato actual: no garantiza reproducción. Una orden de transporte opera sobre una sesión identificada, no sobre una app adivinada.

Se comprobará la vía de sesiones multimedia de Android. El acceso a sesiones de otras apps puede requerir habilitar un NotificationListenerService; la explicación de acceso será específica, y no se leerán ni almacenarán mensajes para este fin. Si hay varias sesiones se ofrece elección por app, y se comprueban sus acciones admitidas. La ausencia de acceso o de una sesión compatible conserva el pedido y ofrece abrir la app.

Modos temporales: volumen multimedia y silencio/No molestar cuando los accesos y la versión de Android lo permitan. Ejemplo: «Silenciá el teléfono durante una hora y después restauralo». Se aclara qué canales afectará; no se interpreta volumen multimedia como silencio de llamadas. El acceso de política de notificaciones y cualquier acceso especial se solicitan solo al necesitar esa función. No se modifican alarmas ni conectividad como consecuencia implícita de un modo.

Cada modo guarda el estado previo, el estado aplicado, el momento de vencimiento y la propiedad de cada ajuste. Al vencer restaura únicamente ajustes que siguen bajo su control: si el usuario cambió el volumen por otra vía, no se sobrescribe esa decisión. Se prohíben modos superpuestos sobre el mismo ajuste en esta versión; se ofrece reemplazar/extender el modo actual conservando su estado original.

Los vencimientos se persisten y se recuperan tras reinicio mediante el mecanismo Android apropiado. La programación exacta se usa solo con soporte/acceso disponible; de lo contrario se informa que la restauración puede demorarse y se ofrece elegir una restauración aproximada o cancelar antes de aplicar el modo. Una tarea vencida se concilia al reiniciar/abrir, sin restaurar sobre cambios ajenos. Un force-stop del sistema puede impedir ejecución hasta que se abra la app; se documenta ese límite. Siempre queda disponible «Terminá el modo y restaurá ahora».

## Arquitectura propuesta y límites

Separar ConversationSession, borradores de comandos, interpretación estructurada, CapabilityRegistry, WorkflowRunner, RoutineRepository y TemporaryModeManager de MainViewModel. Kotlin, coroutines y la UI Compose existentes siguen siendo la base. El constructor elige la persistencia local apropiada, con migraciones y escrituras atómicas; credenciales siguen en Android Keystore y no forman parte de rutinas o historial.

Cada capacidad declara requisitos, campos, efectos, posibilidades de verificar/reintentar y contrato de resultado. Este catálogo alimenta tanto la interpretación como la ejecución. Las restricciones del parser deben evolucionar expresamente para aceptar secuencias admitidas; la IA no las saltea.

Se conserva una sola grabación, la transcripción Groq y el M4A original para consultas dirigidas a Instinct. Un workflow telefónico incompleto o fallido no se convierte en un mensaje a Instinct. Sesiones, revisiones y ejecuciones tienen identidades distintas para descartar respuestas tardías después de editar o cancelar. No se incorporan destinatarios arbitrarios de WhatsApp, lectura de agenda, recordatorios propios, OCR ni control universal de apps.

## Entregas y aceptación

1. Contexto y campos: completar/corregir pedidos, selección contextual y extracción semántica opcional. Se verifica que una corrección conserve los demás datos y que cancelar descarte interpretaciones tardías.
2. Motor de secuencias: resumen, confirmación, resultados parciales, permisos, handoffs, cancelación y recuperación. Se verifica que fallar el tercer paso no repita el timer del segundo, y que un cierre inesperado no autorice duplicados.
3. Rutinas guardadas: gestión por voz/texto y persistencia. Ejecutar dos veces una rutina crea dos ejecuciones explícitas, nunca una reanudación accidental.
4. Multimedia y modos: selección de sesión, acciones compatibles, accesos, vencimientos y restauración respetando cambios manuales. Probar denegación de permisos, reinicio, sesiones múltiples y superposición rechazada.

Las pruebas deben cubrir reglas de conversación y transiciones con ejecutores simulados, integración Android y recorridos físicos en el Motorola. Los resultados simulados o fuentes compiladas se distinguen de la ejecución real. El flujo Groq → acción y las consultas a Instinct conservan cobertura de regresión. El constructor puede decidir organización interna, biblioteca de persistencia, límites técnicos secundarios y diseño visual consistente con el floating actual; cambios del alcance o del comportamiento descrito vuelven a revisión.

## Estado de aprobación

Las capacidades están elegidas. Este diseño y sus límites todavía requieren aprobación del usuario antes de producir código de producto y el plan de implementación.

## Ajuste de alcance aprobado

El usuario confirmó «la número tres de rutinas no me interesa tanto el resto si, totalmente». Se implementan conversación, secuencias y multimedia/modos temporales. Todas las referencias anteriores a RoutineRepository y gestión de rutinas quedan pospuestas. No hay pantalla, persistencia ni comandos de rutinas guardadas en esta entrega. Las secuencias se ejecutan por pedido explícito. Se continúa con ejecución nativa en esta sesión bajo esa autorización.
