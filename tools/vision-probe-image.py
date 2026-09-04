"""Draws the picture the image-token measurement is taken against.

A phone screenshot rather than a photograph, because that is the hard case and the
common one: the answer depends on small text, which is the first thing to go when the
projector is given fewer tokens to describe the picture with. Three text sizes, so the
measurement says where legibility breaks rather than only that it did.
"""
from PIL import Image, ImageDraw, ImageFont
import sys

W, H = 1080, 2400
BG = (13, 14, 16)
FG = (235, 236, 238)
DIM = (150, 154, 160)
LIME = (198, 240, 90)

def font(size):
    for path in (
        "/System/Library/Fonts/Supplemental/Arial.ttf",
        "/System/Library/Fonts/Helvetica.ttc",
    ):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default(size)

im = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(im)

# A large heading: legible at any budget, the control.
d.text((60, 120), "Quarterly Report", font=font(72), fill=FG)
# Mid: the interesting one.
d.text((60, 240), "Region: Kanto  ·  Owner: Marisol Reyes", font=font(40), fill=DIM)

# A coloured shape with an unambiguous name, so a wrong answer is obvious.
d.ellipse([(760, 110), (1000, 350)], fill=LIME)
d.text((800, 200), "42", font=font(80), fill=(13, 14, 16))

# A table of small text: what a 64-token budget cannot read and a 256-token one can.
rows = [
    ("Widgets", "18,402", "+7.1%"),
    ("Sprockets", "9,115", "-2.4%"),
    ("Flanges", "31,760", "+12.8%"),
    ("Grommets", "4,038", "+0.3%"),
]
y = 420
d.text((60, y), "Product", font=font(30), fill=DIM)
d.text((520, y), "Units", font=font(30), fill=DIM)
d.text((820, y), "Change", font=font(30), fill=DIM)
y += 56
for name, units, change in rows:
    d.text((60, y), name, font=font(34), fill=FG)
    d.text((520, y), units, font=font(34), fill=FG)
    d.text((820, y), change, font=font(34), fill=FG)
    y += 58

# A paragraph of body copy, the size a phone actually renders prose at.
para = (
    "The pilot ran for eleven weeks across four sites. Uptake was highest in\n"
    "the second cohort, where onboarding was shortened to a single session.\n"
    "The finance note in appendix C revises the unit cost down to 3.40 per\n"
    "shipment, which is the figure used throughout this summary."
)
d.multiline_text((60, y + 40), para, font=font(32), fill=DIM, spacing=14)

# The smallest text on the page, and a fact only found here.
d.text((60, H - 120), "Build 5071 · verification code TANGERINE", font=font(24), fill=DIM)

im.save(sys.argv[1])
print("wrote", sys.argv[1], im.size)
