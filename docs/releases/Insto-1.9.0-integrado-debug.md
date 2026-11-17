# Insto 1.9.0 — Gemini integrado con main

Compilación: 2026-11-17 UTC. Variante debug, versionCode 15.

## Fuente y compatibilidad

- Commit de código: `a5366bb648b26599bcc6e19e74f7a5a5c1cc1393`.
- Árbol de fuentes: `032399bc75c891325eeb307faf02572792133314`.
- Incluye main `899c93f`: acciones nativas ampliadas, rutinas configuradas y correcciones de búsquedas de YouTube/Spotify.
- Las dos arquitecturas anteriores se integraron en un único flujo: con Gemini activado, Decisions clasifica cada pedido nuevo; el catálogo actual resuelve los parámetros completos antes de recurrir a extracción. Las respuestas a campos pendientes conservan el intent y sus datos.
- Se conservan borradores tipados, correcciones por paso, revisión de propuestas generativas y cancelación de respuestas obsoletas. Los errores y acciones telefónicas de baja confianza no se envían automáticamente a Instinct. Las búsquedas explícitas de YouTube/Spotify mantienen la recuperación local acotada.
- Sin Gemini permanece disponible la interpretación local. Se corrigió la aclaración de alarmas para conservar minutos, repetición, etiqueta y fecha al responder el período.

## APK

- Archivo: `Insto-1.9.0-integrado-debug.apk`.
- [Descargar desde Google Drive](https://drive.google.com/file/d/1Dyu0NBF0nHNnLqfsItYmaf2abnAFpCOP/view?usp=drivesdk); metadatos leídos tras la subida: mismo nombre, tamaño y carpeta Mi unidad.
- Tamaño: 18.920.015 bytes.
- SHA-256: `450a155d418306526d8d769f26491dbf13c98fb81189342baa79e7691031d45a`.
- Application ID: `com.aistudio.pingassist.qvrtkm`; minSdk 24, targetSdk 36.
- Certificado SHA-256: `15d86952df5d4af5259f08c84c7f2a548474a6bfb536bd7da714537fc991fa6e`.
- Se reutiliza la firma de depuración del APK de contexto 1.8.0 entregado en esta conversación. No se garantiza actualización sobre APK firmados con otra clave. La clave no se publica.

## Verificación

- `assembleDebug compileDebugUnitTestKotlin`: BUILD SUCCESSFUL.
- Firma APK v2, integridad ZIP, manifest y versión comprobados.
- Alineación ZIP y segmentos ELF LOAD de las seis bibliotecas arm64-v8a/x86_64: mínimo 16 KiB.
- DEX contiene coordinador, catálogo, Spotify y actividades nativas de calendario/archivos.
- Núcleo Kotlin: 153 casos JUnit: 152 aprobados y una evaluación real opt-in omitida.
- [CI Android completa](https://github.com/ravacholin/Insto/actions/runs/44039148156): 287 casos, 286 aprobados, una evaluación real opt-in omitida; cero fallos y cero errores. `testDebugUnitTest assembleDebug`: BUILD SUCCESSFUL.
- No se ejecutaron llamadas con credenciales reales de Gemini ni pruebas físicas del teléfono; no se atribuyen a las simulaciones esas verificaciones.
