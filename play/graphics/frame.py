#!/usr/bin/env python3
"""Put each rendered screen into a captioned 1080x1920 frame.

The raw renders are already the size and ratio Play wants, so the frame is not fixing a
compliance problem; it is the only place the listing gets to say what the screen is for. The
caption does that job and the screen underneath proves it.

Palette and type are the app's own, from core/designsystem, so a listing page and a first
launch look like the same product.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 1920          # the phone canvas; tablets scale from it
CANVAS = "#0D0E10"
TEXT = "#F5F6F3"
MUTED = "#A2A4AB"
EDGE = "#26272A"
ACCENT = "#E0FF4F"

FONTS = Path(__file__).resolve().parents[2] / "core/designsystem/src/main/res/font"
DISPLAY = str(FONTS / "schibsted_grotesk.ttf")
BODY = str(FONTS / "hanken_grotesk.ttf")

SHOT_W = 828                       # 0.767 of the source, which keeps body text readable
SHOT_TOP = 396
RADIUS = 30

CAPTIONS = [
    ("01-chat", "Real numbers, in front of you", "Tokens per second and context fill, live"),
    ("02-tools", "An assistant that can act", "Search the web, read a page, use your files"),
    ("03-plan", "Ask it to plan first", "It proposes the steps. You tick them off."),
    ("04-discover", "Browse the Hub, not a fixed catalogue", "Inspect fit and run supported GGUF models"),
    ("05-tools", "Every tool has an off switch", "Grouped by whether using one leaves the phone"),
]


def background() -> Image.Image:
    """Flat canvas.

    It was a radial lift behind the caption, matching a launcher icon that was itself a
    radial. Both are flat now: a gradient behind a two line caption is depth that nothing
    on the page uses, and next to a screenshot of a flat interface it read as a smudge.
    """
    return Image.new("RGB", (W, H), CANVAS)


def rounded(image: Image.Image, radius: int) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, image.size[0] - 1, image.size[1] - 1],
                                           radius=radius, fill=255)
    out = image.convert("RGBA")
    out.putalpha(mask)
    return out


def weighted(path: str, size: int, weight: int) -> ImageFont.FreeTypeFont:
    """A named weight of one of the app's variable faces.

    Each family in `res/font` is one variable TTF with a weight axis, which is how the app
    declares four weights per family from a single file. Pillow reaches the same axis, so a
    caption is set in the instance the app actually renders in.
    """
    face = ImageFont.truetype(path, size)
    try:
        face.set_variation_by_axes([weight])
    except OSError:
        pass  # A static build of the face. It will render at its one weight.
    return face


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
    head_font = weighted(DISPLAY, round(58 * scale), 700)
    sub_font = weighted(BODY, round(28 * scale), 400)

    # A short accent rule over the headline. The one lime thing on the page, and the only
    # mark that says these five images belong to each other when Play shows them in a row.
    draw.rounded_rectangle(
        [round(126 * scale), round(104 * scale), round(180 * scale), round(109 * scale)],
        radius=round(3 * scale), fill=ACCENT,
    )

    y = round(148 * scale)
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
