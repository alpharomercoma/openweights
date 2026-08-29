#!/usr/bin/env python3
#
# Copyright 2026 The OpenWeights Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Draws the second picture `MultimodalTest` looks at.

The two-attachment case needs a shape that cannot be confused with the first one in either
of the ways a model can cheat: a triangle is not an octagon and blue is not red, so an
answer naming both shapes is an answer that read both files. Sent second, so a model that
only ever sees the first picture names the octagon twice and fails on the triangle.

Kept as a generator rather than a committed PNG, for the reason its sibling is: the fixture
can be read and changed without a binary in the history. No third party imports.

    python3 tools/blue-triangle.py
    adb push blue-triangle.png /data/local/tmp/openweights/test-image-2.png
"""

import struct
import zlib

SIZE = 448
MARGIN = 60
BLUE = (25, 60, 220)
WHITE = (255, 255, 255)


def inside_triangle(x: float, y: float) -> bool:
    """An upright isosceles triangle: below the apex, inside both sloping sides."""
    apex_x = SIZE / 2.0
    top, bottom = float(MARGIN), float(SIZE - MARGIN)
    if not top <= y <= bottom:
        return False
    # How wide the triangle is at this height, growing linearly from the apex down.
    half_width = (apex_x - MARGIN) * (y - top) / (bottom - top)
    return abs(x - apex_x) <= half_width


def scanlines() -> bytes:
    out = bytearray()
    for y in range(SIZE):
        out.append(0)  # filter type 0: store the row as it is
        for x in range(SIZE):
            out += bytes(BLUE if inside_triangle(x + 0.5, y + 0.5) else WHITE)
    return bytes(out)


def chunk(tag: bytes, payload: bytes) -> bytes:
    body = tag + payload
    return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body))


def png() -> bytes:
    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)  # 8 bit truecolour
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(scanlines(), 9))
        + chunk(b"IEND", b"")
    )


if __name__ == "__main__":
    data = png()
    with open("blue-triangle.png", "wb") as handle:
        handle.write(data)
    print(f"blue-triangle.png: {SIZE}x{SIZE}, {len(data)} bytes")
