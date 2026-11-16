# Acciones nativas del teléfono

El usuario pidió delegar las funciones al teléfono y aprobó implementar la recomendación. Usa un Motorola y Reloj de Google. Este documento sustituye la decisión de mantener un cronómetro propio de la especificación anterior.

## Decisión

Usar intents de DeskClock dirigidos al paquete `com.google.android.deskclock`, comprobando que cada acción sea resoluble, esté exportada y no exija permisos que Insto no tenga. START y RESUME usan START_STOPWATCH; PAUSE usa PAUSE_STOPWATCH; RESET usa RESET_STOPWATCH; STATUS usa SHOW_STOPWATCH. Son acciones específicas de DeskClock, no una API pública universal de Android. El manifiesto AOSP histórico documenta estas acciones; la disponibilidad real se comprueba en el dispositivo. No suponer que todas las versiones de Google las admiten.

Se consideraron conservar el cronómetro propio y automatizar botones mediante Accesibilidad/ADB. El primero contradice el pedido; el segundo agrega fragilidad y privilegios que los intents no requieren. No incorporarlos sin comprobar primero una necesidad concreta en el Motorola.

Quitar el contador, persistencia, controles y actualización periódica del cronómetro de Insto. No migrar tiempos antiguos al Reloj: no hay una API verificada para ello; no borrar los datos anteriores. Antes de reiniciar el cronómetro nativo, pedir confirmación porque Insto no puede consultar si tiene un tiempo que se perderá. No prometer que la operación ya se completó por el solo lanzamiento de un intent.

Si el Reloj no expone una operación, mantener el pedido/audio y mostrar el motivo junto con una opción explícita para abrir Reloj de Google. No enviar el pedido a Instinct ni crear un cronómetro de reemplazo. Si Reloj de Google no está instalado, informar que falta sin abrir otra app arbitraria.

Conservar los intents públicos actuales de alarmas, temporizadores, apps y ajustes, y las APIs nativas de linterna/volumen. Agregar el permiso normal MODIFY_AUDIO_SETTINGS que necesita el volumen. Tras una ejecución o entrega exitosa, cerrar el floating con una confirmación breve del sistema; errores, permisos, aclaraciones y confirmaciones permanecen visibles. La finalización se consume una sola vez para evitar repeticiones al recrear la actividad.

## Verificación

Pruebas de integración para intents correctos, permisos/exportación, operaciones no disponibles, reset confirmado, conservación de borradores/audio y finalización del floating sin repetición. Mantener regresiones de interpretación, cancelación de transcripción y envío de audio original a WhatsApp. Compilar APK y ejecutar la suite viable. No hay teléfono conectado por ADB: la compatibilidad de la versión instalada de Google Clock queda pendiente de validación real. Incluir un script ADB de diagnóstico de solo lectura para inspeccionar versión y handlers de los cuatro intents (cinco operaciones) sin iniciar ni resetear cronómetros.

Referencia primaria: https://android.googlesource.com/platform/packages/apps/DeskClock/+/4af3db5767128318c0724f23f8c30685eab988c9/AndroidManifest.xml
