# Validación de Insto en Android

## Secuencias y tiempos de interpretación

La línea base es el commit `12f8742`. Compará ambos APK en el mismo teléfono, con el mismo corpus sintético y la misma configuración de Gemini y Groq. Usá primero texto para medir la interpretación, y probá por separado el audio y la reutilización de la transcripción al reintentar.

- En cada turno registrá los incrementos de `GeminiDiagnostics` para Decisions, extracción, verificación y respaldo. Cronometrá desde el envío del texto hasta el resultado o la vista previa. Los contadores de interpretación generativa incluyen el último tiempo y fallo, y las rutas locales requieren un cronómetro externo.
- Incluí comandos locales, consultas generales, secuencias reconocibles, secuencias de interpretación ambigua, omisiones, condiciones, negativas y fallos de proveedor. Compará el orden, los parámetros y el estado esperado contra un resultado anotado para cada entrada.
- Informá p50 y p95, tasa de interpretación correcta, errores de clasificación y errores de ejecución por separado. Usá un teléfono y calendarios de prueba para medir ejecución. Los resultados con proveedores simulados permiten comprobar llamadas y orden; la latencia de producción depende de la red y del servicio.

## Casos para comprobar en un teléfono

1. Concedé y denegá `READ_CALENDAR` desde la interfaz flotante. Consultá hoy, mañana y la semana con y sin eventos, y con varias cuentas. Incluí un evento de día completo y uno que atraviese medianoche.
2. Prepará un evento con título, fecha, hora, duración y descripción. Elegí el calendario de destino en el editor de Android. Cancelá el editor y repetí guardando explícitamente, y comprobá la diferencia en el calendario.
3. Con dos eventos de igual título, elegí primero por horario y calendario. Cancelá la confirmación, repetí y confirmá. Verificá que al moverlo conserve la duración, que al cambiar solo la duración conserve el inicio y que la cancelación afecte al evento elegido.
4. Probá `WRITE_CALENDAR` denegado, un calendario de solo lectura y un cambio o borrado externo entre la vista previa y la confirmación. Cerrá la actividad durante la confirmación y volvé a abrirla para comprobar que el cambio no se reejecuta.
5. En una serie semanal, cambiá la hora o cancelá una sola ocurrencia. Verificá que las ocurrencias vecinas sigan intactas. Probá también un cambio de duración y una excepción que ya había sido modificada. La compatibilidad de estas operaciones depende del proveedor y de lo que pueda representar el editor nativo.
6. Repetí las consultas después de cambiar la zona horaria y alrededor de un cambio de horario de verano. Usá al menos dos implementaciones de calendario para validar las excepciones recurrentes, los eventos sincronizados y la selección de cuenta en el editor.

La creación usa el editor de Calendario para la selección de cuenta y el guardado. La duración predeterminada es una hora y aparece en la vista previa. Las consultas ordinarias se ejecutan sin proveedores de IA. Las propuestas generativas de calendario contienen filtros o cambios solicitados, y los resultados de CalendarContract permanecen en el dispositivo.
