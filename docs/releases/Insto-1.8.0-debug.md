# Insto 1.8.0 — APK de prueba

Compilación: 2026-11-16, hora de Buenos Aires. Variante debug, versionCode 13.

[Descargar el APK desde Google Drive](https://drive.google.com/file/d/1NkdbMHyqsUJLEZGMkNlrDkkL7pzwRxyD/view?usp=drivesdk).

- Archivo: `Insto-1.8.0-Decisions-first-debug.apk`.
- Fuente: commit `e622427f4915aa03b3443d051e54a6aa76f54714`.
- Application ID: `com.aistudio.pingassist.qvrtkm`.
- Android mínimo: API 24; target API 36.
- Tamaño: 18.505.576 bytes; leído nuevamente en Drive después de subir.
- SHA-256 del APK: `147a02ac521e34146ce817de5901eb31e3f821852fad0811ca005eb7e59466a0`.
- SHA-256 del certificado de firma: `2cea9b9d0e1752c16c1d89a8c16d7810a1bbee94d5ae465950dfe7bfa60949eb`.

Se generó una clave de depuración nueva porque no había una clave de firma disponible. No permite actualizar directamente una instalación firmada con otra clave. La clave privada está excluida de Git y no forma parte del APK subido.

## Verificación

- `assembleDebug`: BUILD SUCCESSFUL.
- `apksigner verify --verbose --print-certs`: firma v2 verificada.
- `zipalign -c -P 16 4`: verificado.
- Segmentos ELF LOAD de las seis bibliotecas arm64-v8a/x86_64: alineación de 16.384 bytes.
- Manifest y metadatos del APK: versión 1.8.0, código 13, package/minSdk/targetSdk comprobados.
- Se conservan las 146 pruebas JVM aprobadas en 25 clases y la compilación de fuentes de pruebas registradas en el plan.

La suite completa y las assertions Robolectric siguen bloqueadas por descargas HTTP 403. No se verificaron Gemini real ni un dispositivo físico; este artefacto no implica validación física ni precisión comprobada del clasificador.
