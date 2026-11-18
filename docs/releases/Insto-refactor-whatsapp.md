# Automatización de WhatsApp

El servicio convierte ventanas de Android en snapshots. Las máquinas de texto
y audio deciden si esperar, tocar, confirmar o abortar. Los fixtures del
repositorio son sintéticos. El mecanismo de captura debug queda desactivado
hasta crear el marcador indicado en el documento de arquitectura.

## Verificación

- 31 pruebas JVM de las máquinas, las guardas de envío y la política de
  confirmación pasaron sin Robolectric.
- La suite Gradle completa local se frenó al descargar el runtime nativo de
  Robolectric por HTTP 403. La corrida de GitHub queda registrada en los checks
  del PR.
- Pendiente en el Motorola: enviar texto y audio original al contacto de
  prueba, comprobar un único envío y comprobar el regreso a la app previa.
  Registrar versión de WhatsApp y los diálogos observados. Ejecutar también una
  acción local desde el flotante para validar el conjunto del refactor.
