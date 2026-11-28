# Salud operativa

Insto usa `CapabilityHealthEngine` para reunir evidencia de preparación, pruebas y operaciones reales. Los identificadores de operaciones remotas provienen de `InstinctCapabilities`. El reconocimiento determinista se registra en `LocalIntentRegistry`, con las operaciones de diagnóstico excluidas de las decisiones remotas. `InstinctReadiness`, los adaptadores Android, el diario, `GeminiDiagnostics` y los circuit breakers conservan sus responsabilidades.

## Evidencia y alcance

| Fuente | Qué establece |
| --- | --- |
| Permisos, servicios, configuración local | Preparación y restricciones visibles del sistema. |
| Resultados y verificadores de ejecución | Resultado observado, entrega a otra aplicación, fallo documentado o efecto incierto. |
| Gemini, Groq y transcripción | Disponibilidad observada. HTTP 429 es límite de frecuencia. La cuota se informa cuando el código del proveedor la identifica. |
| Buzón, push y diario de Instinct | Recepción, validación, entrega y estados del registro. La latencia hasta el despacho proviene del diario existente. |
| Omniremote | El catálogo establece acceso al contrato local. Leer estado confirma que un destino respondió. Aplicar un cambio requiere la ruta normal y su verificador. |
| Vosk y micrófono | Archivos requeridos, carga nativa y captura de señal se comprueban por separado. El marcador de Vosk corresponde al hash del ZIP original. La lista de archivos y la carga nativa detectan un modelo descomprimido incompleto o ilegible. |

Los códigos distinguen hechos observados de causas probables. Por ejemplo, una llamada que agota su plazo informa el timeout y deja sin confirmar la causa. Una entrega mediante `Intent` queda con limitaciones hasta que haya evidencia de su efecto. Los fallos del asistente conservan una referencia opaca derivada de la sesión existente. El último fallo local muestra la etapa observada. Para una orden de Instinct, la consulta reutiliza su registro y distingue fallos previos al despacho de resultados que quedaron inciertos.

## Ejecución y recuperación

- El diagnóstico general ejecuta hasta cuatro comprobaciones simultáneas durante 30 segundos. Cada sonda tiene su propio plazo. Solicitudes coincidentes comparten el trabajo y el último consumidor puede cancelarlo.
- Las sondas generales leen estado, configuración o catálogos. La captura breve del micrófono, la carga nativa de Vosk y la lectura bajo demanda de un destino requieren un toque de autorización. Las capacidades con efectos externos se verifican mediante sus ejecuciones normales.
- La recuperación de Omniremote descarta el catálogo local y vuelve a negociar una sesión temporal. La recuperación de Instinct rearma el trabajo autorizado del buzón o vuelve a intentar publicar su catálogo. Dos intentos por incidente y un intervalo mínimo persistente limitan los reintentos. La recuperación se verifica mediante evidencia nueva. Las órdenes cuyo efecto quedó incierto siguen el flujo de reconciliación existente.
- Las observaciones se escriben atómicamente en `noBackupFilesDir`. Se conservan hasta 64 incidentes y 32 muestras de latencia por capacidad. La vigencia se evalúa al leer y se invalida por arranque, cambios de red, paquetes, micrófono y configuración observada. No se agrega trabajo periódico. Los fallos repetidos pueden consolidar comprobaciones relacionadas.
- El informe contiene identificadores del catálogo, tiempos, métricas y códigos. `summary.issues` agrupa los avisos vigentes de permisos de calendario y `summary.device_limits` enumera las capacidades sin soporte. `capabilities` conserva el detalle por capacidad. Los permisos detectados en comprobaciones previas aparecen como avisos vigentes. El historial conserva los fallos de órdenes y los resultados de recuperación. Los nombres de dispositivos, destinos, claves, transcripciones, líneas de órdenes y resultados privados quedan fuera del informe.

## Instinct y proveedores

El protocolo v8 agrega `salud` y `salud_destinos` en las capacidades del catálogo vivo y `salud_operativa` para el canal y los servicios autorizados. Los campos tradicionales mantienen su significado. Se publica por el trabajo existente, agrupando transiciones y omitiendo cambios que sólo alteran los contadores. El cliente calcula la antigüedad a partir de `comprobada_en` y deja de usar una prueba después de `vigente_hasta`. La consulta `#insto ver salud operativa` requiere el buzón firmado y usa los acuses configurados.

Un proveedor integra sus comprobaciones implementando `CapabilityHealthContributor`. Sus sondas devuelven códigos y fuentes tipadas y registran el efecto requerido. En Omniremote, la identidad del paquete y la autorización de Insto corresponden al servicio Binder explícito. La autorización observada proviene del proveedor. Insto observa que el paquete está instalado y qué contrato responde. La comprobación no busca una huella independiente del certificado de Omniremote. La identidad de un televisor o computadora se vincula al destino ya autorizado de ese catálogo.

## Verificación física

En Android 15 y 16 queda por comprobar el micrófono durante grabaciones concurrentes, Vosk con audio real, voces TTS offline, alarmas y temporizadores reales, activaciones de hardware, comportamiento con bloqueo y Doze, accesos directos de apps instaladas, push de Instinct y Omniremote firmado contra televisores y computadoras. El pipeline sin red requiere el modelo Vosk de español instalado. Shizuku sólo corresponde si se integra un proveedor que lo requiera.
