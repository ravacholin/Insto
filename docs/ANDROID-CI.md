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
