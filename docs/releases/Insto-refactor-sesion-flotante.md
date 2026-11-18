# Sesión del asistente flotante

`MainViewModel` delega el flujo flotante al controlador de sesión. El reductor
conserva el audio y la transcripción y decide si entregar resultados por id de
sesión. El ejecutor de acciones locales y el adaptador de envío a Instinct
quedan en clases separadas.

## Verificación

- Pasaron cuatro pruebas JVM del reductor. Cubren captura, interpretación,
  retención, permisos, cierre, resultados obsoletos y reintento con la
  transcripción conservada. El ensamblado debug y la compilación de los tests
  también completaron localmente.
- La suite completa local depende del artefacto nativo de Robolectric, cuyo
  mirror respondió HTTP 403. El estado de la suite completa queda en el PR.
- Pendiente en el Motorola: enviar texto y audio a Instinct y ejecutar una
  acción local. Verificar cancelación y edición mientras llega una respuesta,
  retención al pausar y reintento con el audio original. Comprobar que WhatsApp
  recibe cada envío una sola vez.
