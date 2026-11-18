# Insto 1.11.1 · vocabulario para la voz

Código de versión 20.

Groq recibe vocabulario breve para orientar a Whisper cuando reconoce nombres de apps, rutinas y lugares. Insto prepara la lista en segundo plano al abrir el panel y la guarda en memoria. Si todavía no está lista al enviar el audio, la transcripción sigue sin vocabulario. Un reintento de decisión reutiliza la transcripción.

## Qué se envía

Con el ajuste de vocabulario activo por defecto, Groq puede recibir hasta 600 caracteres junto con la copia del audio. El texto incluye términos fijos, nombres de rutinas, alias de lugares guardados, frases aprendidas habilitadas y etiquetas de apps lanzables. Los nombres del contacto fijado y del contacto de manejo solo se incluyen si activás su interruptor. Las direcciones, los números de teléfono y la libreta de contactos quedan en el teléfono. Se descartan términos con cinco dígitos o más y el vocabulario queda fuera de los registros.

## Verificación

- La suite de núcleo `python3 scripts/test-phone-core.py` pasó sus 283 pruebas. Incluye el orden y el recorte del vocabulario, el filtro de dígitos y controles, el opt-in de contactos y la presencia u omisión del campo `prompt` en el multipart.
- En el teléfono queda comparar los aciertos con el [protocolo de 20 frases](../VOICE-CONTEXT.md#comparar-el-reconocimiento-de-nombres-propios), medir la latencia y probar grabaciones con ambos interruptores en distintas combinaciones.
