# Accesos directos de aplicaciones

En **Configuración → Accesos directos** podés buscar los shortcuts que publican las apps instaladas, probarlos, asignarles un alias de voz, marcarlos como favoritos y deshabilitarlos individualmente. El diagnóstico muestra el permiso que Android concede a Insto. **Actualizar catálogo** vuelve a consultarlo. Los cambios de apps, shortcuts y perfiles también actualizan el catálogo.

Insto usa el acceso de host que Android concede al asistente seleccionado. Verifica ese acceso con el servicio del sistema en cada lanzamiento. Niagara sigue siendo el launcher. Si el dispositivo no concede el permiso a Insto como asistente, la función muestra el diagnóstico y espera a que Android lo conceda.

## Voz y workflows

- Decí «ejecutá el acceso directo Nuevo documento de Documentos» o usá el alias que configuraste. Los intents nativos conservan prioridad. Si hay varias coincidencias, Insto te pide elegir una.
- Podés enseñar una frase cuya acción sea un pedido de acceso directo ya reconocido. Se guarda la identidad del destino y se comprueba su disponibilidad al usarla.
- En un workflow decí, por ejemplo, «prendé la linterna y ejecutá el acceso directo Nuevo documento de Documentos». Revisá los pasos. Los lanzamientos piden confirmación y el workflow espera a que vuelvas de la otra app.
- Con Gemini Decisions activado, un pedido explícito que no se pudo resolver localmente puede consultar hasta cinco candidatos pertinentes del manifiesto. Solo se envían nombres públicos de esos candidatos y términos del pedido presentes en ese vocabulario. Los alias, los IDs de Android y los nombres de shortcuts dinámicos o solo fijados se usan en el teléfono.

La copia de seguridad de **Accesos directos de aplicaciones** conserva alias, favoritos y habilitación. La restauración busca la identidad original por paquete, ID y perfil. Si Android todavía no devuelve el shortcut, sus preferencias quedan guardadas para cuando aparezca. Las autorizaciones de Instinct se otorgan en cada dispositivo.

## Instinct

Cada acceso viene sin autorización remota. Podés habilitarlo individualmente en Configuración y copiar su identificador de Instinct. El catálogo vivo publica solo los identificadores de accesos autorizados y disponibles bajo la política de Aplicaciones. Los nombres, alias, paquetes e IDs del proveedor permanecen locales.

El buzón firmado admite la capacidad parametrizada **execute_app_shortcut**, como acción final de un plan JSON y sin alternativa automática. Por ejemplo, un plan de un paso tiene esta forma. Sustituí el valor de ejemplo por el identificador copiado de Configuración.

~~~json
{"version":1,"steps":[{"id":"abrir","kind":"action","capability":"execute_app_shortcut","params":{"shortcut":"0000000000000000000000000000000000000000000000000000000000000000"}}]}
~~~

Se aplican la política de Aplicaciones y los controles del buzón. Incluso con autonomía «Solo», el plan espera a que abras Insto, desbloquees el teléfono y confirmes el acceso directo específico. Android no expone a Insto un contrato sobre lo que hace la app al abrir un shortcut. La autorización permite solicitar ese lanzamiento y la confirmación local te muestra el destino antes de entregarlo. Cambios en el shortcut o en su aplicación pueden invalidar la autorización anterior.

## Android 15 y 16

Insto consulta con las reglas de acceso, visibilidad y perfiles de **LauncherApps**. Busca shortcuts de manifiesto y dinámicos. En Android 12 o posterior, cuando Insto es el asistente seleccionado, también solicita los fijados por otros launchers. Android puede ocultar perfiles, accesos de apps no disponibles y metadatos de fijación según el perfil y el host.

Antes de lanzar, Insto comprueba el permiso, el perfil, la disponibilidad, la revisión del shortcut y que el panel esté en primer plano. Si Android acepta la llamada a **startShortcut**, la acción queda como entrega a la app. Insto no puede comprobar el resultado dentro de la otra aplicación.

Para una prueba en teléfono, seleccioná Niagara como launcher e Insto como asistente. Probá un shortcut estático, uno dinámico y uno fijado en Niagara. Repetí tras bloquear y desbloquear un perfil de trabajo, retirar el asistente predeterminado y desinstalar la app de prueba. En Instinct, comprobá que un plan bloqueado espera confirmación y que revocar la autorización antes de confirmar impide el lanzamiento.

[LauncherApps](https://developer.android.com/reference/android/content/pm/LauncherApps) · [LauncherApps.ShortcutQuery](https://developer.android.com/reference/android/content/pm/LauncherApps.ShortcutQuery)
