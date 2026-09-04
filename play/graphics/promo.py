"""Compose the Play listing video from real screen captures.

Design decisions, all forced by how Play actually shows this file:

- It autoplays INLINE and MUTED for up to 30 seconds, so the cut stays under 30 s, says
  everything in type, and carries only a silent track. No music: a Content ID claim can
  force ads onto a video and Play requires ads off, so silence is compliance before taste.
- It plays in a LANDSCAPE player, so the frame is 1920x1080.
- The FEATURE GRAPHIC is its cover image, so the lime, the type and the mark are the ones
  `mark.py` already draws, and the mark itself is imported from there rather than redrawn.

On the ground colour. The feature graphic sits on flat brand ink (#052B42), but the app's
own dark surface is a neutral near-black (OpenWeightsColors.Canvas, #0D0E10). Laying real
capture on flat ink put a neutral rectangle on a saturated navy field and the seam showed.
The ground here is a deep ink gradient instead: dark enough that the capture settles into
it, blue enough to stay in the same family as the cover art. The capture sits on a rounded
card with a hairline and a soft shadow, the way a raised surface works inside the app, so
its edge is a designed edge rather than a crop boundary.

The UI is the argument, so the UI gets the frame: each shot is a landscape crop of real
capture across the top of the frame, with one claim under it. Nothing is re-enacted.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mark import draw_mark  # noqa: E402  the one true geometry for the three bars

W, H = 1920, 1080
LIME = "#E0FF4F"
TEXT_ON_INK = "#F5F6F3"      # OpenWeightsColors.Text
DIM_ON_INK = "#A2A4AB"       # OpenWeightsColors.TextDim
GROUND_TOP = (6, 32, 47)     # a deep ink, lit slightly from the top
GROUND_BOTTOM = (3, 16, 26)
CARD_EDGE = "#17435F"        # ink lifted just enough to bound the capture
CORNER = 26

FONTS = Path(__file__).resolve().parents[2] / "core/designsystem/src/main/res/font"
DISPLAY = str(FONTS / "schibsted_grotesk.ttf")
MONO = str(FONTS / "geist_mono.ttf")

# One card, the same size and place in every shot, and every crop cut to its aspect. The
# earlier version sized the card from each crop, so the card, the lime rule and the caption
# column each sat on a different grid: the rule ran to a fixed margin while a tall crop's
# card stopped 130 px short of it, and the block left 100 px of dead space at the bottom
# against 74 at the top. Nothing lined up because nothing shared a grid. Now one rectangle
# governs the card, the rule and both caption edges, and the whole stack is centred.
CARD_W, CARD_H = 1560, 700
CARD_X = (W - CARD_W) // 2                 # 180
CARD_Y = 96
EYEBROW_Y = CARD_Y + CARD_H + 56           # 848
RULE_Y = EYEBROW_Y + 40                    # 888
HEAD_Y = RULE_Y + 34                       # 922
SUB_Y = RULE_Y + 44
MARGIN = CARD_X
FPS = 30
# Measured on a render rather than assumed: the headline's ink ends 58 px below HEAD_Y, so
# the content runs CARD_Y .. CARD_Y + 888. Centring that in 1080 puts CARD_Y at 96, which
# leaves 96 above and 95 below. One pixel is what integers allow.


def weighted(path: str, size: int, weight: int) -> ImageFont.FreeTypeFont:
    """One variable TTF per family, set through its weight axis, as the app does."""
    face = ImageFont.truetype(path, size)
    try:
        face.set_variation_by_axes([weight])
    except (OSError, ValueError):
        pass
    return face


def tracked(draw: ImageDraw.ImageDraw, xy, text: str, font, fill: str, extra: float) -> None:
    """Letter-spaced text. Pillow has no tracking, so the eyebrow is drawn per glyph."""
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill)
        x += draw.textlength(ch, font=font) + extra


CARD_ASPECT = CARD_W / CARD_H              # 2.2286


def fit(crop: tuple[int, int, int, int] | None = None) -> tuple[int, int, int, int]:
    """The card. Always the same rectangle: every crop is cut to CARD_ASPECT so it fills it
    exactly, which is what keeps the grid honest from shot to shot."""
    return CARD_X, CARD_Y, CARD_W, CARD_H


def ground() -> Image.Image:
    """The field: a vertical gradient, one pixel wide and stretched."""
    base = Image.new("RGB", (1, H))
    px = base.load()
    for y in range(H):
        t = y / (H - 1)
        px[0, y] = tuple(round(a + (b - a) * t) for a, b in zip(GROUND_TOP, GROUND_BOTTOM))
    return base.resize((W, H))


def mask_png(w: int, h: int, out: Path) -> Path:
    """A rounded-corner alpha mask for the capture, so the shot carries the app's own
    corner radius instead of a hard rectangular crop edge."""
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], radius=CORNER, fill=255)
    m.save(out)
    return out


_BACKDROP: dict[tuple, Image.Image] = {}


def backdrop(rect) -> Image.Image:
    """Field plus the card's shadow. Identical for every frame of a beat, and the typed
    opening asks for 180 of them, so it is blurred once and kept."""
    if rect not in _BACKDROP:
        x, y, w, h = rect
        image = ground()
        shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle(
            [x - 8, y + 8, x + w + 8, y + h + 20], radius=CORNER + 10, fill=(2, 12, 20, 110))
        _BACKDROP[rect] = Image.alpha_composite(
            image.convert("RGBA"), shadow.filter(ImageFilter.GaussianBlur(34))).convert("RGB")
    return _BACKDROP[rect]


def plate(eyebrow: str, headline: str, sub: str, rect, out: Path,
          typed: int | None = None) -> Path:
    """The field, the card the capture sits in, the lime rule, and one claim."""
    x, y, w, h = rect
    image = backdrop(tuple(rect)).copy()
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle([x - 1, y - 1, x + w, y + h], radius=CORNER + 1,
                           outline=CARD_EDGE, width=2)
    # The rule spans exactly the card, so its ends and the card's edges are one line.
    draw.rounded_rectangle([x, RULE_Y, x + w, RULE_Y + 3], radius=2, fill=LIME)

    eyebrow_font = weighted(MONO, 25, 500)
    head_font = weighted(DISPLAY, 50, 700)
    # Two families, not three. Geist Mono for the eyebrow because the app sets its own
    # telemetry in it; Schibsted for headline and subhead, separated by weight rather than
    # by a third face, which in a three-line block read as a ransom note.
    # 32, not 27. Simulated at 390 px, the width a full-bleed listing video gets on a
    # phone, a 27 px subhead lands at about 5.5 px and is decorative rather than readable.
    # It cannot be made readable at that size by any type choice: only the 50 px headline
    # survives the reduction. 32 with shorter strings is the honest improvement, and the
    # subhead's real audience is the viewer who taps to full screen.
    sub_font = weighted(DISPLAY, 35, 400)

    tracked(draw, (x, EYEBROW_Y), eyebrow.upper(), eyebrow_font, LIME, 3.0)

    shown = headline if typed is None else headline[:typed]
    draw.text((x, HEAD_Y), shown, font=head_font, fill=TEXT_ON_INK)
    if typed is not None and typed < len(headline):
        cursor = x + draw.textlength(shown, font=head_font) + 6
        draw.rectangle([cursor, HEAD_Y + 12, cursor + 20, HEAD_Y + 58], fill=LIME)

    sub_w = draw.textlength(sub, font=sub_font)
    head_w = draw.textlength(headline, font=head_font)
    if head_w + 60 + sub_w > w:  # they share one line; a collision must fail loudly
        raise ValueError(f"caption overflows the card: {headline!r} + {sub!r}")
    draw.text((x + w - sub_w, SUB_Y), sub, font=sub_font, fill=DIM_ON_INK)
    image.save(out)
    return out


def endcard(out: Path) -> Path:
    """The mark comes from mark.py so it carries the real geometry: bar height is 0.22 of
    the longest bar and the pitch 0.32. A hand-rolled copy got both wrong and drew the
    bars long and thin."""
    image = ground()
    draw = ImageDraw.Draw(image)
    draw_mark(draw, W / 2, H / 2 - 86, 300, LIME)
    name = weighted(DISPLAY, 84, 700)
    line = weighted(DISPLAY, 36, 400)
    draw.text(((W - draw.textlength("OpenWeights", font=name)) / 2, H / 2 + 54),
              "OpenWeights", font=name, fill=TEXT_ON_INK)
    tag = "Open-weight AI. On your device. On your terms."
    draw.text(((W - draw.textlength(tag, font=line)) / 2, H / 2 + 166),
              tag, font=line, fill=DIM_ON_INK)

    # The repository, in the app's own mono and in lime, which is its action colour. No
    # "Available on Google Play" badge: this video plays ON the Play listing, so a badge
    # saying where to get it points at the page the viewer is already standing on. The URL
    # earns its place because it says something the listing cannot otherwise show, that the
    # thing making these claims is readable source, and because the full description already
    # carries the same address.
    repo = weighted(MONO, 27, 500)
    draw.text(((W - draw.textlength("github.com/alpharomercoma/openweights", font=repo)) / 2,
               H / 2 + 250), "github.com/alpharomercoma/openweights", font=repo, fill=LIME)
    image.save(out)
    return out


def _shot_chain(crop, w, h, zoom: float, length: float) -> str:
    # setpts first, always. Seeking with -ss leaves the shot's timestamps offset by the
    # seek, so overlay finds no frame inside the plate's 0..length window and silently
    # composites nothing: the beat renders as an empty card with its caption intact. The
    # bug hid at first because zoompan re-times its own output, so only un-zoomed shots
    # went blank.
    chain = ["setpts=PTS-STARTPTS", f"fps={FPS}"]
    if crop:
        chain.append(f"crop={crop[2]}:{crop[3]}:{crop[0]}:{crop[1]}")
    if zoom > 1.0:
        # fps first: zoompan emits one frame per frame it is handed, so feeding it 60 fps
        # capture while asking for 30 fps out stretches every shot to twice its length.
        frames = max(int(length * FPS), 2)
        chain.append(f"scale={w * 2}:{h * 2}")
        chain.append(f"zoompan=z='1+({zoom} - 1)*on/{frames}'"
                     f":x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
                     f":d=1:s={w}x{h}:fps={FPS}")
    else:
        chain.append(f"scale={w}:{h}:flags=lanczos")  # crop is cut to CARD_ASPECT
    return ",".join(chain)


def _render(plate_input: list[str], source: Path, start: float, length: float,
            out: Path, crop, zoom: float, mask: Path) -> Path:
    x, y, w, h = fit(crop)
    # A still source is a frame already pulled from a capture. Held views exist because a
    # crop into a moving list cannot be framed: wherever the cut falls, some card is sliced
    # and the gap above it stops matching the gap below. A held frame can be centred exactly
    # on the thing it is showing, and the cut between two held views carries the change.
    shot_input = (["-loop", "1", "-t", f"{length}", "-i", str(source)]
                  if source.suffix == ".png"
                  else ["-ss", f"{start}", "-t", f"{length}", "-i", str(source)])
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", *plate_input, *shot_input,
         "-loop", "1", "-t", f"{length}", "-i", str(mask),
         "-filter_complex",
         f"[1:v]{_shot_chain(crop, w, h, zoom, length)}[shot];"
         f"[2:v]fps={FPS},format=gray[m];"
         f"[shot][m]alphamerge[rounded];"
         f"[0:v][rounded]overlay={x}:{y}:format=auto[v]",
         "-map", "[v]", "-an", "-c:v", "libx264", "-preset", "slow", "-crf", "18",
         "-pix_fmt", "yuv420p", "-r", str(FPS), "-t", f"{length}", str(out)],
        check=True)
    return out


def beat(source: Path, start: float, length: float, plate_png: Path, out: Path,
         mask: Path, crop=None, zoom: float = 1.0) -> Path:
    return _render(["-loop", "1", "-t", f"{length}", "-i", str(plate_png)],
                   source, start, length, out, crop, zoom, mask)


def beat_typed(source: Path, start: float, length: float, seq_dir: Path, out: Path,
               eyebrow: str, headline: str, sub: str, mask: Path, crop=None,
               zoom: float = 1.0, chars_per_second: float = 120.0) -> Path:
    """The opening shot, whose headline types on at the model's own decode rate.

    120 characters a second, not 30: the app is decoding about 30 TOKENS a second in this
    very shot and a token averages roughly four characters, so 120 cps is the honest match.
    It also finishes in about 0.17 s, which matters more than the conceit does. An inline
    muted autoplay punishes a headline the viewer cannot read immediately, and the eyebrow,
    the subhead and the streaming reply are all already on screen at frame one to anchor it.
    """
    seq_dir.mkdir(parents=True, exist_ok=True)
    rect = fit(crop)
    for i in range(int(length * FPS)):
        n = min(len(headline), int(i / FPS * chars_per_second))
        plate(eyebrow, headline, sub, rect, seq_dir / f"{i:04d}.png",
              typed=None if n >= len(headline) else n)
    return _render(["-framerate", str(FPS), "-i", str(seq_dir / "%04d.png")],
                   source, start, length, out, crop, zoom, mask)


def still(plate_png: Path, length: float, out: Path) -> Path:
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-loop", "1", "-t", f"{length}", "-i", str(plate_png),
         "-vf", f"fps={FPS}", "-an", "-c:v", "libx264", "-preset", "slow", "-crf", "18",
         "-pix_fmt", "yuv420p", "-r", str(FPS), str(out)],
        check=True)
    return out


def _silence(video: Path) -> Path:
    """Mux a silent AAC track, deliberately, at about -91 dB of encoder noise around true
    digital silence: inaudible, below the 16-bit floor.

    The decision was made rather than inherited. Play autoplays this muted, so on the
    surface that matters there is no audio either way, and stripping the track would not
    change what a viewer hears: silence is silence, with or without a stream. What it would
    change is the container, and one with no audio stream is what a strict transcoder or
    player refuses. Music is out on policy, not taste: a Content ID claim can force ads onto
    a video, and Play requires ads off, so a claimed track could put the listing out of
    compliance. Foley is out because there is nothing to sound: the shots are held frames
    and arriving text, with no taps or transitions, so clicks would be invented events, the
    audio equivalent of staging footage."""
    tmp = video.with_name(video.stem + "-a.mp4")
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(video),
         "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
         "-c:v", "copy", "-c:a", "aac", "-b:a", "64k", "-shortest",
         "-movflags", "+faststart", str(tmp)],
        check=True)
    tmp.replace(video)
    return video


def join(clips: list[Path], out: Path, fade: float = 0.0) -> Path:
    """Hard cuts by default. A dissolve between two shots also dissolves their captions, so
    for a few frames the viewer reads both at once, and on UI footage the blend reads as
    mush rather than as motion."""
    if fade <= 0:
        listing = out.with_suffix(".txt")
        listing.write_text("".join(f"file '{c}'\n" for c in clips))
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0",
             "-i", str(listing), "-c", "copy", "-movflags", "+faststart", str(out)],
            check=True)
        listing.unlink()
        return _silence(out)

    durations = [float(subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", str(c)], capture_output=True, text=True).stdout.strip())
        for c in clips]
    args, filters, last, offset = [], [], "0:v", 0.0
    for clip in clips:
        args += ["-i", str(clip)]
    for i in range(1, len(clips)):
        offset += durations[i - 1] - fade
        filters.append(
            f"[{last}][{i}:v]xfade=transition=fade:duration={fade}:offset={offset:.3f}[x{i}]")
        last = f"x{i}"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", *args, "-filter_complex", ";".join(filters),
         "-map", f"[{last}]", "-an", "-c:v", "libx264", "-preset", "slow", "-crf", "18",
         "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out)],
        check=True)
    return _silence(out)
