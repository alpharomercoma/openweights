#!/usr/bin/env python3
"""What a picture costs, and what it is worth, across real pictures and the two levers.

The two levers are the pixels the app sends and the projector's token ceiling. The first
is `ModelPreferences.imageEdgePixels` today; the second is `ModelLoadParams.imageTokens`,
which reaches libmtmd as image_min_tokens/image_max_tokens. LFM2 tiles whenever the
picture's area exceeds image_max_pixels * 2, and every tile is 256 tokens, so the two
interact: a bigger ceiling turns tiling off. This measures the interaction on pictures of
different shapes, because the tall screenshot the earlier sweep used never tiled at 1024
while a 3:4 photograph at the same edge does.

Usage:
  python3 eval/image_speed_probe.py <port> <arm-name> [budget,budget,...]

Start llama-server yourself, once per arm, e.g.
  llama-server -m LFM2.5-VL-3B-Q4_0.gguf --mmproj mmproj-LFM2.5-VL-3B-Q8_0.gguf \
      --jinja -c 8192 --port 8092                     # arm "model": the model's own limits
  ... --image-max-tokens 1024                         # arm "max1024": tiling off below 2.1 MP
and check /props names the model you think it does.

Budgets are pixel counts the picture is shrunk to (area, aspect kept), or "edgeNNNN" for
the app's current longest-edge rule, or "orig" for the file as it is.
"""
import base64
import io
import json
import os
import re
import sys
import time
import urllib.request

from PIL import Image

PORT = int(sys.argv[1])
ARM = sys.argv[2]
BUDGETS = (sys.argv[3] if len(sys.argv) > 3 else "edge1024,262144,524288,1048576").split(",")
OUT = os.environ.get("OUT", f"/tmp/image-probe-{ARM}.jsonl")
HOME = os.path.expanduser("~")

# Each: path, question, facts that can only come from reading the picture (lowercase
# substrings; any one alternative in a tuple counts).
CASES = [
    (f"{HOME}/Downloads/IMG_20260813_123001.jpg",
     "Read this form. Give the student's name, the course, the contact number, and the date "
     "the toga must be returned.",
     [("xynil",), ("bscoe", "bs coe", "bsc0e"), ("09452709636", "0945"), ("sept 9", "sep 9", "september 9")]),
    (f"{HOME}/Downloads/d3_starbucks(1).png",
     "Read this receipt. What was bought, how many, the total, the cash given and the change?",
     [("espresso",), ("950",), ("1,000", "1000"), ("50",), ("5",)]),
    (f"{HOME}/Downloads/20260724213732.png",
     "What is written in this game screenshot? Name the character and the game, and say how "
     "many stars are shown.",
     [("columbina",), ("genshin",), ("five", "5")]),
    (f"{HOME}/Downloads/eren.jpg",
     "Describe this picture in two sentences.",
     [("titan", "giant", "monster", "creature", "figure"), ("green",)]),
    (f"{HOME}/Downloads/Alpha_Romer_Coma_FlowCV_Resume_2026-07-24-4-1.png",
     "Whose resume is this, and what is the first job title listed under experience?",
     [("alpha romer coma", "alpha"), ("engineer", "developer", "lead", "founder", "intern")]),
    (f"{HOME}/Downloads/Beyond GPUs_ Production LLMs on AWS Trainium & Inferentia.png",
     "What is the title on this slide?",
     # The file is named for the deck, but the slide itself is Jensen Huang's layer cake.
     [("jensen",), ("davos",), ("5-layer", "five-layer", "5 layer", "five layer")]),
    ("/tmp/vl-probe.png",
     "Read this screenshot and answer with four short lines: the heading, the number in the "
     "green circle, the percentage change on the Flanges row, and the verification code at "
     "the bottom.",
     [("quarterly report",), ("42",), ("12.8",), ("tangerine",)]),
]


def shrink(path, budget):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    if budget == "orig":
        pass
    elif budget.startswith("edge"):
        edge = int(budget[4:])
        if max(w, h) > edge:
            s = edge / max(w, h)
            im = im.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
    else:
        px = int(budget)
        if w * h > px:
            s = (px / (w * h)) ** 0.5
            im = im.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, format="JPEG", quality=90)
    return im.size, buf.getvalue()


def ask(image_bytes, question):
    payload = {
        "model": "m",
        "messages": [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(image_bytes).decode()}},
            {"type": "text", "text": question},
        ]}],
        "max_tokens": 200, "temperature": 0, "seed": 7, "cache_prompt": False,
    }
    req = urllib.request.Request(f"http://localhost:{PORT}/v1/chat/completions",
                                 json.dumps(payload).encode(), {"Content-Type": "application/json"})
    started = time.time()
    with urllib.request.urlopen(req, timeout=1800) as r:
        reply = json.load(r)
    wall = time.time() - started
    timings = reply.get("timings", {})
    content = (reply["choices"][0]["message"].get("content") or "").strip()
    return content, timings, wall


def score(content, facts):
    low = content.lower()
    return sum(any(alt in low for alt in fact) for fact in facts), len(facts)


with open(OUT, "a") as out:
    print(f"arm={ARM}")
    print("image | budget | sent px | prompt tok | prompt ms | wall s | read")
    for path, question, facts in CASES:
        if not os.path.isfile(path):
            print(f"{os.path.basename(path)} | missing")
            continue
        for budget in BUDGETS:
            size, data = shrink(path, budget)
            content, timings, wall = ask(data, question)
            got, of = score(content, facts)
            row = {
                "arm": ARM, "image": os.path.basename(path), "budget": budget,
                "sent": f"{size[0]}x{size[1]}", "px": size[0] * size[1],
                "prompt_n": timings.get("prompt_n"), "prompt_ms": timings.get("prompt_ms"),
                "wall_s": round(wall, 1), "read": f"{got}/{of}", "answer": content[:300],
            }
            out.write(json.dumps(row) + "\n")
            out.flush()
            print(f"{row['image'][:28]} | {budget} | {row['sent']} | {row['prompt_n']} | "
                  f"{row['prompt_ms'] and round(row['prompt_ms'])} | {row['wall_s']} | {row['read']}")
            sys.stdout.flush()
