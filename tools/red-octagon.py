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

"""Draws the picture `MultimodalTest` looks at.

That test asserts on a detail only visible in the file, because an image that never reaches
the model still produces a fluent answer about an imaginary one. So the fixture has to be
unambiguous in two independent ways at once, and an octagon is the useful part: plenty of
things are red, but nothing is accidentally eight sided, so a model that guesses gets it
wrong.

Kept as a generator rather than a committed PNG so the fixture can be read, and changed,
without a binary in the history. No third party imports, so it runs on any Python 3.

    python3 tools/red-octagon.py
    adb push red-octagon.png /data/local/tmp/openweights/test-image.png
"""

import struct
import zlib

SIZE = 448
RADIUS = 170.0
# A regular octagon's vertices sit at 22.5 degrees off the axes, which is where both of the
# bounds below come from: the square is the cosine, the diamond is the two summed.
COS_22_5 = 0.9238795325
SIN_22_5 = 0.3826834324
RED = (220, 30, 30)
WHITE = (255, 255, 255)


def inside_octagon(dx: float, dy: float) -> bool:
    """A regular octagon is a square intersected with a diamond."""
    ax, ay = abs(dx), abs(dy)
    return (
        ax <= COS_22_5 * RADIUS
        and ay <= COS_22_5 * RADIUS
        and ax + ay <= (COS_22_5 + SIN_22_5) * RADIUS
    )


def scanlines() -> bytes:
    centre = SIZE / 2.0
    out = bytearray()
    for y in range(SIZE):
        out.append(0)  # filter type 0: store the row as it is
        for x in range(SIZE):
            out += bytes(RED if inside_octagon(x - centre, y - centre) else WHITE)
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
    with open("red-octagon.png", "wb") as handle:
        handle.write(data)
    print(f"red-octagon.png: {SIZE}x{SIZE}, {len(data)} bytes")
