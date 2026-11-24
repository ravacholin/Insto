# Insto 1.23.0 · Planes de varios pasos

Protocolo Insto v8 (compatible: solo se agregan formas), código de versión 32.

- Nueva orden `#insto plan: <paso>; <paso>; …`, por WhatsApp y por el buzón firmado, con secuencias, alternativas `si falla`, condiciones `si … entonces … si no …`, esperas, disparadores de calendario, auriculares y desbloqueo, `decidir` con secuencias por opción y `guardar`/`restaurar`. Hasta 6 acciones sin interfaz. Gramática fija: cualquier otra forma se rechaza entera.
- `InstinctPlanEngine` ejecuta cada paso después de verificar el anterior con el ejecutor, el verificador, la autonomía, las preguntas, los disparadores y Deshacer existentes. Un fallo detiene el plan o corre la alternativa; un resultado incierto no se repite y suspende el plan.
- SQLite v3 → v4: tablas `plans` y `plan_steps` en `instinct-executions.db`; las filas anteriores no cambian. Cada acción es una fila del diario con transporte `plan`. Tras un reinicio el plan sigue desde su fila sin duplicar efectos.
- Consultas nuevas: `ver planes`, `estado de plan <id>`, `cancelar plan <id>`, `reanudar plan <id>`. Sección «Planes de Instinct» en Ajustes.
- Progreso y resultado en la bitácora como eventos `plan`, con ids estables. Nada nuevo en el chat de WhatsApp.
- Catálogo de texto y JSON con la sección `planes` y sus límites. El respaldo `.insto` lleva los planes todavía no empezados, que se reanudan con «Reactivar».

## Validación en un teléfono

1. Mandar por el buzón `#insto plan: activar no molestar; poner el volumen al 20 por ciento; pausar la música` con la pantalla apagada. Verificar los tres efectos, los eventos `plan` en la bitácora y que el chat no reciba nada.
2. Con un evento en el calendario dentro de la próxima hora, mandar el plan de «al empezar el próximo evento». Comprobar que No molestar se active al empezar y vuelva al estado anterior al terminar. Repetir apagando No molestar a mano durante el evento: no debe volver a tocarse.
3. Mandar el plan con `decidir`, responder desde la pantalla bloqueada y verificar la secuencia elegida. Repetir forzando el cierre de Insto antes de responder.
4. Con Volumen en Confirmar, verificar que el plan pida una sola aprobación. Con Nunca, que se rechace.
5. Forzar la detención de Insto durante una espera y volver a abrirlo: el plan tiene que seguir sin repetir pasos.

Las demoras de alarmas en Doze y la entrega de eventos de «Insto · notificaciones» siguen los límites de 1.17.0 y 1.22.0. La validación física queda pendiente.
