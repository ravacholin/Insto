# Comprobaciones de Android en GitHub

El workflow **Android tests** ejecuta `testDebugUnitTest` y `assembleDebug` en
cada PR y push a `main`. También se puede iniciar desde **Actions → Android
tests → Run workflow**. `lintDebug` corre como paso separado y bloquea el check
cuando existe `app/lint-baseline.xml`. El reporte JaCoCo es informativo y se
publica como `android-unit-test-coverage` cuando se pudo generar.

## Exigir el check para fusionar a main

1. Esperá a que **Android tests** se ejecute en un PR reciente para que GitHub
   ofrezca el check en la lista.
2. En **Settings → Branches**, creá o editá la regla de protección de `main`.
3. Activá **Require status checks to pass before merging** y seleccioná el check
   **test** del workflow **Android tests**, con GitHub Actions como origen.
4. Guardá la regla. En un PR hacia `main`, verificá que el check figure como
   requerido en la sección de merge.

## Verificación local sin el workflow

Con el Android SDK (`platforms;android-36.1`, `build-tools;36.0.0`) en `local.properties`, la misma clave de firma
debug efímera que crea el workflow y Java 21:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
python3 scripts/test-phone-core.py   # pruebas JVM sin Android, incluidas red y sesiones puras
```

La compilación release necesita la clave de distribución. Para verificarla sin ella, sin tocar la configuración,
alcanza con una clave efímera indicada por las variables que ya lee el build (`KEYSTORE_PATH`, `STORE_PASSWORD`,
`KEY_PASSWORD`); el APK resultante no es distribuible. Ver
[`architecture/reliability-refactor.md`](architecture/reliability-refactor.md) para las pruebas nuevas y las que
requieren un dispositivo.

## Firma de distribución de Insto

El workflow manual **Android release firmado** se ejecuta desde `main`.
Comprueba el certificado antes de compilar y verifica la firma del APK
antes de guardar el artefacto `insto-release-apk`.

El SHA-256 del certificado es
`5D:58:24:AD:BB:2B:49:03:91:4F:5E:9F:83:6C:F4:4C:6B:DE:93:DB:56:83:1D:7E:E8:19:C4:01:6C:A4:1C:5F`.
Usá esa huella si OMNIREMOTE necesita autorizar el certificado de Insto.
Los APK firmados con este certificado pueden actualizarse entre sí
si el identificador de la app coincide y la versión permite la actualización.

Configurá una sola vez los secretos de repositorio de GitHub Actions
`INSTO_RELEASE_KEYSTORE_B64` y `INSTO_RELEASE_KEYSTORE_PASSWORD`. En una
terminal autenticada con permisos de administración del repositorio, desde
la carpeta privada que contiene el keystore y su contraseña:

```sh
base64 < insto-release.p12 | gh secret set INSTO_RELEASE_KEYSTORE_B64 --repo ravacholin/Insto
gh secret set INSTO_RELEASE_KEYSTORE_PASSWORD --repo ravacholin/Insto < passphrase
```

Guardá el keystore cifrado y su contraseña por separado. La compilación
local de distribución usa el mismo archivo mediante `KEYSTORE_PATH` y
la misma contraseña para `STORE_PASSWORD` y `KEY_PASSWORD`.
