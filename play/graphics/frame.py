#!/usr/bin/env python3
"""Put each rendered screen into a captioned 1080x1920 frame.

The raw renders are already the size and ratio Play wants, so the frame is not fixing a
compliance problem; it is the only place the listing gets to say what the screen is for. The
caption does that job and the screen underneath proves it.

Palette and type are the app's own, from docs/design/visual-language.md, so a listing page
and a first launch look like the same product.
"""
from pathlib import Path

from PIL import Image, ImageColor, ImageDraw, ImageFont

W, H = 1080, 1920          # the phone canvas; tablets scale from it
CANVAS = "#0B0D0F"
LIFT = "#232A31"          # the radial the icon and feature graphic also sit on
TEXT = "#ECF1F4"
MUTED = "#9AA6AF"
EDGE = "#262D34"

FONTS = Path("/Users/alpha/mobile-inference/core/designsystem/src/main/res/font")
SANS = str(FONTS / "plex_sans.ttf")
MONO = str(FONTS / "plex_mono_regular.ttf")

SHOT_W = 828                       # 0.767 of the source, which keeps body text readable
SHOT_TOP = 396
RADIUS = 30

CAPTIONS = [
    ("01-chat", "Real numbers, in front of you", "Tokens per second and context fill, live"),
    ("02-tools", "An assistant that can act", "Search the web, read a page, use your files"),
    ("03-plan", "Ask it to plan first", "It proposes the steps. You tick them off."),
    ("04-discover", "Any model, not a catalogue", "Search Hugging Face and run any GGUF"),
    ("05-tools", "Every tool has an off switch", "And each says if it asks before running"),
]


def background() -> Image.Image:
    """Graphite with a soft lift behind the caption, drawn small and scaled for smoothness."""
    base = ImageColor.getrgb(CANVAS)
    lift = ImageColor.getrgb(LIFT)
    small = Image.new("RGB", (108, 192), CANVAS)
    pixels = small.load()
    cx, cy, reach = 54, 30, 165
    for y in range(192):
        for x in range(108):
            distance = ((x - cx) ** 2 + ((y - cy) * 0.85) ** 2) ** 0.5
            weight = max(0.0, 1.0 - distance / reach) ** 2
            pixels[x, y] = tuple(
                round(a + (b - a) * weight) for a, b in zip(base, lift)
            )
    return small.resize((W, H), Image.LANCZOS)


def rounded(image: Image.Image, radius: int) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, image.size[0] - 1, image.size[1] - 1],
                                           radius=radius, fill=255)
    out = image.convert("RGBA")
    out.putalpha(mask)
    return out


def wrap(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, limit: int):
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if draw.textlength(trial, font=font) <= limit:
            line = trial
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def frame(source: Path, out: Path, headline: str, sub: str) -> None:
    canvas = background()
    draw = ImageDraw.Draw(canvas)

    scale = W / 1080
    head_font = ImageFont.truetype(SANS, round(58 * scale))
    sub_font = ImageFont.truetype(MONO, round(27 * scale))

    y = round(132 * scale)
    for line in wrap(draw, headline, head_font, W - round(2 * 126 * scale)):
        draw.text((round(126 * scale), y), line, font=head_font, fill=TEXT)
        y += round(74 * scale)
    y += round(14 * scale)
    for line in wrap(draw, sub, sub_font, W - round(2 * 126 * scale)):
        draw.text((round(126 * scale), y), line, font=sub_font, fill=MUTED)
        y += round(40 * scale)

    shot = Image.open(source).convert("RGB")
    height = round(shot.height * SHOT_W / shot.width)
    shot = shot.resize((SHOT_W, height), Image.LANCZOS)
    # Cropped to whatever is left under the caption rather than squeezed, so the app's own
    # proportions survive: a screenshot squashed to fit a box is a screenshot of a phone
    # nobody owns.
    shot = shot.crop((0, 0, SHOT_W, min(height, H - SHOT_TOP)))

    x = (W - SHOT_W) // 2
    canvas.paste(rounded(shot, RADIUS), (x, SHOT_TOP), rounded(shot, RADIUS))
    ImageDraw.Draw(canvas).rounded_rectangle(
        [x, SHOT_TOP, x + SHOT_W - 1, SHOT_TOP + shot.height - 1],
        radius=RADIUS, outline=EDGE, width=2,
    )

    canvas.convert("RGB").save(out, "PNG", optimize=True)
    print(f"{out.name}: {canvas.size[0]}x{canvas.size[1]} {out.stat().st_size} bytes")


def scaled(width: int, height: int) -> None:
    """Retarget the canvas, so one set of proportions serves every slot Play asks for."""
    global W, H, SHOT_W, SHOT_TOP, RADIUS
    factor = width / 1080
    W, H = width, height
    SHOT_W = round(828 * factor)
    SHOT_TOP = round(396 * factor)
    RADIUS = round(30 * factor)


if __name__ == "__main__":
    import sys

    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    if len(sys.argv) > 4:
        scaled(int(sys.argv[3]), int(sys.argv[4]))
    dst.mkdir(parents=True, exist_ok=True)
    for name, headline, sub in CAPTIONS:
        source = src / f"{name}.png"
        if source.exists():
            frame(source, dst / f"{name}.png", headline, sub)
