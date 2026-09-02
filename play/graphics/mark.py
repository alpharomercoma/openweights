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

# A chip: the app's raised surface over ink, with the hairline it draws round a row.
CHIP = "#0B3A57"
CHIP_EDGE = "#1F5675"
CHIP_H = 44
CHIP_PAD = 16

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

    The lockup on the left, and on the right the one fact the second runtime changed
    about the product: the same phone now runs weights in two formats. Six family chips
    say which models, two mono lines say which engine opens which file, and one line says
    what the app does not do. Asymmetric on purpose: a centred logo over a tagline is the
    shape every assistant app ships and says nothing about this one. Play crops this hard
    on some surfaces, so nothing that matters goes near an edge and no part of it is a
    screenshot.

    Every family named here has been run on the test phone through both engines, per
    docs/research/executorch-families.md. A chip for a family the app cannot open would
    be the one thing on this image a reviewer could hold against it.
    """
    image = Image.new("RGB", (1024, 500), INK)
    draw = ImageDraw.Draw(image)

    # The lockup. The mark's left edge and the wordmark's left edge are the same x: they
    # are two halves of one object, and anything else reads as two things that happen to
    # be near each other, which is how the first pass came out.
    left = 72
    longest = 158
    draw_mark(draw, cx=left + longest / 2, cy=188, longest=longest)
    draw.text((left, 268), "OpenWeights", font=font("schibsted_grotesk.ttf", 50, 700),
              fill=TEXT_ON_INK)
    draw.text((left + 2, 332), "Offline AI chat, open-weight models",
              font=font("hanken_grotesk.ttf", 22, 400), fill=DIM_ON_INK)

    # The spine sits at 500 rather than at the halfway line: the right column is the
    # longer one and running it past 1000 is how an earlier pass lost the end of two
    # claims.
    draw.rounded_rectangle([500, 112, 504, 388], radius=2, fill=LIME)

    column = 538
    eyebrow = font("geist_mono.ttf", 17, 500)
    draw.text((column, 116), "TWO RUNTIMES · ONE PHONE", font=eyebrow, fill=LIME)

    # Six chips, two rows. A chip is a label the app itself uses for a model family, in
    # the raised tone the app draws a row in, so the listing and a first launch look like
    # the same product.
    chip_font = font("hanken_grotesk.ttf", 24, 500)
    x, y = column, 156
    for row in (("LFM2.5", "Qwen3", "Gemma 3"), ("Llama 3.2", "Phi-4 mini", "SmolLM3")):
        x = column
        for label in row:
            width = draw.textlength(label, font=chip_font) + 2 * CHIP_PAD
            draw.rounded_rectangle([x, y, x + width, y + CHIP_H], radius=CHIP_H / 2,
                                   fill=CHIP, outline=CHIP_EDGE, width=1)
            draw.text((x + CHIP_PAD, y + 9), label, font=chip_font, fill=TEXT_ON_INK)
            x += width + 12
        y += CHIP_H + 12

    # Which file goes to which engine, in the app's mono because it is the face the app
    # gives to formats and numbers. Two columns aligned on the engine name.
    mono = font("geist_mono.ttf", 20, 500)
    for index, (fmt, engine) in enumerate((("GGUF", "llama.cpp"), (".pte", "ExecuTorch"))):
        line_y = 276 + index * 32
        draw.text((column, line_y), fmt, font=mono, fill=LIME)
        draw.text((column + 84, line_y), engine, font=mono, fill=TEXT_ON_INK)

    draw.text((column, 352), "No account, no server, no telemetry",
              font=font("hanken_grotesk.ttf", 24, 500), fill=DIM_ON_INK)

    image.save(HERE / "feature-graphic-1024x500.png")


if __name__ == "__main__":
    icon()
    feature_graphic()
    print("wrote icon-512.png and feature-graphic-1024x500.png")
