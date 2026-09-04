"""Score the listing video for the channel upload: one composition, cut to the picture.

    python3 play/graphics/score.py            # render both variants
    python3 play/graphics/score.py A          # render one and make it the chosen mix

## Why there are two uploads

Play does not host the mp4. The listing field takes a YouTube URL and plays that video
inline and muted, so "silent on Play, scored on YouTube" is not two versions of one upload:
it is two uploads, and the silent one is the one Play points at. A Content ID claim can
enable monetisation, and Play requires ads off on a listing video, so a claimed track on the
linked upload could put the listing out of compliance. `listing-promo.mp4` stays silent and
stays the Play upload. This writes `listing-promo-scored.mp4` for the channel, copying the
video stream through untouched so the two can never drift.

## Rights

Every sample is synthesised here from oscillators and shaped noise, including the reverb,
whose impulse response is decaying band-limited noise generated in this file. No samples, no
loops, no library source, nothing downloaded. "No copyright" is not literally achievable,
since an original work is copyrighted to its author the moment it exists; what is achievable
is no third-party material and nothing for Content ID to match, and that is this.

## Tempo, forced rather than chosen

Measured off the finished picture by frame differencing, the cuts land at 3.2, 6.6, 9.6,
12.8, 16.4, 19.6 and 23.0 s, and 25.6 ends it. Every one is an exact multiple of 0.2 s, so
the finest grid the picture shares is 0.2 s, and the only tempos whose eighths or sixteenths
fall on all of them are 75, 150 and 300. Searching 118 to 127 at 0.005 resolution, the best
fit leaves the worst cut 30 ms off, which on a transient against a hard cut is a flam. So
150: kick on 1 and 3, clap on 2 and 4, sixteenth plucks and hats at ten a second.

## What the previous pass got wrong

Two faults, both audible and both measurable in that render.

**The privacy section was a breakdown, not a reduction.** Section F set a flag that removed
every drum voice, so its drums measured -53.2 dB against -12.6 either side, forty decibels,
and energy above 6 kHz fell from 10.1 per cent of the mix to 2.0. Forty decibels and a five
fold loss of top is not an arrangement thinning, it is a different track starting. The
groove now runs unbroken: the kick keeps 1 and 3 at three quarters level, the hats step back
from sixteenths to eighths, the shaker carries the sixteenths through untouched, and the
clap alone leaves. The plucks stay in tempo and are shelved rather than removed, the sub
loses level rather than notes, and a fill carries the last half bar into the next section.

**The ending was a logo sting, not a cadence.** It was an impact, a glitch and a noise sweep
landing on a stopped beat, which is a startup animation rather than the end of a film. The
harmony now cadences: Bb at 19.2, C at 21.6, and D minor arriving exactly on the end card,
VI to VII to i, with a snare fill carrying the band into it. On the arrival the band plays
the chord, a cymbal opens under it, and one soft kick two beats later closes the phrase.
Everything after that is sustain: the chord and its reverb tail decaying under the mark.

**And there was too much sound design.** Every cut had an impact and a glitch on it, which
is a film assembled from interface noises rather than composed around an edit. Three cuts
now carry nothing and are held by the arrangement alone: 3.2, 6.6 and 9.6.

## The arc

    0.0   A  hook on frame one, no intro: kit, sub and the motif straight in
    3.2   B  clap enters, plucks double an octave up. Nothing on the cut
    6.6   C  pad opens, shaker enters, open hat every second bar. Nothing on the cut
    9.6   D  sixteenth sub movement under the memory numbers. Nothing on the cut
    12.8  E  riser and cymbal, densest drums, the peak
    16.4  F  reduction: clap out, hats to eighths, plucks shelved, sub down. Groove intact
    19.6  G  fill and cymbal, the loudest bar, the lead motif in the clear
    23.0  H  the cadence lands on the mark; chord and tail decay to the last frame

## Two variants

A is the restrained cut: no kick pickups, a single lead line, softer cymbals, plucks and
hats pulled back. B keeps the same bridge and the same ending but pushes: kick pickups on
the "and" of four through the two densest sections, the lead doubled an octave up, brighter
plucks. Both render; the chosen one is recorded in the commit.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np
from scipy.signal import fftconvolve

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "play/videos/listing-promo.mp4"
OUT = ROOT / "play/videos/listing-promo-scored.mp4"
WAV = ROOT / "play/videos/listing-promo-score.wav"

SR = 48_000
BPM = 150.0
BEAT = 60.0 / BPM
BAR = 4 * BEAT
STEP = BEAT / 4
DUR = 25.6
N = int(DUR * SR)

CUTS = [0.0, 3.2, 6.6, 9.6, 12.8, 16.4, 19.6, 23.0]
A, B, C, D, E, F, G, H = CUTS

TARGET_LUFS = -15.0
TARGET_TP = -1.4
WET = 0.34            # reverb send, against a unit-energy impulse

VARIANTS = {
    "A": dict(pickups=False, pluck=0.86, hat=0.85, clap=0.30, lead8va=False, cym=0.30,
              gains={"A": 0.80, "B": 0.87, "C": 0.93, "D": 0.97,
                     "E": 1.03, "F": 0.84, "G": 1.08, "H": 1.0}),
    "B": dict(pickups=True, pluck=1.06, hat=1.00, clap=0.36, lead8va=True, cym=0.38,
              gains={"A": 0.80, "B": 0.88, "C": 0.95, "D": 1.00,
                     "E": 1.09, "F": 0.84, "G": 1.16, "H": 1.0}),
}


# --------------------------------------------------------------------------- primitives

def _t(n: int) -> np.ndarray:
    return np.arange(n) / SR


def bandpass(x: np.ndarray, lo: float, hi: float) -> np.ndarray:
    """Zero phase, by masking the spectrum. A causal filter smears a two millisecond
    transient and every percussive voice here is a transient."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    spec *= (f >= lo) & (f <= hi)
    return np.fft.irfft(spec, len(x))


def tilt(x: np.ndarray, cut: float, amount: float) -> np.ndarray:
    """A shelf, not a brick wall. The privacy section darkens the plucks with this: a hard
    low pass is the collapse that made the last version sound like a different track, and a
    shelf keeps the instrument recognisable while taking the edge off it."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    g = np.ones_like(f)
    hi = f > cut
    g[hi] = amount ** np.clip(np.log2(f[hi] / cut), 0, 3)
    return np.fft.irfft(spec * g, len(x))


def env(n: int, attack: float, decay: float, curve: float = 1.0) -> np.ndarray:
    t = _t(n)
    return np.clip(t / max(attack, 1e-6), 0, 1) * np.exp(-(t / max(decay, 1e-6)) ** curve)


def add(buf: np.ndarray, at: float, sig: np.ndarray, gain: float = 1.0) -> None:
    i = int(round(at * SR))
    if i < 0:
        sig, i = sig[-i:], 0
    if i >= buf.shape[-1]:
        return
    n = min(len(sig), buf.shape[-1] - i)
    if buf.ndim == 1:
        buf[i:i + n] += sig[:n] * gain
    else:
        buf[:, i:i + n] += sig[:n] * gain


def hz(s: float) -> float:
    return 440.0 * 2 ** (s / 12.0)


D2, D3, D4 = hz(-31), hz(-19), hz(-7)
F2, F3, F4 = hz(-28), hz(-16), hz(-4)
A2, A3, A4 = hz(-24), hz(-12), hz(0)
BB1, BB3 = hz(-35), hz(-11)
C2, C4 = hz(-33), hz(-9)
E4, G4 = hz(-5), hz(-2)

# The last three changes are the cadence: Bb, then C, then D minor on the end card.
CHORDS = [
    (0.0, D2, (D4, F4, A4)),
    (6.4, BB1, (BB3, D4, F4)),
    (9.6, F2, (F4, A4, C4 * 2)),
    (12.8, D2, (D4, F4, A4)),
    (16.0, BB1, (BB3, D4, F4)),
    (19.2, BB1, (BB3, D4, F4)),
    (21.6, C2, (C4, E4, G4)),
    (23.0, D2, (D4, F4, A4)),
]


def chord(t: float):
    root, tri = CHORDS[0][1], CHORDS[0][2]
    for start, r, tr in CHORDS:
        if t >= start - 1e-9:
            root, tri = r, tr
    return root, tri


def section(t: float) -> str:
    for name, start in zip("HGFEDCBA", CUTS[::-1]):
        if t >= start - 1e-9:
            return name
    return "A"


# ------------------------------------------------------------------------------- voices

def kick_hit(rng, gain=1.0) -> np.ndarray:
    """118 Hz down to 46 in 32 ms. The drop is the whole character: it is what reads as
    weight on a speaker that cannot reproduce the fundamental."""
    n = int(0.42 * SR)
    t = _t(n)
    f = 46 + (118 - 46) * np.exp(-t / 0.032)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, 0.001, 0.155, 1.2)
    click = bandpass(rng.standard_normal(n), 1200, 7000) * env(n, 0.0002, 0.0035)
    return (body + 0.16 * click / (np.abs(click).max() + 1e-9)) * gain


def clap_hit(rng, gain=1.0) -> np.ndarray:
    """Four bursts nine milliseconds apart, which is what makes a clap a clap rather than a
    noise burst, then one longer tail so it has a room of its own."""
    n = int(0.34 * SR)
    out = np.zeros(n)
    src = bandpass(rng.standard_normal(n), 900, 6200)
    src /= np.abs(src).max() + 1e-9
    for k, off in enumerate((0.0, 0.009, 0.018, 0.026)):
        i = int(off * SR)
        out[i:] += src[i:] * env(n - i, 0.0004, 0.012 if k < 3 else 0.085) * (0.75 if k < 3 else 1.0)
    return out / (np.abs(out).max() + 1e-9) * gain


def hat(rng, open_=False, gain=1.0) -> np.ndarray:
    n = int((0.20 if open_ else 0.055) * SR)
    x = bandpass(rng.standard_normal(n), 7200, 15500)
    return x / (np.abs(x).max() + 1e-9) * env(n, 0.0003, 0.075 if open_ else 0.011) * gain


def shaker(rng, gain=1.0) -> np.ndarray:
    """Softer and lower than the hat, with a slower attack so it reads as shaken rather than
    struck. It is the voice that keeps the sixteenths alive through the reduction, which is
    how the groove survives the hats stepping back to eighths."""
    n = int(0.075 * SR)
    x = bandpass(rng.standard_normal(n), 4200, 11000)
    return x / (np.abs(x).max() + 1e-9) * env(n, 0.004, 0.020) * gain


def snare(rng, gain=1.0) -> np.ndarray:
    n = int(0.18 * SR)
    t = _t(n)
    tone = (np.sin(2 * np.pi * 186 * t) + 0.7 * np.sin(2 * np.pi * 278 * t)) * env(n, 0.0006, 0.045)
    noise = bandpass(rng.standard_normal(n), 1400, 9500)
    noise = noise / (np.abs(noise).max() + 1e-9) * env(n, 0.0004, 0.055)
    return (0.45 * tone + 0.9 * noise) * gain


def cymbal(rng, dur=1.6, gain=1.0) -> np.ndarray:
    """A wash rather than a crash: a six millisecond attack, slow enough to read as an
    arrival instead of a hit. This marks the download peak and the cadence now that the
    impacts are gone."""
    n = int(dur * SR)
    x = bandpass(rng.standard_normal(n), 3000, 16000)
    return x / (np.abs(x).max() + 1e-9) * env(n, 0.006, dur * 0.30) * gain


def pluck(f, dur, bright=1.0, gain=1.0) -> np.ndarray:
    """Six partials, each with its own decay, so the top disappears before the fundamental.
    That relationship is what makes it a pluck rather than a filtered tone."""
    n = int(dur * SR)
    t = _t(n)
    out = np.zeros(n)
    for k in range(1, 7):
        out += np.sin(2 * np.pi * f * k * t) / k * np.exp(-t / (0.055 / k ** 0.85 * (1 + 0.9 * bright)))
    return out * env(n, 0.0008, dur * 0.9) * gain


def sub(f, dur, gain=1.0) -> np.ndarray:
    n = int(dur * SR)
    t = _t(n)
    return (np.sin(2 * np.pi * f * t) + 0.22 * np.sin(2 * np.pi * 2 * f * t)) \
        * env(n, 0.006, dur * 0.55) * gain


def riser(rng, dur, f0, f1, gain=1.0) -> np.ndarray:
    n = int(dur * SR)
    x = rng.standard_normal(n)
    blk, out = 1024, np.zeros(n)
    win = np.hanning(blk)
    for i in range(0, n - blk, blk // 2):
        centre = f0 * (f1 / f0) ** (i / max(n - blk, 1))
        seg = np.fft.rfft(x[i:i + blk] * win)
        f = np.fft.rfftfreq(blk, 1 / SR)
        seg *= np.exp(-(np.log2(np.maximum(f, 20) / centre) ** 2) / 0.5)
        out[i:i + blk] += np.fft.irfft(seg, blk)
    out /= np.abs(out).max() + 1e-9
    return out * (np.linspace(0, 1, n) ** 1.15) * gain


def make_ir(rng, dur=2.4) -> np.ndarray:
    """A hall as decaying band-limited noise, with a short pre-delay and quieter early
    reflections. Two and a bit seconds is chosen so the final chord is still under the mark
    at 25 s and gone by the last frame."""
    n = int(dur * SR)
    x = rng.standard_normal(n) * np.exp(-_t(n) / 0.62)
    x = bandpass(x, 180, 9000)
    x[:int(0.018 * SR)] *= 0.15
    x[int(0.018 * SR):int(0.05 * SR)] *= 0.45
    # Energy normalised, not peak normalised. A two second noise impulse scaled to a peak of
    # 0.3 still carries enormous total energy, and convolving with it multiplied the mix by
    # about twenty: the tail came back at peak 19.5 and the soft clipper was crushing a
    # fifth of all samples by more than 3 dB. Unit energy makes the wet return sit at
    # roughly the level of what was sent to it, so the send control means what it says.
    return x / (np.sqrt(np.sum(x ** 2)) + 1e-9)


def reverb(x: np.ndarray, ir: np.ndarray) -> np.ndarray:
    if x.ndim == 1:
        return fftconvolve(x, ir)[:len(x)]
    return np.stack([fftconvolve(ch, ir)[:x.shape[1]] for ch in x])


def pad_block(rng, t0, t1, tri, gain, lo, hi) -> np.ndarray:
    n = int((t1 - t0) * SR)
    t = _t(n)
    v = np.zeros(n)
    for f in tri:
        for cents in (-7, 0, +7):
            v += np.sin(2 * np.pi * f * 2 ** (cents / 1200) * t + rng.random() * 6.28)
    v = bandpass(v / (len(tri) * 3), lo, hi)
    return v * np.clip(t / 0.25, 0, 1) * np.clip((t1 - t0 - t) / 0.35, 0, 1) * gain


# ------------------------------------------------------------------------------ the kit

def drums(rng, v):
    """The privacy section is a reduction, not a hole. Through F the kick keeps 1 and 3 at
    three quarters level, the hats step back from sixteenths to eighths, the shaker carries
    the sixteenths untouched, and the clap is the single layer that leaves."""
    buf = np.zeros(N)
    kicks = []
    for s in range(int(DUR / STEP)):
        t = s * STEP
        sec = section(t)
        beat, six = (s // 4) % 4, s % 4
        thin = sec == "F"
        if sec == "H":
            continue                                   # the ending is written by hand

        if six == 0 and beat in (0, 2):
            g = (1.0 if beat == 0 else 0.88) * (0.75 if thin else 1.0)
            if v["pickups"] and sec in ("E", "G"):
                g *= 1.05
            add(buf, t, kick_hit(rng, 0.92 * g))
            kicks.append(t)
        elif v["pickups"] and sec in ("E", "G") and beat == 3 and six == 2:
            add(buf, t, kick_hit(rng, 0.48))
            kicks.append(t)

        if sec != "A" and not thin and six == 0 and beat in (1, 3):
            add(buf, t, clap_hit(rng, v["clap"] * (1.12 if sec in ("E", "G") else 1.0)))

        if not thin:
            g = (0.16 if six == 0 else 0.085 if six == 2 else 0.055) * v["hat"]
            if sec in ("A", "B"):
                g *= 0.8
            add(buf, t, hat(rng, False, g))
            if sec in ("C", "D", "E", "G") and beat == 3 and six == 2:
                add(buf, t, hat(rng, True, 0.14 * v["hat"]))
        elif six in (0, 2):
            add(buf, t, hat(rng, False, (0.115 if six == 0 else 0.062) * v["hat"]))

        if sec in ("C", "D", "E", "F", "G"):
            add(buf, t, shaker(rng, 0.048 if six % 2 else 0.030))

        if sec in ("D", "E", "G") and six == 3 and beat in (1, 3):
            add(buf, t, snare(rng, 0.055))

    # Three fills, all played rather than swept: into the reduction, out of it, and into
    # the cadence. A fill is a drummer connecting two sections; a riser is an effect.
    for start, end, top in ((F - 0.6, F, 0.15), (G - 0.8, G, 0.30), (H - 0.6, H, 0.34)):
        steps = int(round((end - start) / (STEP / 2)))
        for k in range(steps):
            frac = k / max(steps - 1, 1)
            add(buf, start + k * STEP / 2, snare(rng, top * (0.32 + 0.68 * frac ** 1.5)))
    return buf, kicks


def duck(kicks, depth=0.55, hold=0.115) -> np.ndarray:
    g = np.ones(N)
    n = int(hold * SR)
    shape = 1 - depth * np.exp(-_t(n) / (hold / 3.0))
    for t in kicks:
        i = int(round(t * SR))
        if i < N:
            m = min(n, N - i)
            g[i:i + m] = np.minimum(g[i:i + m], shape[:m])
    return g


def bassline(v) -> np.ndarray:
    """Through the reduction the bass loses level, not notes: it keeps the downbeats so the
    harmony never lets go, which is what stops a thinning from reading as a stop."""
    buf = np.zeros(N)
    for s in range(int(H / STEP)):
        t = s * STEP
        sec = section(t)
        root, _ = chord(t)
        six = s % 4
        thin = 0.62 if sec == "F" else 1.0
        if six == 0:
            add(buf, t, sub(root, 0.36, 0.80 * thin))
        elif sec in ("D", "E", "G") and six == 2:
            add(buf, t, sub(root * (1.5 if s % 8 == 6 else 1.0), 0.16, 0.34))
        elif sec == "F" and six == 2 and (s // 4) % 2 == 1:
            add(buf, t, sub(root, 0.14, 0.22))
    return buf


MOTIF = [0, 7, 5, 12, 7, 3, 0, -5]
SCALE = [0, 2, 3, 5, 7, 8, 10, 12]


def plucks(v) -> np.ndarray:
    """Sixteenths at ten a second, near the rate the reply in shot one arrives at. Through
    the reduction they keep playing and are shelved rather than removed: the note density
    IS the groove, and taking it away is what made the last version restart."""
    l, r = np.zeros(N), np.zeros(N)
    for s in range(int(H / STEP)):
        t = s * STEP
        sec = section(t)
        _, tri = chord(t)
        deg = MOTIF[s % len(MOTIF)]
        f = tri[0] * 2 ** ((SCALE[deg % 8] + 12 * (deg // 8)) / 12)

        if sec == "F":
            g, brt = 0.25, 0.55
        elif sec in ("A", "B"):
            g, brt = 0.30, 0.85
        elif sec in ("C", "D"):
            g, brt = 0.34, 1.0
        else:
            g, brt = 0.40, 1.15
        g *= v["pluck"]

        sig = pluck(f, 0.26, brt, g)
        if sec == "F":
            sig = tilt(sig, 1600, 0.50)
        pan = 0.34 if s % 2 else -0.34
        pan *= 0.45 if sec in ("A", "B") else (1.25 if sec == "G" else 1.0)
        add(l, t, sig * (1 - pan))
        add(r, t, sig * (1 + pan))

        if sec in ("B", "C", "D", "E", "G") and s % 2 == 0:
            hi = pluck(f * 2, 0.16, 1.3, g * (0.34 if sec != "G" else 0.46))
            add(l, t, hi * (1 + pan))
            add(r, t, hi * (1 - pan))
    return np.stack([l, r])


def lead(v) -> np.ndarray:
    """One melodic statement, saved for the bar under "You control what goes online"."""
    l, r = np.zeros(N), np.zeros(N)
    _, tri = chord(G + 0.1)
    figure = [(0.0, 7), (0.2, 5), (0.4, 3), (0.8, 7), (1.2, 12),
              (1.6, 10), (1.8, 7), (2.0, 5), (2.4, 3), (2.8, 0)]
    for off, deg in figure:
        f = tri[0] * 2 ** (SCALE[deg % 8] / 12 + (deg // 8))
        sig = pluck(f, 0.42, 1.5, 0.26) + pluck(f * 1.005, 0.42, 1.4, 0.16)
        if v["lead8va"]:
            # Shorter than the note it doubles, so it needs summing in rather than adding:
            # the two arrays are different lengths by design.
            oct_up = pluck(f * 2, 0.30, 1.5, 0.10)
            sig = sig.copy()
            sig[:len(oct_up)] += oct_up
        add(l, G + off, sig * 1.1)
        add(r, G + off, sig * 0.9)
    return np.stack([l, r])


def pads(rng) -> np.ndarray:
    buf = np.zeros(N)
    for i, (t0, _, tri) in enumerate(CHORDS):
        t1 = CHORDS[i + 1][0] if i + 1 < len(CHORDS) else DUR
        if t1 <= C:
            continue
        t0 = max(t0, C)
        if t1 <= t0:
            continue
        if t0 >= H - 1e-9:
            add(buf, t0, pad_block(rng, t0, DUR, tri, 0.34, 90, 4200))
        elif F - 0.5 <= t0 < G:
            # Through the reduction the pad comes forward, so the section loses drums but
            # gains harmony and the total never thins to nothing.
            add(buf, t0, pad_block(rng, t0, t1, tri, 0.30, 90, 3200))
        else:
            add(buf, t0, pad_block(rng, t0, t1, tri, 0.16, 120, 1900))
    return buf


def finale(rng, v) -> np.ndarray:
    """The end card, written as a cadence rather than a stinger.

    The harmony has already moved Bb to C across the last bar and a half. Here it arrives on
    D minor exactly on the picture cut: the band plays the chord, a cymbal opens under it, a
    single soft kick two beats later closes the phrase, and everything from there is the
    chord and its tail decaying under the mark. No sweep, no glitch and no impact, because
    the previous version had all three and they are what made it a logo animation."""
    l, r = np.zeros(N), np.zeros(N)

    def st(at, sig, spread=0.0):
        add(l, at, sig * (1 - spread))
        add(r, at, sig * (1 + spread))

    root, tri = chord(H)
    st(H, kick_hit(rng, 0.95))
    st(H + 2 * BEAT, kick_hit(rng, 0.40))
    st(H, cymbal(rng, 2.0, v["cym"]), 0.06)
    st(H, sub(root, 2.2, 0.70))

    for mult, g, dur in ((1.0, 0.26, 1.5), (2.0, 0.20, 1.2), (3.0, 0.11, 0.9)):
        for k, f in enumerate(tri):
            st(H, pluck(f * mult, dur, 0.9, g / (k + 1.4)), 0.10 if k % 2 else -0.10)
    # The motif's first two notes, an octave apart, so the film ends on the phrase it opened
    # with rather than on a new idea.
    st(H + 0.4, pluck(tri[0] * 2, 1.1, 1.0, 0.13), 0.14)
    st(H + 0.4, pluck(tri[2], 1.1, 0.9, 0.10), -0.14)

    n = int((DUR - H) * SR)
    t = _t(n)
    held = np.zeros(n)
    for f in (D3, F3, A3, D4, F4):
        for cents in (-6, +6):
            held += np.sin(2 * np.pi * f * 2 ** (cents / 1200) * t + rng.random() * 6.28)
    held *= np.clip(t / 0.05, 0, 1) * np.exp(-t / 1.55) / 10
    st(H, held * 0.62, 0.05)
    return np.stack([l, r])


def design(rng, v) -> np.ndarray:
    """What survived the cull. Three cuts carry nothing at all: 3.2, 6.6 and 9.6 are held by
    the arrangement, because a film where every cut has an effect on it is assembled from
    interface noises rather than composed around an edit."""
    l, r = np.zeros(N), np.zeros(N)

    def st(at, sig, spread=0.0):
        add(l, at, sig * (1 - spread))
        add(r, at, sig * (1 + spread))

    st(A, cymbal(rng, 1.4, v["cym"] * 0.85), 0.04)
    st(E - 1.0, riser(rng, 1.0, 400, 7000, 0.32))
    st(E, cymbal(rng, 1.5, v["cym"] * 0.9), 0.05)
    st(G - 1.2, riser(rng, 1.2, 400, 8000, 0.38))
    st(G, cymbal(rng, 1.6, v["cym"]), 0.05)

    # Six ticks under the telemetry, where the picture is showing numbers. The previous
    # version had sixteen of these plus one on every cut.
    for k in range(6):
        n = int(0.045 * SR)
        x = bandpass(rng.standard_normal(n), 2500, 8000)
        x = x / (np.abs(x).max() + 1e-9) * env(n, 0.0006, 0.010)
        st(B + 0.4 + k * 0.5, x * 0.055, 0.3 if k % 2 else -0.3)
    return np.stack([l, r])


# ------------------------------------------------------------------------------- mixdown

def groove_curve(v) -> np.ndarray:
    g = np.ones(N)
    bounds = CUTS + [DUR]
    for i, name in enumerate("ABCDEFGH"):
        g[int(bounds[i] * SR):int(bounds[i + 1] * SR)] = v["gains"][name]
    k = int(0.20 * SR)
    win = np.hanning(k) / np.hanning(k).sum()
    return np.convolve(g, win, mode="same")


def render(name: str, out_wav: Path) -> Path:
    v = VARIANTS[name]
    rng = np.random.default_rng(2026_09_04)
    ir = make_ir(rng)

    kit, kicks = drums(rng, v)
    duckg = duck(kicks)
    curve = groove_curve(v)

    dry_mono = (kit + bassline(v) * duckg + pads(rng) * (0.35 + 0.65 * duckg)) * curve
    dry = np.stack([dry_mono, dry_mono])
    wet = plucks(v) * (0.55 + 0.45 * duckg) * curve + lead(v) + finale(rng, v) + design(rng, v)

    # Reverb goes on the tuned voices only. Putting it on the kick and sub smears the low
    # end and costs the mix the headroom the sidechain was there to protect.
    stereo = dry + wet + reverb(wet, ir) * WET

    # Gain staging before the soft knee, so the knee only rounds the tips. Without this the
    # tanh is a brick wall and the whole mix loses its transients: measured on the first
    # render of this arrangement it was flattening 18.9 per cent of samples, which showed up
    # as a peak to loudness ratio of 2.6 dB, a figure no music with a kick drum can have.
    stereo *= 1.18 / (np.abs(stereo).max() + 1e-9)
    stereo = np.tanh(stereo * 0.80) / np.tanh(0.80)
    stereo *= 0.89 / (np.abs(stereo).max() + 1e-9)
    n = int(0.004 * SR)
    stereo[:, :n] *= np.linspace(0, 1, n)
    stereo[:, -n:] *= np.linspace(1, 0, n)

    pcm = (np.clip(stereo.T, -1, 1) * 32767).astype("<i2")
    with wave.open(str(out_wav), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return out_wav


def normalise(raw: Path, dst: Path) -> Path:
    """Two pass loudnorm, linear, so the arrangement's own dynamics survive. A single pass
    is adaptive and would flatten exactly the reduction the arc depends on."""
    out = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(raw), "-af",
         f"loudnorm=I={TARGET_LUFS}:TP={TARGET_TP}:LRA=11:print_format=json",
         "-f", "null", "-"], capture_output=True, text=True).stderr
    m = json.loads(out[out.rindex("{"):out.rindex("}") + 1])
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-af",
         f"loudnorm=I={TARGET_LUFS}:TP={TARGET_TP}:LRA=11:linear=true"
         f":measured_I={m['input_i']}:measured_TP={m['input_tp']}"
         f":measured_LRA={m['input_lra']}:measured_thresh={m['input_thresh']}"
         f":offset={m['target_offset']}", "-ar", str(SR), "-c:a", "pcm_s16le", str(dst)],
        check=True)
    return dst


def mux(wav: Path, dst: Path) -> None:
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(SRC), "-i", str(wav),
         "-map", "0:v", "-map", "1:a", "-c:v", "copy",
         "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart", str(dst)], check=True)


def main() -> None:
    if not SRC.exists():
        raise SystemExit(f"missing {SRC}; run play/graphics/cut.py first")
    want = sys.argv[1].upper() if len(sys.argv) > 1 else None
    for name in (["A", "B"] if want is None else [want]):
        raw = ROOT / f"play/videos/.score-{name}-raw.wav"
        wav = ROOT / f"play/videos/listing-promo-score-{name}.wav"
        normalise(render(name, raw), wav)
        raw.unlink(missing_ok=True)
        mux(wav, ROOT / f"play/videos/listing-promo-scored-{name}.mp4")
        print(f"variant {name} -> listing-promo-scored-{name}.mp4")
    if want:
        shutil.copy(ROOT / f"play/videos/listing-promo-score-{want}.wav", WAV)
        mux(WAV, OUT)
        print(f"chose {want} -> {OUT.name}")


if __name__ == "__main__":
    main()
