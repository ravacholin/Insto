# Control local con OmniRemote

Insto 1.26.0 se enlaza con OmniRemote 2.0 mediante el servicio Binder de la API v1. Instalá las dos aplicaciones en el mismo perfil. La compilación de OmniRemote debe incluir la huella SHA-256 del certificado que firma el APK de Insto instalado, mediante la propiedad Gradle `instoCertificateSha256`. Para una versión distribuida por Google Play usá el certificado de Play App Signing. El botón **Configuración → OmniRemote → Probar conexión** comprueba esa autorización y consulta el catálogo sin enviar órdenes al dispositivo. [Contrato de OmniRemote](https://github.com/ravacholin/omniremote/blob/main/docs/EXTERNAL-CONTROL-API.md)

Agregá los dispositivos, emparejalos y configurá sus apps en OmniRemote. Insto trae el nombre, la habitación y las capacidades de cada dispositivo, así como los accesos directos de cada TV. En Configuración podés darles aliases y elegir un televisor predeterminado. La configuración local de Insto guarda los aliases y el ID elegido. Si borrás los datos de OmniRemote, revisá los destinos de las rutinas y programaciones porque los IDs pueden cambiar.

- «Prendé la Samsung» selecciona esa TV. Después de una orden enviada o verificada, «Abrí YouTube» y «Bajá el volumen» usan el destino reciente durante dos minutos. «Bajá el volumen del celular» usa los controles del teléfono. Fuera de ese contexto, un pedido genérico de volumen sigue siendo del teléfono.
- «Abrí Netflix en la tele» usa la TV reciente, la predeterminada o la única TV disponible. Sin un TV inequívoco, indicá el nombre, la habitación o un alias. Una apertura sin destino puede usar el TV predeterminado si anuncia esa app.
- «Prendé la Samsung y la FlowBox», «Apagá todos los televisores» y «Prendé la Samsung y prendé la linterna» preparan una secuencia para revisar. Cada orden se despacha en su turno y conserva su propio resultado.
- «Apagá la tele en 30 minutos», «Apagá la tele a las 23:30» y «Cancelá el apagado de la tele» actúan sobre el temporizador de OmniRemote. Este dispone de un solo temporizador compartido y los nuevos horarios requieren el permiso de alarmas exactas. «Silenciá» usa mute explícito donde se anuncia. En Samsung envía la tecla de alternar silencio y muestra que el nuevo estado sigue sin confirmar.
- «Pulsá la tecla HOME en la Samsung» admite cualquier tecla anunciada por ese equipo. «Escribí “hola” en la tele» conserva el texto citado. Las operaciones de TVDimmer y audio Bluetooth dependen de la configuración y el modelo. Para medios de Mac, primero consultá las fuentes de medios. Los IDs de fuente pueden cambiar cuando se cierran las apps o pestañas.

## Instinct y rutinas

El catálogo vivo del buzón firmado publica las capacidades y el inventario de OmniRemote, con IDs `omni:<deviceId>` y los IDs de apps por dispositivo. Los planes JSON usan los campos publicados por cada capacidad. El permiso de Autonomía corresponde a **Aplicaciones**. Los pasos con una dependencia explícita requieren que el resultado anterior esté verificado. Un envío sin verificación queda registrado como enviado y permite continuar con los pasos independientes.

Los pasos tipados se pueden combinar con acciones del teléfono en workflows y en rutinas configuradas. Instinct admite órdenes de un solo destino por línea, con los mismos controles de Autonomía. Por ejemplo, «todos los días a las 22:30 hasta el 03/12: apagá la Samsung» fija el ID al programar la serie. Cada disparo tiene un ID de ejecución propio. Los aliases y las recurrentes ligadas a OmniRemote quedan en el dispositivo.

## Resultados y Android 16

OmniRemote puede verificar una observación, acusar envío o indicar un resultado incierto. Un envío de encendido, tecla o apertura de app puede surtir efecto sin que el proveedor lo pueda confirmar. En estado de Android TV se muestran solo los campos observados, incluido el nivel de volumen efectivo aunque haya redondeo. Al perder Binder, Insto vuelve a consultar el ID original. La recuperación nunca vuelve a enviar una operación incierta.

El enlace es explícito y dura lo necesario para consultar o ejecutar. Con la pantalla apagada o bloqueada funciona después del primer desbloqueo, mientras Android mantenga el trabajo y OmniRemote tenga acceso a la red local. Antes del primer desbloqueo, los datos protegidos por el usuario siguen inaccesibles. Si la protección de red local de Android 16 está habilitada, otorgá **Dispositivos cercanos** a OmniRemote en los ajustes del sistema. El emparejamiento y los permisos de control iniciales se configuran en OmniRemote.

Para comprobar la integración física, vinculá Insto con la firma de distribución, probá una TV distinta de la seleccionada en OmniRemote y repetí con el teléfono bloqueado. Interrumpí el enlace durante una orden relativa y comprobá que se consulte la misma ejecución. Un APK con otra firma debe recibir un rechazo de autorización.
