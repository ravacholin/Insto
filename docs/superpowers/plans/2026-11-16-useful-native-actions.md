# Useful native actions Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement inline, with independent review at the end.

**Goal:** Ejecutar pedidos cotidianos en Maps, Teléfono, Calendario, Cámara, YouTube y música desde el floating.
**Architecture:** Intérprete puro especializado conserva los textos originales; el planificador puro produce contratos de intents; ejecutor Android resuelve apps, alias y contactos; ViewModel coordina permisos/opciones y el cierre ya existente.
**Tech Stack:** Kotlin, Android intents/ContactsProvider, JUnit/Robolectric, Compose existente.
**Spec:** docs/superpowers/specs/2026-11-16-useful-native-actions-design.md

## Global Constraints

- No agregar dependencias ni automatizar llamadas, envíos o guardado de eventos.
- Conservar texto, audio y flujos previos; no ejecutar resultados tardíos.
- Rechazar parámetros incompletos o inválidos sin inventar datos ni elegir contactos ambiguos.
- Mantener intacta la clave de firma 1.4.4 y no subirla a Git/Drive.

## Review Focus

- Negaciones/explicaciones, órdenes múltiples y parámetros con signos no deben producir ejecución parcial.
- Fechas inválidas/pasadas/ambiguas y cambios de zona no deben desplazar eventos silenciosamente.
- Contactos duplicados, números no marcables y permiso denegado no deben elegir un destinatario ni perder el pedido.
- Apps ausentes y fallas de lanzamiento deben conservar el pedido/audio.
- Permisos y selección de contactos no deben emitir cierre ni reejecutar tras cancelación.

### Task 1: Interpretación y contratos puros

Files: PhoneCommand.kt, PhoneCommandParser.kt; crear EverydayCommandParser.kt, CalendarRequestParser.kt, NativeIntentSpec.kt y sus pruebas.
Interfaces: EverydayCommandParser(now: () -> Calendar).parse(text: String): PhoneRoute?; NativeIntentSpec.forCommand(command): NativeIntentSpec? con action/data/mime y extras tipados; nuevos comandos de datos en PhoneCommand.
- [x] Observar fallar reconocimiento de los nuevos pedidos con las APIs actuales.
- [x] Escribir pruebas de payloads, tiempos y URLs; implementar modelos, parser y contratos sin dependencias Android.
- [x] Verificar reconocimiento y contratos junto con regresiones puras.

### Task 2: Integración Android

Files: EverydayActionExecutor.kt, SavedPlacesRepository.kt, ContactSelection.kt; PhoneActionExecutor.kt/PhoneActionResult.kt; MainViewModel.kt, AssistActivity.kt, AssistOverlayScreen.kt, AndroidManifest.xml; pruebas de intents y rutas.
Interfaces: PhoneActionResult agrega needsContacts/contactOptions; el ViewModel expone chooseFloatingContact/onFloatingContactsPermission; ContactSelection devuelve coincidencias exactas o alternativas explícitas.
- [x] Agregar pruebas de permisos, resolución, selección y retención; compilar/ejecutar las que permita el entorno.
- [x] Implementar entregas nativas, alias privados y selección de contactos, conservando el cierre existente.
- [x] Añadir ayuda con ejemplos y verificar flujos de voz y texto sin WhatsApp.

### Task 3: Validación y entrega

Files: app/build.gradle.kts, docs/FLOATING-ASSISTANT.md y ejemplos de uso.
- [x] Incrementar a 1.5.0/versionCode 9, compilar APK y todos los fuentes de prueba.
- [x] Ejecutar pruebas puras y suite completa; registrar bloqueos sin presentarlos como aprobación.
- [x] Revisión independiente; corregir problemas importantes y verificar de nuevo.
- [ ] Crear PR borrador y subir APK a Drive, verificando metadata del archivo final.

## Resultado antes de entrega

Tareas de implementación y verificación terminadas: APK 1.5.0 compilado, certificado igual al de 1.4.4 y 76 pruebas JVM aprobadas. Nuevas pruebas de integración compiladas, con ejecución bloqueada por android-all-instrumented HTTP403; suite completa intentada sin exclusiones y bloqueada por nativeruntime-dist-compat HTTP403. La revisión independiente encontró cinco problemas semánticos, reproducidos con regresiones RED y corregidos; las regresiones y toda la suite JVM disponible están verdes. No se modificaron dependencias ni se usaron datos reales de contactos. La entrega será un PR borrador y un APK separado en Drive; no se fusionará a main sin intervención del usuario.
