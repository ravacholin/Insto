# Insto 1.18.0 (código 27): orden directa en el timbre, catálogo vivo y probar una orden

Tres cambios para que Instinct actúe más rápido y con las reglas al día. La orden firmada puede viajar **dentro del timbre** y ejecutarse apenas llega; Insto publica en la planilla un **catálogo vivo** con las reglas vigentes; e Instinct puede **probar** una orden antes de mandarla. Todo lo nuevo arranca apagado o es de solo lectura y, con los valores por defecto, funciona igual que en 1.17.0. No suma dependencias ni servidores. Protocolo Insto v6.

Detalle completo: [Órdenes de Instinct](../INSTINCT-PROPUESTAS.md): [Orden directa en el timbre](../INSTINCT-PROPUESTAS.md#orden-directa-en-el-timbre-1180), [Catálogo vivo](../INSTINCT-PROPUESTAS.md#catálogo-vivo-1180) y [Probar una orden](../INSTINCT-PROPUESTAS.md#probar-una-orden-1180).

## Orden directa en el timbre

- **El modelo de seguridad no cambia:** la firma HMAC sigue siendo lo único que autoriza una orden. La orden del timbre se verifica con la misma ruta que el sondeo (firma, 15 minutos / 2 minutos, id visto).
- **Apps Script:** propiedad `insto_timbre_con_orden`, que Insto cambia con `action:"timbre"` + `conOrden` (si falta, se conserva: compatible con 1.17.0). Con la opción encendida, una orden nueva viaja como `{"v":1,"id","at","lines","sig"}` si ocupa como mucho 3500 bytes; si no, `insto-timbre` como siempre.
- **Una sola ejecución:** `InstinctMailboxSeen.claim` comprueba y marca en disco en la misma sección; el sondeo también toma sus órdenes con `claimAll` sobre la misma instancia. Si la lee el sondeo al mismo tiempo, gana uno solo.
- Después de una orden directa, un sondeo a los ~60 segundos la marca como tomada. Una firma inválida o vencida no se ejecuta, no se responde y sondea enseguida.
- Ajustes → Buzón → Timbre: **«Incluir la orden en el timbre»**, apagado por defecto. Diagnóstico: «… · órdenes directas N · payload inválido N».

## Catálogo vivo

- `InstinctProtocolCatalog.json` arma el catálogo desde las mismas fuentes que el texto, con un `hash` SHA-256 de su forma canónica. Nunca URL, token, clave ni endpoint.
- **Apps Script:** hoja `Catálogo` (una fila), `POST action:"catalogo"` (con `"igual"` si el hash no cambió, como mucho 45 000 caracteres) y `GET action=catalogo`.
- Insto lo publica cuando cambia el hash: al cambiar la autonomía, las horas de silencio, una rutina, el buzón, el timbre o los acuses, después de actualizar Insto y al reiniciar el teléfono. Job propio con reintentos; un fallo no bloquea nada.
- Ajustes: «Catálogo para Instinct: publicado HH:MM · ••••xxxxxx» y «Publicar catálogo ahora». `#insto ver catálogo` responde con el hash publicado.

## Probar una orden

- `#insto probar <orden>`, por WhatsApp y por el buzón: valida con el mismo validador y dice qué pasaría ahora, sin ejecutar, guardar, programar, avisar ni consumir el freno.
- La decisión del gate salió a `InstinctGatePreview`, una función pura que también usa el camino real: la prueba y la ejecución no pueden diferir.
- Respuestas fijas: `válida · <categoría> · la haría sola`, `… esperaría confirmación: <motivo>`, `válida · programada: <cuándo>`, `no válida: <motivo>`, `rechazada: la persona no permite esta acción`. Freno propio de 20 cada 10 minutos.

## Privacidad

Con la orden directa encendida, ntfy ve las líneas de cada orden nueva, pero no puede cambiarlas: la firma lo impide; con tu propio servidor ntfy no las ve nadie más. El catálogo vivo solo dice lo que ya decía el catálogo de texto, más qué canales están encendidos. Todo lo nuevo vive en `noBackupFilesDir`.

## Para actualizar

1. Pegá el `instinct-bitacora.gs` nuevo (conservá tu `const TOKEN`) e implementá una versión nueva (**Implementar → Gestionar implementaciones → Editar → Nueva versión**).
2. Actualizá Insto. El catálogo se publica solo al primer arranque.
3. Si querés la orden directa, encendela en Ajustes → Buzón → Timbre.

## Verificación

- `python3 scripts/test-phone-core.py` (687 pruebas), con `InstinctDoorbellTest` (parser estricto), `InstinctMailboxTest` (claim y carrera con el sondeo), `InstinctCatalogJsonTest` (fuentes, hash canónico, sin secretos, copia de referencia) e `InstinctProbeTest` (vista previa igual a la decisión real, formas fijas).
- `node scripts/test-bitacora-gs.js` (22 pruebas): orden en el timbre, tamaño límite, repetida, script viejo y catálogo.
- `./gradlew compileDebugUnitTestKotlin`. Los tests de Robolectric nuevos (`InstinctDoorbellFlowTest`, `InstinctCatalogFlowTest`) quedan para el CI.
