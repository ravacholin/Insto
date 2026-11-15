# Adjuntos en el floating

## Objetivo y comportamiento acordado
Agregar un botón «+» al floating existente para adjuntar imágenes o documentos al mensaje dirigido al contacto configurado de Instinct. Seleccionar un archivo no lo envía: el usuario puede escribir texto y toca «Enviar» cuando está listo.

## Enfoques
Se consideró enviar al seleccionar o conservar el adjunto en el compositor. El usuario eligió conservarlo hasta tocar «Enviar» para poder escribir un mensaje además. Se propone reutilizar el selector nativo de Android y el envío dirigido de WhatsApp, ampliándolo para archivos con texto.

## Diseño propuesto
- «+» junto al micrófono abre el selector nativo de archivos, que permite elegir una imagen o un documento por mensaje.
- Abrir el selector detiene la escucha; cancelar vuelve al borrador sin enviar nada.
- El floating muestra el nombre del adjunto y una opción para quitarlo. Se conserva el texto al elegir, reemplazar o quitar un archivo.
- «Enviar» se habilita con un archivo aunque el texto esté vacío. Con texto y archivo, se comparten ambos a WhatsApp en la misma operación, usando el texto como mensaje o descripción según el formato que admita WhatsApp.
- Mientras hay un adjunto, el micrófono queda deshabilitado para evitar un envío automático del borrador.
- Se reutilizan el contacto resuelto al abrir el floating, la preferencia de WhatsApp y el retorno a la aplicación anterior.
- La automatización verifica destinatario y adjunto antes de pulsar enviar; si no puede verificarlos, requiere completar el envío en WhatsApp. No reintenta automáticamente un envío incierto.
- Un fallo local conserva el borrador y muestra un error.

## Alcance y validación
Sin cambios en los disparadores ni en el envío de texto o audio sin adjuntos. Una selección por mensaje es una decisión propuesta de implementación. Verificar selección/cancelación, texto más adjunto, quitar/reemplazar, errores, permisos de URI y destinatario con pruebas automatizadas y compilación. El envío efectivo de imágenes/documentos y las variantes de interfaz de WhatsApp requieren validación en un dispositivo Android con WhatsApp.
