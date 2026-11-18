#!/usr/bin/env python3
"""Collect completed Insto debug voice timings over ADB, without audio or transcripts."""
import argparse
import csv
import math
import re
import shutil
import subprocess
import sys
import uuid

FIELDS = ("endpoint_ms", "finalization_ms", "transcription_ms", "interpretation_ms")
CONNECTION_FIELDS = ("groq_connection_ms", "gemini_connection_ms")
CONNECTION = re.compile(r"^(Groq|Gemini)_connection_ms=(\d+)$")
STAGES = ("Silencio", "Audio", "Transcripción", "Interpretación")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Serial ADB si hay varios teléfonos")
    parser.add_argument("--samples", type=int, default=10, help="Pedidos completos a medir (10 por defecto)")
    parser.add_argument("--output", default="insto-voice-latency.csv", help="Archivo CSV de salida")
    args = parser.parse_args()
    if args.samples < 1:
        parser.error("--samples debe ser mayor que cero")
    if not shutil.which("adb"):
        parser.error("Instalá Android Platform Tools y agregá adb a PATH")
    command = ["adb"] + (["-s", args.serial] if args.serial else [])
    if subprocess.run(command + ["get-state"], stdout=subprocess.DEVNULL).returncode:
        parser.error("Conectá un teléfono o emulador con ADB antes de medir")
    records = []
    connections = {field: [] for field in CONNECTION_FIELDS}
    logcat = subprocess.Popen(command + ["logcat", "-v", "raw", "-T", "1", "InstoVoiceLatency:D", "*:S"],
                              stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    print("Abrí el flotante y hablá. Ctrl+C termina antes; solo se guardan tiempos.", flush=True)
    marker = "insto-measure-start-" + uuid.uuid4().hex
    started = False
    try:
        subprocess.run(command + ["shell", "log", "-p", "d", "-t", "InstoVoiceLatency", marker], check=True)
        with open(args.output, "w", newline="", encoding="utf-8") as output:
            writer = csv.writer(output)
            writer.writerow((*FIELDS, "total_ms", *CONNECTION_FIELDS))
            pending = {field: [] for field in CONNECTION_FIELDS}
            for line in logcat.stdout:
                if marker in line:
                    started = True
                    continue
                if not started:
                    continue  # Discard buffered measurements from before this collection.
                connection = CONNECTION.fullmatch(line.strip())
                if connection:
                    provider, millis = connection.groups()
                    pending[f"{provider.lower()}_connection_ms"].append(int(millis))
                    continue
                values = [re.search(re.escape(stage) + r": (\d+) ms", line) for stage in STAGES]
                if not values[-1]:
                    continue  # Ignore intermediate stage updates.
                timings = [int(match.group(1)) if match else None for match in values]
                total = sum(value for value in timings if value is not None)
                writer.writerow((*timings, total,
                                 *(" ".join(map(str, pending[field])) for field in CONNECTION_FIELDS)))
                for field in CONNECTION_FIELDS:
                    connections[field].extend(pending[field])
                    pending[field].clear()
                output.flush()
                records.append((*timings, total))
                print(f"Medición {len(records)}/{args.samples}: {line.strip()}", flush=True)
                if len(records) >= args.samples:
                    break
    except KeyboardInterrupt:
        pass
    finally:
        logcat.terminate()
        try:
            logcat.wait(timeout=5)
        except subprocess.TimeoutExpired:
            logcat.kill()
            logcat.wait()
    if not records:
        print("No se recibieron mediciones completas. Usá una compilación debug y verificá Groq/Gemini.", file=sys.stderr)
        return 1
    for index, field in enumerate((*FIELDS, "total_ms")):
        values = sorted(record[index] for record in records if record[index] is not None)
        if values:
            percentile = lambda p: values[max(0, math.ceil(len(values) * p) - 1)]
            print(f"{field}: p50={percentile(.50)} ms, p95={percentile(.95)} ms, n={len(values)}")
    for field, samples in connections.items():
        if samples:
            values = sorted(samples)
            p50 = values[math.ceil(len(values) * .50) - 1]
            p95 = values[math.ceil(len(values) * .95) - 1]
            print(f"{field}: p50={p50} ms, p95={p95} ms, n={len(values)}")
    print(f"CSV: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
