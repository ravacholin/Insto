# Insto 1.10.0 — voz adaptativa y contexto compartido

Variante debug, código 17. Integrada sobre main `196685e66560799b2f3c3bdff29fcfd606b44553` / PR #16; incluye el trabajo anterior del PR #15 y conserva la disponibilidad de acciones locales e Instinct cuando Gemini falla.

## Cambios

- El silencio final se adapta a la duración de la voz: 900 ms para tramos menores de 4 s, 1600 ms entre 4 y 12 s, y 2400 ms desde 12 s. No identifica semánticamente un dictado; los umbrales necesitan validación física.
- El menú de selección y Compartir de Android permiten abrir Insto con texto de referencia visible y removible. El texto recibido por sí solo no ejecuta acciones.
- Los pedidos escritos o hablados de resumen/traducción llevan instrucción y referencia juntas a Instinct por texto. Sin contexto se conserva el audio original. Un enlace compartido aporta solo su URL: no se descarga el cuerpo de la página.
- Los eventos con fecha en el texto usan extracción estructurada cuando Gemini está habilitado, con revisión antes de ejecutar. Los comandos completos conservan el camino rápido del PR #16.
- Cambiar o quitar la referencia invalida decisiones y planes sin confirmar. Se mantiene la referencia al rotar o reintentar y se elimina al cerrar.
- La compilación debug muestra y registra tiempos por etapa. `scripts/measure-voice-latency.py` recolecta CSV y p50/p95 por ADB, sin audio ni transcripciones.
- Streaming evaluado pero no habilitado: el endpoint de Groq usado actualmente necesita el archivo finalizado. No incluye lectura de pantalla ni permisos nuevos.

## APK

- [Descargar en Google Drive](https://drive.google.com/file/d/16Ifgs-T1RjpXdCntmYDtp3lWqJEXijt9/view?usp=drivesdk).
- Nombre y tamaño verificados por metadatos de Drive: `Insto-1.10.0-voz-adaptativa-contexto-debug.apk`, 18.653.510 bytes, en Mi unidad.
- SHA-256 local: `3d255d0297f2e037188fcf34d4f6a962c9acc7f93b1bdec1973b7e25b95b81ef`.
- Application ID: `com.aistudio.pingassist.qvrtkm`; minSdk 24, targetSdk 36.
- Firma v2 y alineación ZIP/16 KiB verificadas. Usa una clave debug local nueva, distinta de la entrega 1.9.1: puede no instalarse como actualización sobre el APK anterior. La clave no se publica.
- La subida inicial de la base 1.9.0 se reemplazó en el mismo archivo de Drive por esta compilación integrada 1.10.0 antes de entregar el enlace.

## Verificación

- `python3 scripts/test-phone-core.py`: 177 casos sin fallos, 176 aprobados y una evaluación real opt-in de Gemini omitida.
- `assembleDebug compileDebugUnitTestKotlin`: BUILD SUCCESSFUL; también compilan las pruebas Android agregadas.
- El calendario contextual se comprobó en rojo después de integrar el PR #16 y volvió a verde al conservar la extracción con referencia sin bloquear comandos locales completos.
- Suite completa de Robolectric: la ejecución local sigue bloqueada antes de iniciar los casos por HTTP 403 al descargar `org.robolectric:nativeruntime-dist-compat:1.0.18` desde el mirror de Maven. El PR ejecuta la suite en CI; consultar sus checks para el resultado actualizado.
- Revisión independiente de la implementación e integración completada. Colector ADB verificado con datos simulados; no equivale a una medición de rendimiento.
- No hay teléfono conectado: quedan pendientes micrófono y latencia físicos, servicios reales de Gemini/Groq, compartir/seleccionar en otras apps y recepción real en WhatsApp.

[Protocolo de medición y límites](../VOICE-CONTEXT.md).

## Corrección posterior a la primera ejecución Android

- Código corregido: `55c556453fe5da88cc1411a0d2d46d5f3cbd393f`.
- Se acepta defensivamente un intent de inicio ausente sin intentar leer contexto. La regresión de ciclo de vida comprueba la finalización pendiente del handoff real y su consumo al detener el flotante.
- La regresión de adjuntos usa el FileProvider real del manifest y comprueba URI/nombre antes del reemplazo. No se omitieron pruebas.
- APK recompilado, firma v2 y alineación verificadas, mismo nombre y enlace de Drive.

Las trazas completas se imprimen automáticamente en CI si falla una prueba. Se aisló FileProvider por caso en las pruebas de audio. La regresión de ciclo de vida conserva la detención del flotante antes del siguiente frame. El intent exacto del Reloj y el envío único siguen comprobándose en `NativeStopwatchControllerTest` y `FloatingPhoneRoutingTest`, que permanecen en la suite completa.
