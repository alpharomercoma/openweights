"""Compose an original cue for the YouTube cut of the listing video, and mux it in.

    python3 play/graphics/score.py

Why this file exists, and why the Play cut still has none.

Play does not host the mp4. The listing field takes a YouTube URL and plays that video
inline and muted, so "silent on Play, scored on YouTube" is not two versions of one upload:
it is two uploads, and the silent one is the one Play points at. Keeping them separate is
what lets this file exist at all. A Content ID claim can enable monetisation on a video, and
Play requires ads off on a listing video, so a claimed track on the linked upload could put
the listing out of compliance. `listing-promo.mp4` therefore stays silent and stays the
Play upload. This produces a second file for the channel.

Every sample here is synthesised from oscillators and shaped noise in this script. There are
no samples, no loops and no library source, which is the only way to say something honest
about rights: an original composition is still copyrighted, and it is copyrighted to its
author, so what "no copyright" can actually mean is no third-party material and nothing for
Content ID to match against. That is what this is.

On what it is NOT. The brief asked for an enterprise keynote bed, hype and applause. Two
reviewers were asked independently and both refused the same two things for the same
reasons. Applause invents an audience that does not exist, which is the argument this
project already accepted when it rejected UI click foley from this video: invented events
are the audio equivalent of staging footage, and a screencast with canned applause reads to
this audience as parody rather than as confidence. A corporate hype bed fails differently:
the video's whole argument is unembellished evidence, real capture and real numbers, and
scoring it like a launch keynote tells the viewer to distrust exactly the thing it is
proving. What both reviewers proposed instead, in nearly the same words, is what is here:
a sparse, precise electronic pulse, quiet computational momentum rather than triumph.

The arrangement is cut to the edit. 75 BPM in 4/4 puts a bar at exactly 3.2 s, which is the
length of the opening shot, and eight bars at exactly 25.6 s, which is the length of the
film. Four of the seven cuts land on a bar line and the rest are left where they fall,
because forcing an edit onto a beat is how a score starts steering the picture.

    bar 1  0.0   pulse from the first frame, no intro
    bar 2  3.2   the sequence enters as the answer begins to write itself
    bar 3  6.4   a high tick, near the telemetry beat
    bar 4  9.6   the pad opens under the model-fit cards
    bar 5  12.8  the pulse firms up under the download
    bar 6  16.0   stripped back to pulse and pad for "no folder shared"
    bar 7  19.2  the sequence widens for the tool switches
    bar 8  22.4  and at 23.0, on the end card, the pulse stops dead

There is no crescendo into the end card. "No account" is a statement, so it lands as a
single low hit and a chord resolving to the tonic, decaying into the last frame.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "play/videos/listing-promo.mp4"
OUT = ROOT / "play/videos/listing-promo-scored.mp4"
WAV = ROOT / "play/videos/listing-promo-score.wav"

SR = 48_000
BPM = 75.0
BEAT = 60.0 / BPM          # 0.8 s
BAR = 4 * BEAT             # 3.2 s
DUR = 8 * BAR              # 25.6 s
N = int(DUR * SR)
T = np.arange(N) / SR

END_CARD = 23.0            # where the picture cuts to the mark
PAD_IN = 3 * BAR           # 9.6 s, the model-fit cards

# D minor. The progression moves once every two bars and returns home for the end card,
# so the last thing heard is the chord the first thing heard implied.
def hz(semitones_above_a4: float) -> float:
    return 440.0 * 2 ** (semitones_above_a4 / 12.0)


D2, D3, D4 = hz(-31), hz(-19), hz(-7)
F3, F4 = hz(-16), hz(-4)
A2, A3, A4 = hz(-24), hz(-12), hz(0)
BB2, BB3 = hz(-23), hz(-11)
C3, C4 = hz(-21), hz(-9)
E4 = hz(-5)
G3 = hz(-14)

# (start bar, root for the bass, triad for the pad)
PROGRESSION = [
    (0, D2, (D3, F3, A3)),      # Dm
    (2, BB2, (BB3, D4, F4)),    # Bb
    (4, hz(-28), (F3, A3, C4)),  # F
    (6, C3, (C4, E4, G3 * 2)),  # C, leaning home
]


def _env(length: int, attack: float, decay: float) -> np.ndarray:
    """A percussive envelope: a short linear attack so nothing clicks, then an exponential
    tail. Exponential rather than linear because a linear release on a sine reads as the
    note being switched off rather than as it stopping."""
    t = np.arange(length) / SR
    a = np.clip(t / max(attack, 1e-6), 0, 1)
    d = np.exp(-t / max(decay, 1e-6))
    return a * d


def _add(buf: np.ndarray, at: float, sig: np.ndarray) -> None:
    i = int(at * SR)
    if i >= len(buf) or i < 0:
        return
    n = min(len(sig), len(buf) - i)
    buf[i:i + n] += sig[:n]


def _fade(x: np.ndarray, seconds: float = 0.012) -> np.ndarray:
    """Both ends of every voice, so no partial is cut mid-cycle."""
    n = int(seconds * SR)
    if len(x) < 2 * n or n == 0:
        return x
    ramp = np.linspace(0, 1, n)
    x = x.copy()
    x[:n] *= ramp
    x[-n:] *= ramp[::-1]
    return x


def chord_at(t: float) -> tuple[float, tuple[float, float, float]]:
    bar = t / BAR
    root, triad = PROGRESSION[0][1], PROGRESSION[0][2]
    for start, r, tri in PROGRESSION:
        if bar >= start:
            root, triad = r, tri
    return root, triad


def sub_pulse() -> np.ndarray:
    """The clock. One note a beat, the root an octave down, with beats 1 and 3 carrying the
    weight. It starts on the first frame because a listing video has no room for an intro:
    the picture is already arguing by then."""
    buf = np.zeros(N)
    beats = int(END_CARD / BEAT)
    for b in range(beats):
        t0 = b * BEAT
        bar_i = int(t0 // BAR)
        root, _ = chord_at(t0)
        strong = (b % 4) in (0, 2)
        # bar 5 is where the download beat starts, and the pulse firms up under it
        gain = (0.34 if strong else 0.22) * (1.18 if bar_i >= 4 else 1.0)
        if bar_i == 0 and b == 0:
            gain *= 0.8  # do not slam the very first frame
        length = int(0.55 * SR)
        e = _env(length, 0.004, 0.16 if strong else 0.10)
        t = np.arange(length) / SR
        # A sine for the body and a quiet octave above so it survives a phone speaker,
        # which reproduces nothing at 73 Hz.
        v = np.sin(2 * np.pi * root * t) + 0.28 * np.sin(2 * np.pi * root * 2 * t)
        _add(buf, t0, _fade(v * e * gain))
    return buf


def sequence() -> np.ndarray:
    """Sixteenths, five a second, which is close enough to the rate at which the reply in
    shot one actually arrives that the two read as the same event. Enters at bar 2, drops
    out for bar 6 so "no folder shared" is heard in the clear, returns wider for bar 7."""
    buf_l, buf_r = np.zeros(N), np.zeros(N)
    step = BEAT / 4
    n_steps = int(END_CARD / step)
    shape = [0, 4, 2, 4, 7, 4, 2, 4]  # scale degrees within the triad, an up-and-back figure
    for s in range(n_steps):
        t0 = s * step
        bar_i = int(t0 // BAR)
        if bar_i < 1 or bar_i == 5:
            continue
        _, triad = chord_at(t0)
        deg = shape[s % len(shape)]
        f = [triad[0], triad[1], triad[2], triad[0] * 2][min(deg // 2, 3)]
        length = int(0.30 * SR)
        t = np.arange(length) / SR
        # Triangle-ish: odd harmonics falling off fast, so it is present without being a saw
        v = (np.sin(2 * np.pi * f * t)
             + 0.11 * np.sin(2 * np.pi * 3 * f * t)
             + 0.04 * np.sin(2 * np.pi * 5 * f * t))
        e = _env(length, 0.003, 0.075)
        gain = 0.16 * (1.25 if bar_i >= 6 else 1.0)
        sig = _fade(v * e * gain)
        # Bar 7 widens: alternate steps lean left and right. Before that it sits centred.
        if bar_i >= 6:
            pan = 0.30 if s % 2 else -0.30
        else:
            pan = 0.0
        _add(buf_l, t0, sig * (1 - pan) / 2 * 2)
        _add(buf_r, t0, sig * (1 + pan) / 2 * 2)
    return np.stack([buf_l, buf_r])


def pad() -> np.ndarray:
    """The harmony, opening at bar 4 under the model-fit cards and held under everything
    after it. Detuned pairs rather than one oscillator per note, because a single sine per
    voice sounds like a test tone and two a few cents apart sounds like an instrument."""
    buf = np.zeros(N)
    for start, _, triad in PROGRESSION:
        t0, t1 = start * BAR, min((start + 2) * BAR, END_CARD)
        t0 = max(t0, PAD_IN)         # the pad opens at bar 4 and not before
        if t1 <= t0:
            continue
        if t1 <= t0:
            continue
        length = int((t1 - t0) * SR)
        t = np.arange(length) / SR
        v = np.zeros(length)
        for f in triad:
            for cents in (-6, +6):
                v += np.sin(2 * np.pi * f * 2 ** (cents / 1200) * t)
        v /= len(triad) * 2
        # slow swell in, slow settle out, and a very slight tremolo so it breathes
        ramp = np.clip(t / 1.1, 0, 1) * np.clip((t1 - t0 - t) / 1.1, 0, 1)
        v *= ramp * (1 + 0.05 * np.sin(2 * np.pi * 0.28 * t))
        _add(buf, t0, _fade(v * 0.105, 0.05))
    return buf


def ticks() -> np.ndarray:
    """A dry high tick on the offbeat, from bar 3, where the telemetry beat is. Filtered
    noise rather than a pitch: it is a detail, not a note, and it gives the top of the mix
    something to do while the picture is showing numbers."""
    rng = np.random.default_rng(20260904)
    buf = np.zeros(N)
    step = BEAT / 2
    for s in range(int(END_CARD / step)):
        t0 = s * step + step / 2
        bar_i = int(t0 // BAR)
        if bar_i < 2 or bar_i == 5 or t0 >= END_CARD:
            continue
        length = int(0.05 * SR)
        n = rng.standard_normal(length)
        # A real band pass, done in the frequency domain. The first version differenced the
        # noise twice, which is a differentiator rather than a filter: it left a click with
        # energy from DC to Nyquist, and the spectrogram showed it as a bright line up the
        # whole picture. Keeping 5.5 to 11 kHz leaves air and nothing else.
        spec = np.fft.rfft(n)
        freqs = np.fft.rfftfreq(length, 1 / SR)
        spec *= (freqs > 5500) & (freqs < 11000)
        n = np.fft.irfft(spec, length)
        e = _env(length, 0.0015, 0.012)
        _add(buf, t0, _fade(n / (np.max(np.abs(n)) + 1e-9) * e * 0.055, 0.003))
    return buf


def landing() -> np.ndarray:
    """The end card. The pulse has already stopped; this is one low hit on the cut at 23.0
    and the tonic chord resolving under the mark, decaying to nothing by the last frame.
    Deliberately not a crescendo: the line on screen is a statement, not a climax."""
    buf = np.zeros(N)
    tail = DUR - END_CARD
    length = int(tail * SR)
    t = np.arange(length) / SR

    hit = np.sin(2 * np.pi * 55 * t) * np.exp(-t / 0.5) * 0.30
    hit += np.sin(2 * np.pi * 110 * t) * np.exp(-t / 0.32) * 0.12

    chord = np.zeros(length)
    for f in (D3, F3, A3, D4):
        for cents in (-5, +5):
            chord += np.sin(2 * np.pi * f * 2 ** (cents / 1200) * t)
    chord /= 8
    # in fast enough to feel like an arrival, then a long decay across the whole end card
    chord *= np.clip(t / 0.09, 0, 1) * np.exp(-t / 2.0) * 0.16

    _add(buf, END_CARD, _fade(hit + chord, 0.02))
    return buf


def render_wav() -> Path:
    mono = sub_pulse() + pad() + ticks() + landing()
    stereo = np.stack([mono, mono]) + sequence()

    # A soft knee rather than a hard clip: tanh keeps the peaks in without the buzz a clip
    # puts on a sine, and at these levels it is doing almost nothing anyway.
    stereo = np.tanh(stereo * 1.15) / np.tanh(1.15)
    peak = float(np.max(np.abs(stereo)))
    stereo *= 0.72 / max(peak, 1e-9)          # about -2.9 dBFS peak, quiet under a picture

    n = int(0.02 * SR)
    stereo[:, :n] *= np.linspace(0, 1, n)
    stereo[:, -n:] *= np.linspace(1, 0, n)

    pcm = (np.clip(stereo.T, -1, 1) * 32767).astype("<i2")
    import wave
    with wave.open(str(WAV), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return WAV


def main() -> None:
    if not SRC.exists():
        raise SystemExit(f"missing {SRC}; run play/graphics/cut.py first")
    render_wav()
    # The picture is copied through untouched. Only the audio track differs between the two
    # uploads, so the scored file can never drift from the one Play points at.
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(SRC), "-i", str(WAV),
         "-map", "0:v", "-map", "1:a", "-c:v", "copy",
         "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", str(OUT)],
        check=True)
    dur = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", str(OUT)], capture_output=True, text=True).stdout.strip()
    print(f"wrote {OUT}  ({float(dur):.1f}s)")
    print(f"      {WAV}")


if __name__ == "__main__":
    sys.exit(main())
