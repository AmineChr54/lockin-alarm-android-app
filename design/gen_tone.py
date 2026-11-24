"""Synthesizes the app's own fallback alarm tone (two-note beep pattern, loops cleanly)."""
import math, struct, sys, wave

RATE = 22050
out = []
for freq, on in [(880, 0.16), (0, 0.06), (1175, 0.16), (0, 0.06), (880, 0.16), (0, 0.06), (1175, 0.3), (0, 0.5)]:
    n = int(RATE * on)
    for i in range(n):
        env = min(1, i / 150, (n - i) / 150)
        t = i / RATE
        s = math.sin(2 * math.pi * freq * t) + 0.35 * math.sin(6 * math.pi * freq * t) if freq else 0
        out.append(int(max(-1, min(1, s * 0.7 * env)) * 32000))
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes(struct.pack(f"<{len(out)}h", *out))
