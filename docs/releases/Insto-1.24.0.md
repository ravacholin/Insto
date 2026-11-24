# Insto 1.24.0

## Planificación desde Instinct

Con el buzón firmado y la bitácora configurados, copiá las instrucciones actualizadas desde **Ajustes → Propuestas de Instinct**. Instinct consulta `action=catalogo` y `action=estado`, decide qué pasos necesita el objetivo y manda una única orden `#insto plan-json`. Insto valida y ejecuta el plan en el teléfono. El formato anterior `#insto plan: …` sigue disponible.

El ejemplo de la reunión programa un temporizador de Insto para 25 minutos, espera el inicio de la reunión, guarda y activa No molestar, espera el fin y restaura el ajuste si sigue como lo dejó el plan. El ejemplo supone que la reunión es el próximo evento del calendario y empieza dentro de media hora. Instinct puede adaptar el plan a los horarios y a las capacidades autorizadas del teléfono.

Para usar líneas largas, actualizá el código de la planilla a partir de `docs/instinct-bitacora.gs` y creá una nueva versión de la implementación de Apps Script. La firma HMAC-SHA256 `insto-v4` usa las líneas UTF-8 tal como se enviaron. Los cuerpos dentro del límite de push cifrado pueden llegar de inmediato. Si no entran, llega el timbre y se lee el plan del buzón. La bitácora conserva los acuses de ejecución y su deduplicación.

## Pasos y políticas

El catálogo vivo contiene el contrato `plan_json` v1, restricciones, capacidades disponibles, revisiones de rutinas habilitadas y los calendarios autorizados. Los tiempos absolutos están en milisegundos Unix. El máximo es de 8 KiB por línea, 24 KiB por orden, 24 pasos, 32 acciones contando expansiones, alternativas y opciones, 2 decisiones y 5 planes activos. El plazo por defecto de un plan es de 12 horas y el máximo es de 24 horas. Las dependencias apuntan solo a pasos anteriores completados. Una alternativa se ejecuta ante un fallo definitivo. Un resultado incierto suspende el plan, y la reanudación manual continúa después del paso incierto.

El plan puede combinar acciones, rutinas habilitadas y fijadas a su revisión, condiciones, preguntas, esperas y restauración. Las consultas de calendario entregan horarios acotados y pueden vincular un único evento a un cambio posterior con `eventFrom`. La persona habilita lectura y escritura por calendario. Una app externa o la app Reloj requieren un toque desde **Planes de Instinct → Continuar** con el teléfono desbloqueado. Se registran como entregas en el último paso.

Una acción con permiso pendiente espera hasta que la persona lo conceda. Abrir Insto reevalúa la disponibilidad. La revocación de un permiso de Android vuelve a bloquear los pasos que lo necesitan. Un cambio a Nunca detiene la ejecución correspondiente. La app ya concedida o autorizada no recibe pedidos redundantes.

SQLite agrega el registro del reloj propio y evidencia opcional por ejecución. El formato de respaldo conserva líneas completas de planes portables aún sin efectos y su vencimiento original. Se restauran apagados y se validan al reactivar Instinct. Las autorizaciones ligadas a calendarios, planes con IDs de este teléfono, historial y relojes ya programados quedan en el dispositivo de origen.

## Comprobación en un teléfono

- Concedé calendario, notificaciones y acceso a alarmas exactas. En Autonomía revisá Calendario, Reloj, No molestar y Rutinas.
- Probá el objetivo de la reunión con la pantalla apagada. Comprobá el aviso de Insto antes del evento, el filtro durante la reunión y su restauración. Cambiá el filtro manualmente durante otra ejecución y comprobá que Insto preserve la elección.
- Revocá un permiso mientras espera el plan y verificá la espera y el reinicio desde el paso pendiente. Reiniciá el dispositivo con un temporizador programado y revisá si Android lo rearma y entrega dentro del margen indicado.
- Probá un evento en un calendario de solo lectura, otro que cambió después de la consulta y un intento de abrir un recurso con la pantalla bloqueada.
- Verificá el catálogo y los acuses en la planilla. La hora exacta, el sonido en Doze y el comportamiento de la app Reloj dependen del dispositivo y sus permisos. [Capacidades y límites de Android](../architecture/instinct-device-capabilities.md)
