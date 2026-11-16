#!/bin/sh
# Read-only: resolves exported stopwatch activities without starting or resetting them.
set -eu
if ! command -v adb >/dev/null 2>&1; then
  echo 'ADB no está instalado. Instalá Android Platform Tools y volvé a ejecutar este script.' >&2
  exit 1
fi
if [ "$#" -gt 1 ]; then
  echo 'Uso: scripts/check-google-clock.sh [serial-del-dispositivo]' >&2
  exit 1
fi
adb_clock() {
  if [ "$#" -eq 0 ]; then return 1; fi
  if [ -n "${clock_serial:-}" ]; then adb -s "$clock_serial" "$@"; else adb "$@"; fi
}
clock_serial=${1:-}
adb_clock get-state >/dev/null
printf 'Reloj de Google instalado:\n'
adb_clock shell dumpsys package com.google.android.deskclock | sed -n '/versionCode=/p; /versionName=/p'
for operation in SHOW START PAUSE RESET; do
  printf '\n%s_STOPWATCH — handlers disponibles:\n' "$operation"
  adb_clock shell cmd package query-activities --brief --components --query-flags 65536 \
    -a "com.android.deskclock.action.${operation}_STOPWATCH" \
    -p com.google.android.deskclock
done
printf '\nEste diagnóstico no inició, pausó ni reinició ningún cronómetro.\n'
printf 'Los permisos de un handler aún deben comprobarse desde Insto; ADB usa otra identidad.\n'
