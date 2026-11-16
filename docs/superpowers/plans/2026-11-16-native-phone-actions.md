# Native phone actions Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Delegar el cronómetro al Reloj de Google y cerrar el floating tras acciones nativas exitosas.

**Architecture:** NativeStopwatchController resuelve intents específicos del Reloj y devuelve PhoneActionResult. PhoneActionExecutor conserva su API de ejecución, sin repositorio de cronómetro. MainViewModel produce una finalización consumible para AssistActivity; se elimina la UI/estado del cronómetro propio.

**Tech Stack:** Kotlin, Android intents/PackageManager, Compose, JUnit/Robolectric.

**Spec:** docs/superpowers/specs/2026-11-16-native-phone-actions-design.md

## Global Constraints

- Dirigir el cronómetro a `com.google.android.deskclock`; comprobar cada handler antes de lanzarlo.
- No agregar ADB/Accesibilidad/Shizuku como dependencias de ejecución.
- Conservar pedidos rechazados y el audio original; preservar envío existente a Instinct.
- No afirmar ejecución nativa comprobada sin dispositivo.

## Review Focus

- Handlers no exportados o protegidos: no lanzar ni afirmar éxito.
- Reloj ausente u operación no disponible: conservar pedido y no enviarlo a WhatsApp.
- Reset: nunca despachar antes de confirmar ni afirmar que el contador quedó detenido.
- Pausa/cierre durante transcripción: no ejecutar la respuesta tardía.
- Permisos o aclaraciones: no cerrar el floating antes de resolverlos.

### Task 1: Cronómetro nativo

Files: crear NativeStopwatchController.kt y NativeStopwatchControllerTest.kt en sus paquetes phone; modificar PhoneActionExecutor.kt/PhoneActionExecutorTest.kt; eliminar StopwatchRepository.kt, StopwatchState.kt y su prueba. Agregar visibilidad del paquete Google Clock al manifiesto.

Interfaces: NativeStopwatchController(context).execute(operation: StopwatchOp, confirmed: Boolean): PhoneActionResult; PhoneActionExecutor(context, nowHourMinute).execute sin cambio de resultado.

- [ ] Escribir pruebas de operaciones y rechazos usando PackageManager de Robolectric con handlers exportados/protegidos y comprobar intents lanzados.
- [ ] Ejecutar las pruebas y comprobar fallo por falta de implementación.
- [ ] Implementar controlador, confirmación de reset y opción de abrir Reloj ante incompatibilidad. Quitar almacenamiento propio.
- [ ] Verificar pruebas y compilación.

### Task 2: Integración del floating

Files: MainViewModel.kt, AssistActivity.kt, AssistOverlayScreen.kt, FloatingPhoneRoutingTest.kt, PhoneCommandParser.kt y su prueba; permiso de volumen en AndroidManifest.xml.

Interfaces: FloatingAssistantState.phoneActionCompletion: String?; MainViewModel.consumePhoneActionCompletion(): String? consume y limpia la finalización antes de cerrar.

- [ ] Reemplazar expectativas de cronómetro propio por intents reales y probar conservación en fallas, pausa de transcripción, finalización consumible, y permisos/confirmaciones sin cierre.
- [ ] Comprobar RED y luego eliminar contador/controles/ticks; emitir finalización solo tras COMPLETED/HANDOFF; consumir desde Activity con Toast y finish().
- [ ] Aceptar «abrí el cronómetro» como STATUS.
- [ ] Verificar pruebas de rutas y regresiones existentes.

### Task 3: Entrega y verificación

Files: docs/FLOATING-ASSISTANT.md, scripts/check-google-clock.sh, app/build.gradle.kts.

- [ ] Documentar cambios y validación manual; agregar diagnóstico ADB solo lectura.
- [ ] Incrementar versión a 1.4.4 (versionCode 8).
- [ ] Ejecutar suite completa y assembleDebug; registrar bloqueos exactos y verificaciones alternativas sin alterar dependencias del proyecto.
- [ ] Revisar diff final y cambios de comportamiento; entregar APK, commit y rama con límites reales.

## Resultado de ejecución

Implementación de las tres tareas terminada en `feat/native-phone-actions`. El caso de interpretación «abrí el cronómetro» se observó fallar antes del cambio y pasó después. Las pruebas Android se escribieron y compilaron; no se pudieron ejecutar por los artefactos HTTP 403 indicados en `docs/FLOATING-ASSISTANT.md`, de modo que sus pasos de RED/GREEN quedan bloqueados, no aprobados.

Verificación final: APK compilado; 50 pruebas JVM aprobadas; firma/alineación verificadas; suite completa intentada sin exclusiones y bloqueada por `nativeruntime-dist-compat:1.0.18`. La revisión independiente detectó una carrera de cierre al entregar el pedido a otra actividad; se corrigió trasladando el observador de finalización de Compose a `Activity.lifecycleScope` y se agregó regresión compilada. Sin teléfono conectado, la compatibilidad real de Google Clock queda pendiente. Entrega como PR borrador, sin fusionar a main.
