#!/usr/bin/env python3
"""Deterministically generates the Watch alert notification sound.

Two short sine-wave notes (a rising perfect fourth, C6 -> F6) with a soft
attack/decay envelope, no noise or randomness anywhere. Regenerating this
script produces byte-identical output every time. Kept short and unhurried
on purpose: a watch is a background check, not an alarm, and the sound
should read as a calm notice, not an interruption. See docs on why: short,
melodic, on-brand chimes read as calm; sharp buzzes read as urgent.
"""
import math
import struct
import wave

SAMPLE_RATE = 44100
AMPLITUDE = 0.28  # headroom so the two overlapping notes never clip

# A rising perfect fourth: C6 to F6. Consonant and gentle, not a fanfare.
NOTE_A_HZ = 1046.50  # C6
NOTE_B_HZ = 1396.91  # F6

NOTE_A_START = 0.0
NOTE_A_DURATION = 0.32
NOTE_B_START = 0.16  # overlaps the tail of the first note, reads as one phrase
NOTE_B_DURATION = 0.38

TOTAL_DURATION = 0.60


def envelope(t: float, duration: float, attack: float = 0.02, release: float = 0.20) -> float:
    """Linear attack, linear release, flat in between. No clicks at either edge."""
    if t < 0 or t > duration:
        return 0.0
    if t < attack:
        return t / attack
    if t > duration - release:
        return max(0.0, (duration - t) / release)
    return 1.0


def note_sample(t: float, start: float, duration: float, freq: float) -> float:
    local_t = t - start
    if local_t < 0 or local_t > duration:
        return 0.0
    return AMPLITUDE * envelope(local_t, duration) * math.sin(2 * math.pi * freq * local_t)


def generate() -> bytes:
    total_frames = int(SAMPLE_RATE * TOTAL_DURATION)
    frames = bytearray()
    for i in range(total_frames):
        t = i / SAMPLE_RATE
        sample = note_sample(t, NOTE_A_START, NOTE_A_DURATION, NOTE_A_HZ)
        sample += note_sample(t, NOTE_B_START, NOTE_B_DURATION, NOTE_B_HZ)
        clamped = max(-1.0, min(1.0, sample))
        frames += struct.pack("<h", round(clamped * 32767))
    return bytes(frames)


def main() -> None:
    import pathlib
    out_dir = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/raw"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "watch_alert.wav"
    with wave.open(str(out_path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(SAMPLE_RATE)
        wav_file.writeframes(generate())
    print(f"wrote {out_path} ({out_path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
