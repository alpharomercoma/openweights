"""Score the listing video for the channel upload: composition and sound design, from
oscillators and shaped noise, cut to the picture.

    python3 play/graphics/score.py

## Why there are two uploads

Play does not host the mp4. The listing field takes a YouTube URL and plays that video
inline and muted, so "silent on Play, scored on YouTube" is not two versions of one upload:
it is two uploads, and the silent one is the one Play points at. A Content ID claim can
enable monetisation, and Play requires ads off on a listing video, so a claimed track on the
linked upload could put the listing out of compliance. `listing-promo.mp4` stays silent and
stays the Play upload. This writes `listing-promo-scored.mp4` for the channel, copying the
video stream through untouched so the two can never drift.

## Rights

Every sample is synthesised here from oscillators and band-limited noise. No samples, no
loops, no library source, nothing downloaded. "No copyright" is not literally achievable,
since an original work is copyrighted to its author the moment it exists; what is achievable
is no third-party material and nothing for Content ID to match against, and that is this.

## Tempo, and why it is not 125

The brief asked for 120 to 126 BPM. The edit will not take it. Measured off the finished
file by frame differencing, the cuts land at 3.2, 6.6, 9.6, 12.8, 16.4, 19.6 and 23.0
seconds, and 25.6 s ends it. Every one of those is an exact multiple of 0.2 s, so the
finest grid the picture shares is 0.2 s, and the only tempos whose eighth or sixteenth
notes fall on all of them are 75, 150 and 300 BPM. At 125 the best fit leaves the worst cut
30 ms off the grid, which on a transient against a hard cut is a flam rather than a hit.

So 150, which is the old score's 75 doubled: every cut still lands on a beat or a clean
eighth, and the pulse is twice as fast. The kick sits on 1 and 3, the clap on 2 and 4, so
something lands every 0.4 s, and the plucks and hats run sixteenths at 10 a second, which is
close to the rate the reply in shot one actually arrives at.

    bar = 1.6 s, 16 bars in 25.6 s
    3.2  bar 3           6.6  bar 5 + an eighth     9.6  bar 7
    12.8 bar 9          16.4  bar 11 beat 2        19.6  bar 13 beat 2
    23.0 bar 15 beat 2 + an eighth

The three cuts that land off the downbeat are played as syncopations rather than nudged,
because an accent on the "and" is a musical event and a hit 30 ms early is a mistake.

## The cue sheet

    0.0   A  impact on frame one, no intro. Kick, sub, sixteenth plucks, hats, and the
             three note motif that comes back on the end card.
    3.2   B  clap enters, plucks double an octave up, digital ticks under the telemetry.
    6.6   C  syncopated hit, pad opens, open hat every second bar.
    9.6   D  sixteenth sub movement and an extra percussion accent for the memory numbers.
    12.8  E  riser through 12.0 to 12.8, impact on the cut, densest drums of the film.
    16.4  F  everything drops for the privacy card. Downlifter, filtered pad, one pluck,
             no drums. This is the negative space the arc needs.
    19.6  G  riser through 18.8, then the strongest bar: lead motif over the full kit,
             widest stereo, sub at its loudest.
    23.0  H  hard stop into the branded hit. Impact, the motif in octaves, a tonic chord
             decaying under the mark to the last frame.

## What was refused, and why it stays refused

The first brief asked for an enterprise keynote bed with applause. codex and agy were asked
independently and both refused the same two things. Applause invents an audience, which is
the argument this project already accepted when it kept UI click foley out: invented events
are the audio equivalent of staging footage. A corporate hype bed scores unembellished
evidence like a launch keynote and tells the viewer to distrust what is being proved. Their
alternative, a sparse electronic pulse, was correct about the palette and wrong about the
level: at 75 BPM and -18 LUFS it was background rather than a score. This keeps the palette
and fixes the level.
"""
from __future__ import annotations

import subprocess
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "play/videos/listing-promo.mp4"
OUT = ROOT / "play/videos/listing-promo-scored.mp4"
WAV = ROOT / "play/videos/listing-promo-score.wav"
RAW = ROOT / "play/videos/.score-raw.wav"

SR = 48_000
BPM = 150.0
BEAT = 60.0 / BPM               # 0.4 s
BAR = 4 * BEAT                  # 1.6 s
STEP = BEAT / 4                 # 0.1 s, one sixteenth
DUR = 25.6
N = int(DUR * SR)

# Measured off the finished picture, not taken from the edit list.
CUTS = [0.0, 3.2, 6.6, 9.6, 12.8, 16.4, 19.6, 23.0]
A, B, C, D, E, F, G, H = CUTS

TARGET_LUFS = -15.0
TARGET_TP = -1.2

RNG = np.random.default_rng(2026_09_04)


# --------------------------------------------------------------------------- primitives

def _t(n: int) -> np.ndarray:
    return np.arange(n) / SR


def band(x: np.ndarray, lo: float, hi: float) -> np.ndarray:
    """Zero-phase band limit by masking the spectrum. Zero phase matters here: a causal
    filter smears a two millisecond transient, and every percussive voice in this file is a
    transient."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    spec *= (f >= lo) & (f <= hi)
    return np.fft.irfft(spec, len(x))


def env(n: int, attack: float, decay: float, curve: float = 1.0) -> np.ndarray:
    t = _t(n)
    a = np.clip(t / max(attack, 1e-6), 0, 1)
    return a * np.exp(-(t / max(decay, 1e-6)) ** curve)


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


def hz(semis: float) -> float:
    return 440.0 * 2 ** (semis / 12.0)


# D minor throughout. i - VI - III - VII, the confident one, two bars a chord.
D2, D3, D4, D5 = hz(-31), hz(-19), hz(-7), hz(5)
F2, F3, F4 = hz(-28), hz(-16), hz(-4)
A2, A3, A4 = hz(-24), hz(-12), hz(0)
BB1, BB2, BB3 = hz(-35), hz(-23), hz(-11)
C2, C3, C4 = hz(-33), hz(-21), hz(-9)
E4, G4 = hz(-5), hz(-2)

# (from, root, triad). Chords change on 3.2 s boundaries, which anticipates the three cuts
# that fall slightly later; a chord arriving just before the picture reads as a push.
CHORDS = [
    (0.0, D2, (D4, F4, A4)),
    (6.4, BB1, (BB3, D4, F4)),
    (9.6, F2, (F4, A4, C4 * 2)),
    (12.8, D2, (D4, F4, A4)),
    (16.0, BB1, (BB3, D4, F4)),
    (19.2, C2, (C4, E4, G4)),
    (22.4, D2, (D4, F4, A4)),
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

def kick_hit(gain: float = 1.0) -> np.ndarray:
    """Pitch envelope from 118 Hz to 46 Hz in 32 ms, which is the whole character: the drop
    is what reads as weight on a laptop speaker that cannot reproduce the fundamental."""
    n = int(0.42 * SR)
    t = _t(n)
    f = 46 + (118 - 46) * np.exp(-t / 0.032)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, 0.001, 0.155, 1.2)
    click = band(RNG.standard_normal(n), 1200, 7000) * env(n, 0.0002, 0.0035)
    return (body + 0.16 * click / (np.abs(click).max() + 1e-9)) * gain


def clap_hit(gain: float = 1.0) -> np.ndarray:
    """Four bursts 9 ms apart rather than one, which is what makes a clap a clap, then a
    short bright tail so it has a room without a reverb bus."""
    n = int(0.34 * SR)
    out = np.zeros(n)
    src = band(RNG.standard_normal(n), 900, 6200)
    src /= np.abs(src).max() + 1e-9
    for k, off in enumerate((0.0, 0.009, 0.018, 0.026)):
        i = int(off * SR)
        e = env(n - i, 0.0004, 0.012 if k < 3 else 0.085)
        out[i:] += src[i:] * e * (0.75 if k < 3 else 1.0)
    return out / (np.abs(out).max() + 1e-9) * gain


def hat(open_: bool = False, gain: float = 1.0) -> np.ndarray:
    n = int((0.20 if open_ else 0.055) * SR)
    x = band(RNG.standard_normal(n), 7200, 15500)
    x /= np.abs(x).max() + 1e-9
    return x * env(n, 0.0003, 0.075 if open_ else 0.011) * gain


def rim(gain: float = 1.0) -> np.ndarray:
    n = int(0.09 * SR)
    t = _t(n)
    tone = np.sin(2 * np.pi * 1750 * t) + 0.5 * np.sin(2 * np.pi * 2630 * t)
    noise = band(RNG.standard_normal(n), 2000, 9000)
    noise /= np.abs(noise).max() + 1e-9
    return (0.6 * tone + 0.7 * noise) * env(n, 0.0003, 0.014) * gain


def pluck(f: float, dur: float, bright: float = 1.0, gain: float = 1.0) -> np.ndarray:
    """A saw thinned to five partials with a falling lowpass, which is a pluck: the top
    disappears faster than the fundamental. Cheaper and cleaner than filtering a real saw."""
    n = int(dur * SR)
    t = _t(n)
    out = np.zeros(n)
    for k in range(1, 7):
        decay = 0.055 / (k ** 0.85) * (1 + 0.9 * bright)
        out += np.sin(2 * np.pi * f * k * t) / k * np.exp(-t / decay)
    return out * env(n, 0.0008, dur * 0.9) * gain


def sub(f: float, dur: float, gain: float = 1.0) -> np.ndarray:
    n = int(dur * SR)
    t = _t(n)
    v = np.sin(2 * np.pi * f * t) + 0.22 * np.sin(2 * np.pi * 2 * f * t)
    return v * env(n, 0.006, dur * 0.55) * gain


def sweep(dur: float, f0: float, f1: float, up: bool = True, gain: float = 1.0) -> np.ndarray:
    """Noise through a band that travels, done block by block in the frequency domain. The
    riser into the download beat and the downlifter out of it are the same function."""
    n = int(dur * SR)
    x = RNG.standard_normal(n)
    blk = 1024
    out = np.zeros(n)
    win = np.hanning(blk)
    for i in range(0, n - blk, blk // 2):
        frac = i / max(n - blk, 1)
        centre = f0 * (f1 / f0) ** frac
        seg = np.fft.rfft(x[i:i + blk] * win)
        f = np.fft.rfftfreq(blk, 1 / SR)
        seg *= np.exp(-((np.log2(np.maximum(f, 20) / centre)) ** 2) / 0.45)
        out[i:i + blk] += np.fft.irfft(seg, blk)
    out /= np.abs(out).max() + 1e-9
    ramp = np.linspace(0, 1, n) ** 1.15
    return out * (ramp if up else ramp[::-1]) * gain


def impact(gain: float = 1.0, tail: float = 1.5) -> np.ndarray:
    """The thing that lands on a cut: a low sine that drops, a mid body so it reads on a
    phone, and a short noise front so the transient is audible before the pitch is."""
    n = int(tail * SR)
    t = _t(n)
    f = 38 + 90 * np.exp(-t / 0.05)
    low = np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, 0.0008, 0.34, 1.1)
    mid = band(RNG.standard_normal(n), 180, 2400) * env(n, 0.0005, 0.10)
    air = band(RNG.standard_normal(n), 4000, 13000) * env(n, 0.0004, 0.045)
    mid /= np.abs(mid).max() + 1e-9
    air /= np.abs(air).max() + 1e-9
    return (low + 0.38 * mid + 0.22 * air) * gain


def glitch(dur: float = 0.09, gain: float = 1.0) -> np.ndarray:
    """Sample and hold on noise, which is the cheap and correct way to get a digital
    texture: quantising time, not amplitude, is what makes it read as data rather than as
    distortion."""
    n = int(dur * SR)
    hold = RNG.integers(24, 190)
    raw = RNG.standard_normal(int(np.ceil(n / hold)) + 1)
    x = np.repeat(raw, hold)[:n]
    x = band(x, 900, 9000)
    x /= np.abs(x).max() + 1e-9
    return x * env(n, 0.0004, dur * 0.35) * gain


def pad_block(t0: float, t1: float, tri, gain: float, lo: float, hi: float) -> np.ndarray:
    n = int((t1 - t0) * SR)
    t = _t(n)
    v = np.zeros(n)
    for f in tri:
        for cents in (-7, 0, +7):
            v += np.sin(2 * np.pi * f * 2 ** (cents / 1200) * t + RNG.random() * 6.28)
    v /= len(tri) * 3
    v = band(v, lo, hi)
    ramp = np.clip(t / 0.25, 0, 1) * np.clip((t1 - t0 - t) / 0.35, 0, 1)
    return v * ramp * gain


# ------------------------------------------------------------------------------ the kit

def drums() -> tuple[np.ndarray, list[float]]:
    buf = np.zeros(N)
    kick_times: list[float] = []
    n_steps = int(H / STEP)

    for s in range(n_steps):
        t = s * STEP
        sec = section(t)
        beat_in_bar = (s // 4) % 4
        six = s % 4
        quiet = sec == "F"                    # the privacy card is the hole in the arc

        # Kick on 1 and 3. In the two densest sections it also takes the "and" of 4 as a
        # pickup, which is what stops a backbeat this fast from sitting flat.
        if not quiet:
            if six == 0 and beat_in_bar in (0, 2):
                g = 1.0 if beat_in_bar == 0 else 0.88
                if sec in ("E", "G"):
                    g *= 1.06
                add(buf, t, kick_hit(0.92 * g))
                kick_times.append(t)
            elif sec in ("E", "G") and beat_in_bar == 3 and six == 2:
                add(buf, t, kick_hit(0.5))
                kick_times.append(t)

        # Clap on 2 and 4, from B. It is the single loudest thing about the change at 3.2.
        if not quiet and sec != "A" and six == 0 and beat_in_bar in (1, 3):
            add(buf, t, clap_hit(0.40 if sec in ("E", "G") else 0.34))

        # Sixteenth hats, accented on the beat, with an open hat on the last eighth of a
        # bar from C so the bars have an end as well as a start.
        if not quiet:
            g = 0.16 if six == 0 else (0.085 if six == 2 else 0.055)
            if sec in ("A", "B"):
                g *= 0.8
            add(buf, t, hat(False, g))
            if sec in ("C", "D", "E", "G") and beat_in_bar == 3 and six == 2:
                add(buf, t, hat(True, 0.14))

        # A rim on the offbeat sixteenth in the busier half, for detail rather than weight.
        if sec in ("D", "E", "G") and six == 3 and beat_in_bar in (1, 3):
            add(buf, t, rim(0.11))

    return buf, kick_times


def duck(kick_times: list[float], depth: float = 0.55, hold: float = 0.115) -> np.ndarray:
    """Sidechain, done as an envelope rather than a compressor. Without it the sub and the
    kick fight for the same 50 Hz and the whole mix loses a couple of dB of headroom for
    nothing."""
    g = np.ones(N)
    n = int(hold * SR)
    shape = 1 - depth * np.exp(-_t(n) / (hold / 3.0))
    for t in kick_times:
        i = int(round(t * SR))
        if i >= N:
            continue
        m = min(n, N - i)
        g[i:i + m] = np.minimum(g[i:i + m], shape[:m])
    return g


def bassline() -> np.ndarray:
    buf = np.zeros(N)
    n_steps = int(H / STEP)
    for s in range(n_steps):
        t = s * STEP
        sec = section(t)
        root, _ = chord(t)
        six = s % 4
        if sec == "F":
            # one long note, so the hole is not silence
            continue
        if six == 0:
            add(buf, t, sub(root, 0.36, 0.80))
        elif sec in ("D", "E", "G") and six == 2:
            # sixteenth movement once the film is arguing about memory and speed
            add(buf, t, sub(root * (1.5 if s % 8 == 6 else 1.0), 0.16, 0.34))
    for t0, t1 in ((F, G),):
        root, _ = chord(t0)
        add(buf, t0, sub(root, t1 - t0, 0.42))
    return buf


MOTIF = [0, 7, 5, 12, 7, 3, 0, -5]   # scale steps over the chord, the film's signature


def plucks() -> np.ndarray:
    """Sixteenths, ten a second, which is roughly the rate the reply in shot one arrives
    at, so the two read as the same event rather than as picture plus music."""
    l, r = np.zeros(N), np.zeros(N)
    n_steps = int(H / STEP)
    scale = [0, 2, 3, 5, 7, 8, 10, 12]   # D natural minor from the root
    for s in range(n_steps):
        t = s * STEP
        sec = section(t)
        if sec == "A" and t < 0.0:
            continue
        _, tri = chord(t)
        deg = MOTIF[s % len(MOTIF)]
        semis = scale[deg % len(scale)] + 12 * (deg // len(scale))
        f = tri[0] * 2 ** (semis / 12)

        if sec == "F":
            # one pluck a bar, wide and quiet: the negative space still has to tick
            if s % 16 != 0:
                continue
            g, brt = 0.15, 0.35
        elif sec in ("A", "B"):
            g, brt = 0.30, 0.85
        elif sec in ("C", "D"):
            g, brt = 0.34, 1.0
        else:
            g, brt = 0.40, 1.15

        v = pluck(f, 0.26, brt, g)
        pan = 0.34 if (s % 2) else -0.34
        if sec in ("A", "B"):
            pan *= 0.45
        elif sec == "G":
            pan *= 1.25
        add(l, t, v * (1 - pan))
        add(r, t, v * (1 + pan))

        # The octave double from B: the same note, quieter, an octave up, panned opposite.
        if sec in ("B", "C", "D", "E", "G") and s % 2 == 0:
            hi = pluck(f * 2, 0.16, 1.3, g * (0.34 if sec != "G" else 0.46))
            add(l, t, hi * (1 + pan))
            add(r, t, hi * (1 - pan))
    return np.stack([l, r])


def lead() -> np.ndarray:
    """The motif in the clear, only in G, where the caption is "You control what goes
    online". It is the one melodic statement in the film and it is saved for the claim the
    product is actually about."""
    l, r = np.zeros(N), np.zeros(N)
    scale = [0, 2, 3, 5, 7, 8, 10, 12]
    figure = [(0.0, 7), (0.2, 5), (0.4, 3), (0.8, 7), (1.2, 12),
              (1.6, 10), (1.8, 7), (2.0, 5), (2.4, 3), (2.8, 0)]
    _, tri = chord(G + 0.1)
    for off, deg in figure:
        t = G + off
        f = tri[0] * 2 ** (scale[deg % 8] / 12 + (deg // 8))
        v = pluck(f, 0.42, 1.5, 0.26) + pluck(f * 1.005, 0.42, 1.4, 0.16)
        add(l, t, v * 1.1)
        add(r, t, v * 0.9)
    return np.stack([l, r])


def pads() -> np.ndarray:
    buf = np.zeros(N)
    for i, (t0, _, tri) in enumerate(CHORDS):
        t1 = CHORDS[i + 1][0] if i + 1 < len(CHORDS) else H
        if t1 <= C:                      # nothing before the pad opens at 6.6
            continue
        t0 = max(t0, C)
        if t1 <= t0:
            continue
        # The privacy section is where the pad becomes the whole arrangement, so it opens
        # up there rather than staying a bed.
        if t0 >= F - 0.5 and t1 <= G + 0.1:
            add(buf, t0, pad_block(t0, t1, tri, 0.30, 90, 2600))
        else:
            add(buf, t0, pad_block(t0, t1, tri, 0.15, 120, 1700))
    return buf


def design() -> np.ndarray:
    """Sound design on picture. Every one of these lands on a measured cut, not on the
    nearest convenient beat."""
    l, r = np.zeros(N), np.zeros(N)

    def stereo(at, sig, spread=0.0, gain=1.0):
        add(l, at, sig * (1 - spread) * gain)
        add(r, at, sig * (1 + spread) * gain)

    # Frame one. The brief asked for a confident hook inside half a second and this is it:
    # impact, and the motif's first three notes before the first bar is out.
    stereo(A, impact(0.85, 1.8))
    stereo(A, sweep(0.6, 5000, 400, up=False, gain=0.16))

    # Every cut gets a transient. The three that fall off the downbeat get a slightly
    # brighter one, so the syncopation reads as intended.
    for t in (B, C, D, E, F, G):
        off_grid = abs((t / BEAT) - round(t / BEAT)) > 1e-6
        stereo(t, impact(0.30 if not off_grid else 0.36, 0.9), 0.05)
        stereo(t, glitch(0.07, 0.16 if off_grid else 0.11), -0.2)

    # A riser into the download beat, and another into the strongest bar. Both end exactly
    # on the frame, which is why they are built backwards from the cut.
    for target, length, g in ((E, 1.0, 0.46), (G, 1.6, 0.72), (H, 0.6, 0.34)):
        stereo(target - length, sweep(length, 380, 9000, up=True, gain=g))
    # A pitch riser doubling the noise one into G. Noise alone sweeps brightness; a tone
    # sweeping a fifth upward is what makes the ear expect a landing.
    n_r = int(1.6 * SR)
    t_r = _t(n_r)
    f_r = 220 * 2 ** (1.6 * t_r / 1.6)
    tone = np.sin(2 * np.pi * np.cumsum(f_r) / SR) * (np.linspace(0, 1, n_r) ** 1.4) * 0.20
    stereo(G - 1.6, tone, 0.1)

    # Out of the download beat and into the privacy card: a downlifter instead of a hit,
    # because the picture there is about something stopping.
    stereo(F, sweep(0.9, 8000, 260, up=False, gain=0.30))
    stereo(F, impact(0.34, 1.6), 0.0)

    # Digital ticks under the telemetry, where the picture is showing numbers.
    for k in range(10):
        stereo(B + 0.2 + k * 0.3, glitch(0.05, 0.075), 0.35 if k % 2 else -0.35)
    for k in range(6):
        stereo(D + 0.15 + k * 0.45, glitch(0.045, 0.06), -0.3 if k % 2 else 0.3)

    # The end card. Hard stop, one impact, the motif in octaves, and a tonic chord that
    # decays under the mark instead of a crescendo into it.
    stereo(H, impact(1.0, 2.6))
    _, tri = chord(H)
    for off, mult in ((0.0, 1.0), (0.0, 2.0), (0.30, 1.5), (0.30, 3.0)):
        stereo(H + off, pluck(tri[0] * mult, 0.9, 1.2, 0.20 / mult ** 0.4), 0.12)
    n = int((DUR - H) * SR)
    t = _t(n)
    chordv = np.zeros(n)
    for f in (D3, F3, A3, D4):
        for cents in (-6, +6):
            chordv += np.sin(2 * np.pi * f * 2 ** (cents / 1200) * t)
    chordv *= np.clip(t / 0.08, 0, 1) * np.exp(-t / 1.9) / 8
    stereo(H, chordv * 0.34)
    sublow = np.sin(2 * np.pi * D2 * t) * np.clip(t / 0.02, 0, 1) * np.exp(-t / 1.5)
    stereo(H, sublow * 0.30)
    return np.stack([l, r])


# -------------------------------------------------------------------------------- mixdown

# The groove builds; the sound design does not. Measured on the first render the sections
# came out within 0.7 dB of each other from the hook to the download, which is layering
# without a build: the clap, the octave plucks, the pad and the sixteenth sub all add parts
# rather than level. This curve gives the parts somewhere to go. It is applied to the kit,
# the bass, the pads and the plucks only, so the impacts and risers still land at full
# strength wherever they land, including the one on frame one.
SECTION_GAIN = {"A": 0.78, "B": 0.86, "C": 0.93, "D": 0.97,
                "E": 1.05, "F": 0.92, "G": 1.12, "H": 1.0}


def groove_curve() -> np.ndarray:
    """Stepped per section, then smoothed over 120 ms so the steps are a swell rather than
    a fader move."""
    g = np.ones(N)
    for name, start in zip("ABCDEFGH", CUTS + [DUR]):
        pass
    bounds = CUTS + [DUR]
    for i, name in enumerate("ABCDEFGH"):
        a, b = int(bounds[i] * SR), int(bounds[i + 1] * SR)
        g[a:b] = SECTION_GAIN[name]
    k = int(0.12 * SR)
    win = np.hanning(k) / np.hanning(k).sum()
    return np.convolve(g, win, mode="same")


def render() -> Path:
    kit, kick_times = drums()
    g = duck(kick_times)

    curve = groove_curve()
    mono = (kit + bassline() * g + pads() * (0.35 + 0.65 * g)) * curve
    stereo = np.stack([mono, mono])
    stereo = stereo + plucks() * (0.55 + 0.45 * g) * curve + lead() + design()

    # Soft knee before the limiter so the impacts round rather than square off.
    stereo = np.tanh(stereo * 0.92) / np.tanh(0.92)
    stereo *= 0.89 / (np.abs(stereo).max() + 1e-9)

    n = int(0.004 * SR)
    stereo[:, :n] *= np.linspace(0, 1, n)
    stereo[:, -n:] *= np.linspace(1, 0, n)

    pcm = (np.clip(stereo.T, -1, 1) * 32767).astype("<i2")
    with wave.open(str(RAW), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return RAW


def _measure(path: Path) -> dict:
    out = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(path), "-af",
         f"loudnorm=I={TARGET_LUFS}:TP={TARGET_TP}:LRA=11:print_format=json",
         "-f", "null", "-"], capture_output=True, text=True).stderr
    import json
    return json.loads(out[out.rindex("{"):out.rindex("}") + 1])


def normalise(raw: Path) -> Path:
    """Two pass loudnorm, linear, so the arrangement's own dynamics survive: a single pass
    is adaptive and would flatten exactly the density drop the privacy section depends on."""
    m = _measure(raw)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(raw), "-af",
         f"loudnorm=I={TARGET_LUFS}:TP={TARGET_TP}:LRA=11:linear=true"
         f":measured_I={m['input_i']}:measured_TP={m['input_tp']}"
         f":measured_LRA={m['input_lra']}:measured_thresh={m['input_thresh']}"
         f":offset={m['target_offset']}:print_format=summary",
         "-ar", str(SR), "-c:a", "pcm_s16le", str(WAV)], check=True)
    return WAV


def main() -> None:
    if not SRC.exists():
        raise SystemExit(f"missing {SRC}; run play/graphics/cut.py first")
    normalise(render())
    RAW.unlink(missing_ok=True)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(SRC), "-i", str(WAV),
         "-map", "0:v", "-map", "1:a", "-c:v", "copy",
         "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart", str(OUT)],
        check=True)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
