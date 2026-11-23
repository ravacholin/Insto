# Insto 1.22.0 · Decisiones y reglas contextuales

Protocolo Insto v8, código de versión 31.

- `decidir` conserva la pregunta y registra el toque junto con la acción en SQLite. Un toque repetido o una notificación vencida no crea otra ejecución. El resultado usa el diario y la bitácora existentes.
- Las preguntas muestran solo un aviso genérico en la pantalla bloqueada por defecto. Se puede permitir que Android muestre el texto y las opciones desde Ajustes → Propuestas de Instinct → Autonomía.
- Las reglas `cuando` se crean desde el buzón firmado. Después se ejecutan en el teléfono según los eventos de Android. Ajustes permite activarlas, desactivarlas y consultar su diagnóstico.
- La migración SQLite de v2 a v3 agrega `context_rules`. Las preguntas y las ejecuciones anteriores conservan sus filas. El respaldo `.insto` agrega las definiciones activas y desactivadas con su vencimiento original y la preferencia de privacidad. La restauración exige reactivación para incorporar las reglas.

## Validación en un teléfono

1. Enviar `decidir` con Silencio y No molestar desde el buzón. Revisar la notificación bloqueada con la preferencia apagada y encendida. Probar un toque, un segundo toque y una pregunta vencida.
2. Dejar una pregunta pendiente y reiniciar. Tras desbloquear Android por primera vez, verificar que reaparezca. Revocar el permiso de No molestar y comprobar que una opción elegida no active el modo ni abra otro diálogo de confirmación.
3. Crear una regla que combine batería baja y ausencia de carga. Con la pantalla apagada, desconectar el cargador y comprobar la notificación y la fila de ejecución. Probar un rebote corto y una transición estable fuera y dentro de la condición.
4. Probar Wi-Fi como red activa, disponibilidad de internet, reproducción, auriculares y desbloqueo. Probar una ventana horaria y un evento de calendario con y sin permiso. Comparar los tiempos con y sin permiso de alarmas exactas y en Doze.
5. Desactivar una regla pendiente de confirmación, reiniciar, volver a activarla y comprobar que la ocurrencia anterior quede cancelada. Exportar un respaldo, importar y verificar que las reglas esperen reactivación.

Los eventos dinámicos dependen de que Android mantenga conectado «Insto · notificaciones». Una alarma puede demorarse en Doze. Tras forzar la detención, Android reanuda la entrega cuando se vuelve a abrir Insto. La validación física de estos pasos queda pendiente.
