# Insto 1.8.0 — Gemini, contexto y tratamiento de errores

Compilación: 2026-11-17 UTC. Variante debug; versionCode 13.

[Descargar el APK desde Google Drive](https://drive.google.com/file/d/1kyFLdebIrxNMb3VbydnveaVYEUlmlKh1/view?usp=drivesdk).

- Archivo: `Insto-1.8.0-Gemini-context-128a547-debug.apk`.
- Tamaño comprobado en Drive: 18.471.936 bytes.
- Application ID: `com.aistudio.pingassist.qvrtkm`; minSdk 24, targetSdk 36.
- SHA-256 del APK: `1f74b87394557fa2c9bd80ffd347c5995e072357ad38300c9809fdd6a527a05a`.
- SHA-256 del certificado: `15d86952df5d4af5259f08c84c7f2a548474a6bfb536bd7da714537fc991fa6e`.
- Commit de código publicado: `9f4a57520ade288e22b34899d4fbbdc01a74a4fd`.
- Árbol de fuentes verificado: `d0031d222389efcbb26765cf62944122ee11878f`.
- El APK se compiló del commit local `128a547`; su árbol coincide exactamente con el commit de código publicado. La diferencia de identificador corresponde a metadatos de publicación mediante el conector de GitHub.

## Verificación

- `assembleDebug`: BUILD SUCCESSFUL, 38 tareas.
- Firma APK v2 comprobada con `apksigner verify --verbose --print-certs`.
- Alineación comprobada con `zipalign -c -P 16 -v 4`.
- Integridad ZIP, manifest, DEX, applicationId y versión comprobados.
- Núcleo Kotlin: 122 casos JUnit correctos; evaluación real omitida salvo activación explícita.
- App y fuentes de prueba Android compilados correctamente.
- Suite Robolectric bloqueada por HTTP 403 al descargar nativeruntime y android-all. No se verificaron una cuenta real de Gemini ni un teléfono físico.

## Firma y compatibilidad de actualización

No estaba disponible la clave anterior. Se generó una clave de depuración nueva, excluida de Git y de Drive. El APK puede no reemplazar instalaciones firmadas con otra clave; no se garantiza una actualización conservando datos con esta firma.

## Base e integración pendientes

Esta entrega conserva la implementación aprobada en esta conversación sobre `de25ed4`. Mientras se desarrollaba, main avanzó a `899c93f` con las versiones 1.8.0/1.8.1, nuevas acciones y una arquitectura que exige Decisions para cada pedido y elimina la extracción generativa.

La comprobación de fusión detectó conflictos en seis archivos: configuración de versión, Decisions, HybridPhoneRouter, StructuredCommandClient (eliminado en main), MainViewModel y FloatingWorkflowTest. El PR se entrega como borrador; no se fusiona ni sobrescribe la versión actual. Integrar ambas arquitecturas requiere resolver la elección solicitada al usuario.

Este APK corresponde a la rama de Gemini/contexto indicada; no contiene las mejoras de main incorporadas después de `de25ed4` y no se presenta como sustituto de la versión 1.8.1.
