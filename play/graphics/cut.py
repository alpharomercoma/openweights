"""The Play listing cut: which real capture plays under which claim, and for how long.

    python3 play/graphics/cut.py

One card, one grid: the card, the lime rule and both caption edges share a single
rectangle, and the stack is centred so the margin above the card matches the one below the
headline. Every crop is cut to that card's aspect and centred on the phone's own axis.

Motion versus held frames. A crop into a scrolling list cannot be framed: wherever the cut
lands some card is sliced, and the gap above it stops matching the gap below, which is the
first thing an eye notices. The Tools list is also barely a screen tall, so a beat built on
scrolling it runs out of travel and then sits still. So only the opening rides real motion,
the reply arriving line by line; the rest are frames held from the same captures, each
centred on measured card edges, with the cut between two views carrying the change. The
card bands and the 33 px gaps between them were measured off the pixels, not eyeballed.

Every frame is from a recording made on a Dimensity 9400 (POCO X8 Pro Max, Android 16),
and
every claim in the type is one the frame underneath it shows.

The opening used to avoid the web-search chip, on the argument that a video whose first
claim is "no sign-in" should not then show the app fetching a page. That was the wrong
call. The chip is the proof, not the contradiction: it says the model reached the network
once, because a switch the viewer owns was on, and then wrote the answer here. The caption
carries it, and the tools beat later in the cut says the same thing about the switch.

But the first two seconds now carry no network claim at all, which is the point of the
brand beat. It is the same take framed higher, so the chip is out of shot and what is in
frame is the app header: a named open-weight model, running on this phone's CPU, with its
context length printed. The claim and its evidence are the same pixels. Only in the shot
after it, where the subhead can say "Model local. Web opt-in.", does the chip appear.

Deliberately not here: the canvas builder, because its tools stay off until a folder is
shared and on a 1.2B model the request returns an empty reply, and we do not stage results;
and the two-runtimes claim, the product's real differentiator, because Discover shows no
engine label per row and a .pte model's detail page renders its header with no file list at
all in this build. That last one is a bug, and until it is fixed there is nothing honest to
point a camera at.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from promo import beat_typed, endcard, fit, join, mask_png, still  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CAPTURES = ROOT / "play/videos"
OUT = ROOT / "play/videos/listing-promo.mp4"

# Held views are cut from the full 1280-wide screen, so they keep the app's own side
# margins and need no horizontal centring of their own. 1280 x 574 is the card's aspect.
FULL_W, FULL_H = 1280, 574
END = 3.2  # end-card seconds

SHOTS = [
    # The brand beat, and it costs no runtime: it is the same take as the shot after it,
    # framed higher. The crop runs 108..682, which clears the status bar glyphs at the top
    # and stops in the gap above the search chip, so what is in frame is the app header
    # reading "LFM2.5-1.2B-Inst... CPU . 32768 ctx" and the question. That header IS the
    # claim: a named open-weight model, running on this phone's CPU, with its context
    # length printed. Two reviewers independently refused a held title card here on the
    # grounds that a static slate in the first two seconds of a muted autoplay is how you
    # lose the viewer; overlaying the brand on live UI says the same thing and spends
    # nothing. The chip is deliberately out of this frame, so the first two seconds carry
    # no network claim at all.
    dict(src="shot-search.mp4", at=12.6, length=2.0, hold=True,
         crop=(0, 108, FULL_W, FULL_H), zoom=1.035,
         eyebrow="openweights", head="Run open-weight AI on your phone",
         sub="No account. On-device by default."),

    # Then the same conversation, lower in the frame, with the answer writing itself. The
    # subhead does the one job the picture cannot: it separates where the model runs from
    # where the answer's facts came from. A viewer who reads "Searched the web" over a
    # headline about on-device inference can otherwise conclude the opposite of the truth.
    dict(src="shot-search.mp4", at=12.45, length=3.2, crop=(0, 635, FULL_W, FULL_H),
         eyebrow="on device", head="It answers on-device",
         sub="Model local. Web opt-in."),

    # Held from the same take, so the first three beats are one conversation. 965 is the gap
    # between two lines of the reply, so the top edge cuts no glyph, and 1539 clears the
    # speed line underneath. The composer stays out: the chat box has no business in a shot
    # whose whole subject is the number above it.
    dict(src="shot-search.mp4", at=17.5, length=3.0, hold=True,
         crop=(0, 965, FULL_W, FULL_H),
         eyebrow="live telemetry", head="See exactly how fast it runs",
         sub="Tokens per second, every answer."),

    # Two files, each card centred on its own measured edges: the bands are 513 px tall with
    # 33 px gaps, so a 574 px window leaves about 30 px above and below and slices nothing.
    # The cut between them is what says "per file": 664 MB needing 1.13 GB at ~98 tok/s,
    # then a 4.80 GB file that wants 5.29 and says so.
    dict(src="shot-fit.mp4", at=2.4, length=2.8, hold=True,
         crop=(0, 1272, FULL_W, FULL_H),
         eyebrow="before download", head="Know if a model fits",
         sub="What it needs, and how fast it runs."),
    # The same eyebrow as the shot above, deliberately: one claim, two pieces of
    # evidence, and repeating the label is what says the second card is the same
    # argument rather than a new one. The other verdict, on a different screen: two cards from the same list
    # that both say "Runs comfortably" differ so little that the cut between them barely
    # registers. This one says the opposite thing, about the very file the next shot
    # downloads. Its cards are 411 px tall on a 444 px pitch, so a window wide enough to
    # hold the Download button cannot avoid the neighbours: it is centred instead, with
    # 29 px of the card above and 31 px of the one below, which reads as a list.
    dict(src="fs-datasync-download.mp4", at=1.2, length=2.8, hold=True,
         crop=(40, 1203, 1200, 538),
         eyebrow="before download", head="And when it does not",
         sub="4.80 GB asking for 5.29, upfront."),

    # Motion, and the only other shot with any: the byte counter climbs and the bar grows
    # while the layout underneath stays still, which is the one kind of movement a fixed
    # crop can hold. Shortened from 3.6 s to 3.0: downloading a file is table stakes, not a
    # differentiator, and the two beats either side of it are the argument.
    dict(src="fs-datasync-download.mp4", at=5.0, length=3.0, crop=(50, 368, 1180, 529),
         eyebrow="from hugging face", head="Then it downloads it",
         sub="Progress in the app. Cancel anytime."),

    # On the headline here. An earlier pass ran "No folder is shared by default", because
    # said flat and unscoped, "nothing is shared" is not true of this build: web search
    # ships on, and a query a person typed leaves the device when the model calls it. The
    # line is asked for and it is kept, and what makes it honest is that the eyebrow is not
    # decoration. YOUR FILES is the subject and the headline is its predicate, the card
    # underneath says "No folder shared . The file tools stay off until you pick one. Only
    # that folder is shared", and the subhead names the same scope again in five words. Of
    # your files, nothing is shared by default, and that is exactly what the screen proves.
    # The very next beat then says out loud that some tools do leave the device.
    #
    # The tools. The first view is the screen's own explainer card, centred on its measured
    # edges: it says the file tools stay off until a folder is shared, which is both the
    # strongest privacy claim on the screen and the reason those rows read "Waiting for a
    # folder". The second is centred exactly on the boundary: the local group's last row
    # sits 200 px above the "Leaves the device" header and the network group's first row
    # 200 px below it, so the split is symmetrical in frame rather than approximately so.
    # 380 puts the screen's own "What the model may do while it answers" line 10 px from the
    # top; 1412 is exactly the divider above "Run a script", so the frame opens on a whole
    # row instead of a sliced title.
    dict(src="shot-tools3.mp4", at=0.4, length=2.8, hold=True,
         crop=(0, 380, FULL_W, FULL_H),
         eyebrow="your files", head="Nothing is shared by default",
         sub="No folder, until you choose one."),
    dict(src="shot-tools3.mp4", at=7.0, length=2.8, hold=True,
         crop=(0, 1412, FULL_W, FULL_H),
         eyebrow="your call", head="You decide what leaves the device",
         sub="Every tool says which side it is on."),
]


def grab(source: Path, at: float, out: Path) -> Path:
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", f"{at}", "-i", str(source),
                    "-frames:v", "1", str(out)], check=True)
    return out


def main() -> None:
    work = Path(tempfile.mkdtemp(prefix="promo-"))
    clips = []
    try:
        rect = fit()
        mask = mask_png(rect[2], rect[3], work / "mask.png")
        for i, shot in enumerate(SHOTS):
            source = CAPTURES / shot["src"]
            if not source.exists():
                raise SystemExit(f"missing capture: {source}")
            if shot.get("hold"):
                source = grab(source, shot["at"], work / f"hold{i}.png")
            # Every headline types on, at 120 characters a second, so each finishes inside
            # 0.25 s and reads as the caption snapping into place rather than as an effect.
            clips.append(beat_typed(source, shot["at"], shot["length"], work / f"seq{i}",
                                    work / f"beat{i}.mp4", shot["eyebrow"], shot["head"],
                                    shot["sub"], mask, shot["crop"],
                                    shot.get("zoom", 1.0)))
        # 2.6 s. Both reviewers called 4.6 s pacing malpractice: 17 percent of the runtime
        # on a static logo, on a listing whose cover image is already that same brand art.
        clips.append(still(endcard(work / "end.png"), END, work / "end.mp4"))
        join(clips, OUT)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print(f"wrote {OUT}  ({sum(s['length'] for s in SHOTS) + END:.1f}s)")


if __name__ == "__main__":
    main()
