"""Builds simulated capture sessions from real speech and runs the real WebRTC VAD on each one.

Speech: LibriVox public-domain readings (two speakers). Each session is spliced audio
(leading floor, speech, optional hesitation, trailing floor) run through a fresh VAD,
as the app does per session. Only per-frame decisions are exported, never audio.

Usage (outside the Android build; needs numpy and webrtcvad, e.g. the webrtcvad-wheels package):

    curl -LO https://archive.org/download/cuentosdelaselva_1603_librivox/cuentosdelaselva_01_quiroga_64kb.mp3
    curl -LO https://archive.org/download/cuentos_1910_librivox/cuentos_01_aulnoy_64kb.mp3
    ffmpeg -i cuentosdelaselva_01_quiroga_64kb.mp3 -t 600 -ac 1 -ar 16000 -f s16le a.pcm
    ffmpeg -i cuentos_01_aulnoy_64kb.mp3 -t 600 -ac 1 -ar 16000 -f s16le b.pcm
    python scripts/voice/build-vad-traces.py a.pcm b.pcm app/src/test/resources/vad-traces-es.json

The random seed is fixed, so the same audio produces the same traces.
"""
import json, sys
import numpy as np
import webrtcvad

FRAME = 320
rng = np.random.default_rng(20261120)
paths, out = sys.argv[1:-1], sys.argv[-1]
speakers = [np.fromfile(p, dtype="<i2").astype(np.float64) for p in paths]

def frames(x):
    n = len(x) // FRAME
    return x[: n * FRAME].reshape(n, FRAME)

def raw(x, mode):
    v = webrtcvad.Vad(mode)
    pcm = np.clip(x, -32768, 32767).astype("<i2")
    return "".join("1" if v.is_speech(f.tobytes(), 16000) else "0" for f in frames(pcm))

def subphrases(x):
    """Reference speech units: mode 0 on the clean original, gaps >= 100 ms split units."""
    bits = [c == "1" for c in raw(x, 0)]
    units, start, last = [], None, None
    for i, b in enumerate(bits):
        if not b: continue
        if start is None: start = i
        elif i - last > 5: units.append((start, last)); start = i
        last = i
    units.append((start, last))
    return [u for u in units if u[1] - u[0] >= 4]

units = [subphrases(s) for s in speakers]

def speech_slice(min_ms, max_ms):
    """Consecutive reference units of one speaker, starting and ending on a unit boundary."""
    for _ in range(1000):
        k = rng.integers(len(speakers)); us = units[k]
        i = rng.integers(len(us) - 40)
        # Do not start right after a short gap: the start must be a real onset.
        if i > 0 and us[i][0] - us[i - 1][1] < 15: continue
        j = i
        while j + 1 < len(us) and (us[j + 1][1] - us[i][0]) * 20 <= max_ms and us[j + 1][0] - us[j][1] < 25:
            j += 1
        dur = (us[j][1] - us[i][0] + 1) * 20
        if min_ms <= dur <= max_ms:
            a, b = us[i][0] * FRAME, (us[j][1] + 1) * FRAME
            return speakers[k][a:b]
    raise RuntimeError("no slice")

def floor(ms, rms):
    return rng.normal(0, rms, 16 * ms)

CONDITIONS = {  # gain dB, added noise rms (both speech and floor)
    "clean": (0, 0), "soft": (-20, 0), "soft_noisy": (-20, 60), "noisy": (0, 300),
}
ROOM = 25  # quiet room floor, always present

def session(parts, cond):
    gain, noise = CONDITIONS[cond]
    audio, spans, t = [], [], 0
    for kind, x in parts:
        if kind == "speech":
            x = x * 10 ** (gain / 20)
            spans.append((t, t + len(x)))
        audio.append(x); t += len(x)
    a = np.concatenate(audio)
    a = a + rng.normal(0, ROOM, len(a)) + (rng.normal(0, noise, len(a)) if noise else 0)
    return a, [(s // FRAME, (e - 1) // FRAME) for s, e in spans]

def event(name):
    x = floor(0, 0)
    if name == "click":
        x = rng.normal(0, 12000, 80)
    elif name == "knock":
        x = np.concatenate([9000 * np.exp(-np.arange(1600) / 200) * np.sin(2 * np.pi * 180 * np.arange(1600) / 16000)
                            for _ in range(1)])
    elif name == "double_knock":
        k = 9000 * np.exp(-np.arange(1600) / 200) * np.sin(2 * np.pi * 180 * np.arange(1600) / 16000)
        x = np.concatenate([k, np.zeros(1600), k])
    elif name == "beep":
        x = 6000 * np.sin(2 * np.pi * 1000 * np.arange(3200) / 16000)
    elif name == "burst":
        x = rng.normal(0, 7000, 4000) * np.hanning(4000)
    return x

sessions = []
def add(kind, cond, parts, expect_send=True):
    audio, spans = session(parts, cond)
    sessions.append({"kind": kind, "condition": cond, "speech": spans, "expectSend": expect_send,
                     "frames": len(audio) // FRAME,
                     "mode2": raw(audio, 2), "mode3": raw(audio, 3)})

for cond in CONDITIONS:
    for _ in range(12):  # brief command
        add("short", cond, [("floor", floor(int(rng.integers(300, 1500)), 0)), ("speech", speech_slice(500, 1800)),
                            ("floor", floor(4000, 0))])
    for _ in range(8):  # long command
        add("long", cond, [("floor", floor(int(rng.integers(300, 1500)), 0)), ("speech", speech_slice(4000, 9000)),
                           ("floor", floor(4000, 0))])
    for pause in (600, 900, 1200):  # hesitation in the middle of a command
        for _ in range(5):
            add(f"pause{pause}", cond, [("floor", floor(int(rng.integers(300, 1500)), 0)), ("speech", speech_slice(600, 2000)),
                                        ("floor", floor(pause, 0)), ("speech", speech_slice(400, 1500)),
                                        ("floor", floor(4000, 0))])
    for _ in range(4):  # nothing said: initial window must expire without a send
        add("silence", cond, [("floor", floor(6000, 0))], expect_send=False)
for name in ("click", "knock", "double_knock", "beep", "burst"):
    for cond in ("clean", "noisy"):
        for _ in range(3):
            add(f"noise_{name}", cond, [("floor", floor(int(rng.integers(300, 2000)), 0)), ("noise", event(name)),
                                        ("floor", floor(5000, 0))], expect_send=False)

json.dump({"source": "LibriVox public-domain Spanish readings (Quiroga; d'Aulnoy), WebRTC VAD via py-webrtcvad 2.0.14, 16 kHz, 20 ms frames",
           "sessions": sessions}, open(out, "w"), separators=(",", ":"))
print(len(sessions), "sessions")
