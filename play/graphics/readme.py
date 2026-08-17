"""Composes the screenshot strip the README ends with.

Four portrait screens are four tall images, and stacking them makes a README nobody
scrolls to the end of. Side by side they read as one object the width of the page, which is
the shape a phone app's screenshots want on a desktop screen.

Transparent background rather than ink or paper, because GitHub renders a README in whichever
theme the reader chose and a baked-in background is wrong for one of them.

Run after regenerating the raw renders:

    OPENWEIGHTS_SCREENSHOTS=/tmp/shots \
      ./gradlew :app:testDebugUnitTest --tests '*PlayScreenshots*' --rerun-tasks
    python3 play/graphics/readme.py /tmp/shots
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).parent

# Four, not five: the fifth store screenshot is the Tools screen again at a different scroll
# position, which says nothing new on a strip this size.
SCREENS = ["01-chat", "04-discover", "02-tools", "03-plan"]

# The header logo, shipped at the size it is displayed at.
#
# GitHub does not rewrite a relative `src` inside a raw <img> tag: the rendered README keeps
# `play/graphics/icon-512.png`, which the browser resolves against the repository page and
# 404s. Markdown image syntax *is* rewritten, so the README uses that, and markdown carries no
# width attribute. Hence a second file at the display size rather than the 512 icon scaled by
# an attribute that cannot be used.
LOGO = 128

SHOT_W = 320
GAP = 24
RADIUS = 26
# A hairline so a dark screenshot has an edge on a dark page, and a light one on a light page.
# Neutral grey at low alpha reads as a border in both.
EDGE = (128, 128, 128, 90)


def rounded(image: Image.Image, radius: int) -> Image.Image:
    """The screen with phone-shaped corners, so it reads as a device rather than a crop."""
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, *[n - 1 for n in image.size]], radius, fill=255)
    out = Image.new("RGBA", image.size, (0, 0, 0, 0))
    out.paste(image, (0, 0), mask)

    edge = Image.new("RGBA", image.size, (0, 0, 0, 0))
    ImageDraw.Draw(edge).rounded_rectangle(
        [0, 0, *[n - 1 for n in image.size]], radius, outline=EDGE, width=2
    )
    return Image.alpha_composite(out, edge)


def strip(source: Path) -> Image.Image:
    shots = []
    for name in SCREENS:
        image = Image.open(source / f"{name}.png").convert("RGBA")
        height = round(image.height * SHOT_W / image.width)
        shots.append(rounded(image.resize((SHOT_W, height), Image.LANCZOS), RADIUS))

    width = len(shots) * SHOT_W + (len(shots) - 1) * GAP
    canvas = Image.new("RGBA", (width, max(s.height for s in shots)), (0, 0, 0, 0))
    for index, shot in enumerate(shots):
        canvas.paste(shot, (index * (SHOT_W + GAP), 0), shot)
    return canvas


if __name__ == "__main__":
    source = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/shots")

    strip_out = HERE / "readme-screens.png"
    image = strip(source)
    image.save(strip_out)
    print(f"wrote {strip_out.name}: {image.width}x{image.height}")

    logo_out = HERE / "readme-logo.png"
    icon = Image.open(HERE / "icon-512.png").convert("RGBA")
    icon.resize((LOGO, LOGO), Image.LANCZOS).save(logo_out)
    print(f"wrote {logo_out.name}: {LOGO}x{LOGO}")
