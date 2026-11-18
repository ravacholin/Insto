# Arquitectura del asistente flotante

## Envío por WhatsApp

El adaptador de Android lee la ventana activa o la ventana de WhatsApp enfocada.
Convierte su árbol de accesibilidad en un `UiNode` y usa
`WhatsAppScreenReader` para identificar el destinatario, el compositor y los
controles. `AutoSendCoordinator` registra el lanzamiento dirigido, las ventanas
que ya existían y la hora de preparación. Un evento de ventana se vuelve
utilizable cuando se registra el éxito del lanzamiento.

### Texto

```mermaid
stateDiagram-v2
    [*] --> EsperandoChat
    EsperandoChat --> EsperandoChat: evidencia insuficiente
    EsperandoChat --> Gesto: lanzamiento registrado, chat y borrador coinciden
    Gesto --> Verificando: gesto completado
    Gesto --> Abortado: gesto cancelado o rechazado
    Verificando --> Confirmado: compositor vacío del destinatario
    Verificando --> Abortado: 3 s sin confirmar
    Confirmado --> Retorno: si corresponde
```

### Audio

```mermaid
stateDiagram-v2
    [*] --> BuscandoVista
    BuscandoVista --> GestoOK: diálogo propio autorizado
    BuscandoVista --> GestoEnviar: vista propia autorizada
    GestoOK --> DespuesDeOK: gesto completado
    GestoEnviar --> DespuesDeEnviar: gesto completado
    DespuesDeOK --> GestoEnviar: Enviar en una superficie posterior
    DespuesDeEnviar --> GestoOK: OK en una superficie posterior
    DespuesDeOK --> VerificandoChat: compositor vacío y micrófono de chat
    DespuesDeEnviar --> VerificandoChat: compositor vacío y micrófono de chat
    VerificandoChat --> Confirmado: mismo chat estable 500 ms
    GestoOK --> Abortado: gesto cancelado o rechazado
    GestoEnviar --> Abortado: gesto cancelado o rechazado
    Confirmado --> Retorno: si corresponde
```

El registro del paso queda marcado antes de ejecutar cada gesto. El adaptador
comprueba el id de la solicitud antes de entregar resultados o volver a la app
anterior. Los adjuntos generales conservan su ventana de verificación de 3 s.
El audio grabado conserva el plazo de la solicitud dirigida.

## Capturar una pantalla en debug

Las pruebas consumen ejemplos sintéticos en
`app/src/test/resources/whatsapp/` basados en los selectores existentes.
Para guardar la próxima pantalla que el servicio inspeccione en un envío propio,
creá el marcador de captura y reproducí ese envío con un contacto de prueba.

```sh
adb shell run-as com.aistudio.pingassist.qvrtkm touch cache/capture-whatsapp-tree
adb exec-out run-as com.aistudio.pingassist.qvrtkm cat cache/whatsapp-tree.json > whatsapp-tree.json
```

El marcador se consume en una captura. El JSON privado conserva la estructura,
los IDs, las clases, los bounds, los flags y los nombres de controles incluidos
en una lista fija. Los nombres de destinatarios, archivos y textos de mensajes
deben representarse con valores de prueba al preparar el fixture.

## Sesión del asistente flotante

`MainViewModel` conserva la interfaz que usa Compose y delega el flujo flotante
a `FloatingSessionController`. El controlador administra el trabajo asíncrono,
envía sus resultados al reductor y proyecta el estado a la UI.
`PhoneCommandRunner` adapta el ejecutor local e `InstinctDispatcher` adapta
los envíos de texto, audio y adjuntos.

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Listening: abrir micrófono
    Listening --> Finalizing: listo, silencio o pausa
    Finalizing --> Transcribing: audio listo
    Finalizing --> Retained: audio conservado al pausar
    Transcribing --> Interpreting: transcripción disponible
    Idle --> Interpreting: enviar texto
    Interpreting --> AwaitingClarification: faltan datos
    Interpreting --> AwaitingConfirmation: revisar propuesta
    Interpreting --> AwaitingPermission: permiso solicitado
    Interpreting --> AwaitingChoice: hay varias opciones
    Interpreting --> Dispatching: consulta para Instinct
    Interpreting --> Done: acción local completada
    AwaitingClarification --> Interpreting: responder
    AwaitingConfirmation --> RunningWorkflow: confirmar secuencia
    AwaitingPermission --> Dispatching: permiso concedido
    AwaitingPermission --> Retained: permiso denegado
    AwaitingChoice --> Dispatching: opción elegida
    RunningWorkflow --> Done: secuencia finalizada
    Dispatching --> Done: entrega aceptada
    Dispatching --> Retained: error
    Transcribing --> Retained: pausa o error
    Interpreting --> Retained: pausa o error
    Retained --> Interpreting: reintentar con transcripción conservada
    Retained --> Transcribing: reintentar sin transcripción
    Interpreting --> Idle: edición o cierre
    Retained --> Idle: cierre
```

El identificador de sesión acompaña los efectos. El reductor descarta los
resultados de sesiones invalidadas y libera una captura tardía que ninguna
sesión conserva. El reintento consulta primero la transcripción conservada.
El estado persistido de una secuencia sigue bajo responsabilidad de
`WorkflowRunner`. La grabación de la pantalla principal queda en el ViewModel.
