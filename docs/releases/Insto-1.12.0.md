# Insto 1.12.0 (código 21)

Tres mejoras independientes. Sin permisos nuevos, sin cambios en `accessibility_service_config.xml` ni en los flujos de Groq, Gemini e Instinct.

## Frases aprendidas con valores y formas parecidas

- Una frase aprendida con un valor sirve con otros: «bajá la música al 30» resuelve «bajá la música al 50» en el teléfono. Admite porcentaje de volumen, duración y hora (estas dos solo en frases manuales) y nombre de app (debe coincidir con una sola app instalada). El valor se valida con el parser nativo.
- Reconoce voseo, tuteo, infinitivo, enclíticos, muletillas («che», «dale», «porfa») y una letra de diferencia en palabras seguras. Ante un empate o una ambigüedad no ejecuta nada.
- Las negaciones y condiciones nunca coinciden. También se rechazan «Ni se te ocurra…», «Dale, no, … no» y «… salvo que…».
- Medición offline con frases sembradas: local 1/38 → 21/38, llamadas a Decisions por pedido 0,89 → 0,26, falsos positivos en restricciones 3 → 0, y acciones locales indebidas y derivaciones incorrectas a Instinct en 0. El corpus de 155 pedidos no cambia (120/155). Ver [métricas](../metrics/intents-baseline.md) y [frases aprendidas](Insto-frases-aprendidas.md).

## Auto-envío con estrategias y diagnóstico

- Estrategias ordenadas para encontrar cada control de WhatsApp y WhatsApp Business. Ante dos candidatos no se toca nada, y «Enviar ubicación» ya no puede confundirse con «Enviar». Los eventos de WhatsApp adelantan la verificación.
- El panel indica «Auto-envío listo», «necesita revisión» o «Accesibilidad desactivada», según los envíos confirmados con la versión instalada. Si el estado no es «listo», ofrece abrir el chat y enviar a mano.
- Métricas locales sin contenido. Se pueden borrar en Configuración → WhatsApp y notas de voz. Ver [FLOATING-ASSISTANT.md](../FLOATING-ASSISTANT.md).

## Copia de seguridad y restauración

- Un archivo `.insto` cifrado con contraseña (AES-256-GCM, PBKDF2-HMAC-SHA256) con claves de API, contactos, ajustes, frases, rutinas y lugares. Se puede importar en otro teléfono, fusionando o reemplazando por categoría. Ver [detalles y límites](Insto-1.12.0-backup.md).

## Verificación

En este entorno, con el SDK de Android 36.1 instalado:

- `python scripts/test-phone-core.py`: 345 pruebas JVM aprobadas.
- `./gradlew compileDebugKotlin compileDebugUnitTestKotlin`: correcto.
- `./gradlew testDebugUnitTest`: 573 pruebas aprobadas (JVM, Robolectric y Compose).
- `./gradlew assembleDebug`: APK de depuración generado, con el keystore efímero que también crea el CI.

**Pendiente en el teléfono:**

- Enviar texto y audio con WhatsApp y WhatsApp Business reales, y verificar el indicador después de una actualización de WhatsApp.
- Exportar en un teléfono e importar en otro, o después de reinstalar, comprobando que Groq y Gemini responden sin volver a cargar las claves. Robolectric no implementa Android Keystore.
- Medir el tiempo de cifrado en el teléfono.
- Probar las frases con valores por voz.
