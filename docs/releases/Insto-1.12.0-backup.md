# Insto 1.12.0 · copia de seguridad y restauración

**Configuración → Copia de seguridad y restauración** guarda toda la configuración de Insto en un archivo cifrado con una contraseña y la restaura en este u otro teléfono, incluso después de reinstalar Insto. Todo ocurre en el teléfono: sin servidores, sin Gemini y sin Groq. El archivo se guarda y se elige con el selector de documentos de Android, sin permisos de almacenamiento.

## Qué incluye

| Categoría | Contenido | Almacenamiento de origen |
| --- | --- | --- |
| Claves de API y proveedores | Clave de Groq, clave de Gemini, Gemini Decisions activado o no, elección «seguir en local» | `GroqKeyStore` y `GeminiSettings` (Android Keystore + `noBackupFilesDir`) |
| Contactos y ajustes del asistente | Contacto de Instinct y de conducción, modo de activación, botón principal, WhatsApp preferido, modo de grabación, botón de volumen, botón flotante, señales sonoras, volver a la app anterior, voz en el auto o con Bluetooth, horario nocturno y vocabulario de Groq | DataStore `ping_assist_prefs` |
| Frases aprendidas | Frases manuales y automáticas con su acción, origen, usos, texto escrito, interruptor «otros valores», estructuras aprendidas e interruptor «Aprender y usar frases» | DataStore `learned_phrases` |
| Rutinas | Rutinas configuradas | `configured-routines.json` |
| Lugares guardados | «casa» y «trabajo» | SharedPreferences `saved_places` |

**No incluye:** audios, transcripciones, mensajes, capturas, historial de actividad, la última frase usada, secuencias en curso, modos temporales activos ni métricas del auto-envío. Tampoco permisos, el rol de asistente, Accesibilidad, el acceso a notificaciones o a No molestar ni el permiso para modificar ajustes. Android exige activarlos a mano en cada teléfono. Al terminar, el informe dice cuáles faltan.

## Cómo se protege

- El archivo `insto-backup-AAAA-MM-DD.insto` está cifrado con **AES-256-GCM**. La clave se deriva de tu contraseña con **PBKDF2-HMAC-SHA256** (600 000 iteraciones y sal aleatoria de 16 bytes). Cada exportación usa un nonce nuevo de 12 bytes. La cabecera (formato, versión, iteraciones, sal y nonce) está autenticada: cualquier cambio se detecta antes de leer el contenido.
- La contraseña no se guarda. **Si la perdés, la copia no se puede recuperar.** Mínimo 8 caracteres.
- Las claves de API se leen con los almacenes seguros de Insto solo mientras se arma el archivo, y se escriben directo dentro del contenido cifrado. No se usan archivos temporales. En el teléfono de destino se guardan con su propio Android Keystore. Las vistas previas y los mensajes nunca muestran una clave.
- **Exportar sin claves** usa el mismo cifrado y la misma contraseña, porque el archivo igual tiene teléfonos y direcciones. Esa copia no restaura Groq ni Gemini.
- Una contraseña incorrecta y un archivo alterado dan el mismo mensaje: el cifrado no permite distinguirlos.

## Cómo se importa

1. **Importar configuración** abre el selector de documentos. Cancelar no cambia nada.
2. Insto revisa el archivo completo antes de cambiar algo. Rechaza los archivos de más de 1 MiB, los incompletos, los alterados, los de una versión más nueva y los que no son copias de Insto. Una categoría dañada, repetida o creada por una versión más nueva se muestra como no disponible, y las demás se pueden restaurar igual.
3. La vista previa muestra categorías y cantidades. Ahí elegís qué restaurar y, en cada categoría, si querés **Fusionar** o **Reemplazar**. Antes de confirmar se ve qué se agrega, qué se sobrescribe y qué se borra.
4. El informe final dice qué se recuperó y qué necesita atención.

**Fusionar** conserva lo que ya tenés:

- Una clave existente distinta se mantiene, salvo que marques «Reemplazar la clave … actual». Una clave que falta se restaura.
- Los ajustes y lugares solo completan lo que no estaba configurado. Un contacto distinto se conserva y se avisa.
- Una frase que agregaste en este teléfono nunca se reemplaza. Una frase tuya del respaldo reemplaza a una aprendida sola con el mismo texto, y entre dos aprendidas solas gana la más reciente. Las estructuras con la misma forma suman sus ejemplos y mantienen el interruptor local.
- Las rutinas con el mismo nombre se conservan. Si en total quedan más de 50, la fusión se rechaza antes de escribir.

**Reemplazar** usa lo del respaldo solo en las categorías elegidas. Un campo que falta en el archivo no borra nada. Un ajuste que estaba sin configurar al exportar vuelve a su valor predeterminado, con aviso previo. Las claves de API nunca se borran por faltar en el respaldo.

Importar no ejecuta comandos, no envía mensajes, no arma el auto-envío, no llama a proveedores y no reinicia servicios. Los ajustes se ven enseguida, sin reiniciar el teléfono. Si importás «botón flotante: siempre visible» y Accesibilidad está activada, la burbuja aparece, como si lo hubieras elegido en Configuración.

## Límites conocidos

- **La restauración no es atómica entre almacenes.** Se aplica por partes: lugares, rutinas, frases, ajustes y, al final, claves. Antes de escribir se valida todo y se toma en memoria una instantánea de cada categoría elegida. Si una parte falla, las ya escritas vuelven a su estado anterior. Si el sistema cierra Insto en medio de la restauración (por batería o porque lo mata), esas instantáneas se pierden: guardarlas en disco obligaría a escribir las claves sin cifrar. Para ese caso queda una marca sin datos, y Configuración avisa al volver a abrir: «La última restauración se interrumpió».
- En `GeminiSettings`, la clave y el interruptor de Decisions son dos archivos. Un corte entre ambos deja la clave con Decisions apagado, que es el estado seguro.
- En la JVM de la máquina de pruebas, la derivación de la clave tarda unos 170 ms. En el teléfono va a tardar más; no se midió.
- Kotlin no permite borrar de la memoria el texto de una clave (`String`). Los buffers de bytes y la contraseña (`CharArray`) sí se ponen en cero después de usarlos.
- La copia de frases en JSON que ya existía en Frases aprendidas sigue funcionando. Esos archivos también se pueden importar desde esta sección, como una copia parcial sin claves.

## Verificación

- `BackupCryptoTest` (JVM): vectores de PBKDF2-HMAC-SHA256 de la RFC 7914, comparación con `SecretKeyFactory` de la JVM usando contraseñas no ASCII, ida y vuelta, contraseña incorrecta, archivo truncado, byte alterado en cabecera, nonce y texto cifrado, iteraciones fuera de rango, versión futura y tamaño.
- `ConfigurationBackupManagerTest` (JVM, almacenes en memoria): teléfono A → respaldo → teléfono B limpio, con claves; exportar sin claves; fusionar sin perder valores; consentimiento para reemplazar claves; reemplazar solo las categorías elegidas; falla de escritura en la última etapa con reversión de todas; contenido inválido, repetido, malformado o futuro por categoría; esquema desconocido o futuro; migraciones de esquema; archivo de frases de 1.11; más de 50 rutinas; ningún secreto en planes, vistas previas, informes ni archivos; marca de restauración interrumpida.
- `BackupRestoreFlowTest` (Robolectric, almacenes reales): exportar, vaciar e importar DataStore, SharedPreferences y rutinas; la frase con valor sigue generalizando; no se inicia ninguna actividad ni se arma nada; fusión de frases manuales.
- `BackupScreenTest` (Compose): contraseñas, selector cancelado, éxito informado recién después de escribir, error de escritura y aviso de restauración interrumpida.

**Pendiente de verificación física:** exportar en un teléfono, importar en otro (o después de reinstalar) y comprobar que Groq y Gemini responden sin volver a cargar las claves. Robolectric no implementa Android Keystore, así que el camino real de `GroqKeyStore` solo se prueba en un dispositivo.
