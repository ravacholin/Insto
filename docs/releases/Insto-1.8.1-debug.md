# Insto 1.8.1 — búsquedas de YouTube y Spotify

Compilación: 2026-11-17 UTC. Variante debug, versionCode 14.

[Descargar el APK desde Google Drive](https://drive.google.com/file/d/1RqfDAlhSdwGizKxOdmqDIYFfRs0icuJx/view?usp=drivesdk).

- Archivo: `Insto-1.8.1-YouTube-Spotify-debug.apk`.
- Fuente: base `85fd8f8` más la corrección de búsquedas del árbol de trabajo; no se atribuye este APK únicamente al commit base.
- Application ID: `com.aistudio.pingassist.qvrtkm`.
- Android mínimo: API 24; target API 36.
- Tamaño: 18.505.632 bytes, comprobado mediante metadatos de Drive después de subir.
- SHA-256 del APK: `72406c44cb76be974b7238e711295df37fbca2bba5d51a2bb0785036a0d93bb6`.
- SHA-256 del certificado: `eca11724e0b806f549c565681285ed279bf75342a1fc43de92a14e48ce145ee6`.

## Corrección

«buscar rebord en YouTube» y «buscar rebord en Spotify» producen una búsqueda local después de consultar Decisions, incluso si su respuesta válida deriva a Instinct o no alcanza el umbral. Esta recuperación solo admite órdenes explícitas de búsqueda de esas plataformas; no sustituye errores técnicos o falta de clave. Se conservan los controles de negaciones y condiciones. Spotify tiene un comando y una entrada propios en el catálogo.

Se prefiere la app correspondiente, con la misma búsqueda web como alternativa cuando no resuelve el intent. No se afirma reproducción automática. Los errores de ejecución permanecen locales.

## Verificación

- `:app:assembleDebug`: BUILD SUCCESSFUL.
- `apksigner verify --verbose --print-certs`: firma v2 verificada.
- `zipalign -c -P 16 4`: verificado.
- Segmentos ELF LOAD de las seis bibliotecas arm64-v8a/x86_64: alineación mínima de 16.384 bytes.
- Manifest y metadatos: versión 1.8.1, código 14, applicationId/minSdk/targetSdk comprobados.
- Integridad ZIP y presencia de `PhoneCommand$SpotifySearch` en DEX comprobadas.
- 61 pruebas JVM aprobadas; fuentes de producción y de todas las pruebas compilados.
- Suite completa bloqueada por HTTP 403 de `nativeruntime-dist-compat:1.0.18`. Con una exclusión externa solo para ese artefacto, `FloatingHybridRoutingTest` y `EverydayActionExecutorTest` no pudieron inicializar por HTTP 403 de `android-all-instrumented:9-robolectric-4913185-2-i7`; sus assertions Android no se ejecutaron. Las dependencias del proyecto permanecen intactas.

La clave anterior no estaba disponible. Se generó una clave de depuración nueva, excluida de Git; este APK no actualiza directamente una instalación con otra firma. Para actualizar conservando datos se necesita la firma original. No se probó el proveedor real ni un dispositivo físico.
