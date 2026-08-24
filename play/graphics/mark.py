#!/usr/bin/env python3
"""Draw the mark, and the two pieces of listing art built out of it.

    python3 play/graphics/mark.py

Writes `icon-512.png` and `feature-graphic-1024x500.png` beside this file.

The icon used to be HTML rendered by headless Chrome, with the source living in a session
scratchpad that no longer exists, so the only record of it was a paragraph of prose in the
README. Everything here is Pillow and thirty lines of arithmetic, which is the amount of
machinery this drawing deserves and keeps it reproducible from the repository alone.

The mark is five numbers, and they are the same five in Mark.kt and in
ic_launcher_foreground.xml: three bars at 1, 0.7 and 0.41 of the longest, 0.22 as thick as
the longest is long, 0.32 apart centre to centre.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
FONTS = HERE.parent.parent / "core/designsystem/src/main/res/font"

# The palette, from core/designsystem/.../theme/Color.kt.
INK = "#052B42"
LIME = "#E0FF4F"
TEXT_ON_INK = "#F5F6F3"
DIM_ON_INK = "#A2A4AB"

# The mark, as fractions of the longest bar.
BARS = (1.0, 0.70, 0.41)
BAR_HEIGHT = 0.22
BAR_PITCH = 0.32


def draw_mark(canvas: ImageDraw.ImageDraw, cx: float, cy: float, longest: float,
              colour: str = LIME) -> None:
    """Three stadium bars, left aligned, centred on (cx, cy)."""
    height = longest * BAR_HEIGHT
    pitch = longest * BAR_PITCH
    left = cx - longest / 2
    for index, width in enumerate(BARS):
        top = cy + (index - 1) * pitch - height / 2
        canvas.rounded_rectangle(
            [left, top, left + longest * width, top + height],
            radius=height / 2,
            fill=colour,
        )


def font(name: str, size: int, weight: int) -> ImageFont.FreeTypeFont:
    """A named weight of one of the app's variable faces.

    Every face in `res/font` is a single variable TTF with a weight axis, which is what lets
    the app declare four weights per family from one file. Pillow reaches the same axis
    through FreeType, so the listing art is set in exactly the instances the app renders in
    rather than in whatever the default instance happens to be.
    """
    face = ImageFont.truetype(str(FONTS / name), size)
    try:
        face.set_variation_by_axes([weight])
    except OSError:
        pass  # A static build of the face. It will render at its one weight.
    return face


def icon() -> None:
    """512 square, full bleed. Play rounds and masks it however the surface wants."""
    image = Image.new("RGBA", (512, 512), INK)
    draw_mark(ImageDraw.Draw(image), cx=256, cy=256, longest=272)
    image.save(HERE / "icon-512.png")


def feature_graphic() -> None:
    """1024 x 500, no alpha.

    The lockup on the left and three claims on a lime spine, asymmetric on purpose: a
    centred logo over a tagline is the shape every assistant app ships and it says nothing
    about this one. Play crops this hard on some surfaces, so nothing that matters goes
    near an edge and no part of it is a screenshot.
    """
    image = Image.new("RGB", (1024, 500), INK)
    draw = ImageDraw.Draw(image)

    # The mark's left edge and the wordmark's left edge are the same x. They are the two
    # halves of one lockup and anything else reads as two things that happen to be near
    # each other, which is exactly how the first pass came out.
    left = 72
    longest = 158
    draw_mark(draw, cx=left + longest / 2, cy=188, longest=longest)
    draw.text((left, 268), "OpenWeights", font=font("schibsted_grotesk.ttf", 50, 700),
              fill=TEXT_ON_INK)
    draw.text((left + 2, 332), "Open weight models, on your phone",
              font=font("hanken_grotesk.ttf", 22, 400), fill=DIM_ON_INK)

    # The spine sits at 500 rather than at the halfway line: the claims are the longer
    # column and running them past 1000 is how the first pass lost the end of two of them.
    draw.rounded_rectangle([500, 130, 504, 376], radius=2, fill=LIME)
    # The first line has to say where the work happens without repeating the subtitle two
    # inches to its left, which already says "on your phone". Naming the processor is what
    # does that, and it is also the one the app can be held to: the top bar says CPU or
    # OPENCL for the model in memory, and the sampler sheet lets you move it.
    #
    # It read "Runs on the processor in your hand", which is a phrase about a phone rather
    # than about this app, and is the sort of line that sounds like a claim while making
    # none. "or" rather than "and": layers go to one or the other, never to both at once,
    # and that is a distinction this project spent a week measuring.
    claims = [
        "CPU or GPU where supported",
        "No account, no server, no telemetry",
        "Run supported GGUF models locally",
    ]
    for index, claim in enumerate(claims):
        draw.text((538, 146 + index * 90), claim,
                  font=font("hanken_grotesk.ttf", 26, 500), fill=TEXT_ON_INK)

    image.save(HERE / "feature-graphic-1024x500.png")


if __name__ == "__main__":
    icon()
    feature_graphic()
    print("wrote icon-512.png and feature-graphic-1024x500.png")
