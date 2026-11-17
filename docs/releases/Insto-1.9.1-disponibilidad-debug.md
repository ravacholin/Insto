# Insto 1.9.1 — disponibilidad de intents e Instinct

Variante debug; versionCode 16. Actualiza el APK 1.9.0 de esta conversación con la misma firma, sin requerir borrar datos.

## Corrección del fallo reportado

La versión 1.9.0 hacía depender todos los pedidos de Decisions y de cuatro puntuaciones mínimas de 0,95. Esto permitía bloquear incluso «abrir spotify» por timeout y «prender linterna» por confianza insuficiente; los fallos del proveedor también impedían derivar consultas a Instinct.

- Los comandos completos reconocidos y los campos pendientes se resuelven localmente antes de Google. Las órdenes de varios pasos conservan el flujo de planificación.
- Las consultas explícitas a Instinct no esperan a Gemini. Los pedidos no reconocidos localmente vuelven a Instinct ante timeout, cuota, autenticación, conexión, respuesta inválida, incertidumbre o falta de coincidencia.
- Se mantienen las restricciones explícitas, los borradores activos, las confirmaciones de efectos y la cancelación de respuestas obsoletas. Una ejecución rechazada por Android sigue siendo local.
- Cargar las preferencias antes de un envío ya no borra el texto activo si falta configurar el contacto. La fecha de una alarma incompleta se conserva mediante la continuación con origen.
- El acceso a los ajustes de Accesibilidad permanece visible cuando Android indica habilitado pero el servicio no está conectado.
- «Probar conexión de Gemini» consulta al proveedor directamente; una derivación local no se anuncia como conexión exitosa.

## Fuente

- Código: `c67884ef67bb9c6597167e305963def65f39d32e`.
- Árbol: `d9a3667ee8bf74957a06cfd895f7119185110505`.
- Base: main `9fcc0346da85d473029d312b4f11b7e9f4995318`, incluida la integración 1.9.0.
- PR: https://github.com/ravacholin/Insto/pull/16

## Validación

- Antes de corregir: siete de las ocho regresiones iniciales fallaban, incluidos los dos ejemplos exactos del reporte. La regresión adicional del saludo sin coincidencia y la de la fecha de alarma también se comprobaron en rojo.
- Núcleo final: 163 casos JUnit, 162 aprobados y una evaluación real opt-in omitida.
- Resultados y artefactos de la suite completa en las comprobaciones del [PR #16](https://github.com/ravacholin/Insto/pull/16).
- CI ejecuta la suite completa con Robolectric y compila el APK. Sus regresiones recorren el ViewModel real, los resolutores y los despachadores Android: Spotify instalado se lanza; linterna llega al permiso de cámara; cuota y timeout de Google permiten compartir respectivamente el texto y los bytes de audio originales una sola vez.
- La prueba de accesibilidad se comprobó en rojo antes de corregir el botón oculto.
- Las pruebas usan un proveedor simulado y aplicaciones simuladas en Robolectric; no verifican un dispositivo físico ni credenciales reales de Gemini/Groq ni recepción real en WhatsApp. No se atribuye esa validación a CI.

## APK

- [Descargar en Google Drive](https://drive.google.com/file/d/1YqUJoDUcXckZ24rX6Mqe1EGGHi4_Si3O/view?usp=drivesdk).
- Archivo: `Insto-1.9.1-disponibilidad-debug.apk`; tamaño: 18.920.397 bytes.
- SHA-256: `e56bd092706f1f7938d79eba90e2c20867e3c6dec83880ddb08ad180c3af2002`.
- Application ID: `com.aistudio.pingassist.qvrtkm`; minSdk 24, targetSdk 36.
- Certificado SHA-256: `15d86952df5d4af5259f08c84c7f2a548474a6bfb536bd7da714537fc991fa6e`, el mismo de la entrega 1.9.0. La clave no se publica.
- `assembleDebug compileDebugUnitTestKotlin`: BUILD SUCCESSFUL, 40 tareas.
- Firma v2, versión, integridad ZIP y alineación ZIP y ELF de 16 KiB de las seis bibliotecas nativas verificadas.
