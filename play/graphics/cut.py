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

Every frame is from a recording made on a Dimensity 9400 (POCO X8 Pro, Android 16), and
every claim in the type is one the frame underneath it shows.

Deliberately not here: the web-search citation chip, because a video whose first claim is
"no sign-in" should not then show the app fetching a page; the canvas builder, because its
tools stay off until a folder is shared and on a 1.2B model the request returns an empty
reply, and we do not stage results; and the two-runtimes claim, the product's real
differentiator, because Discover shows no engine label per row and a .pte model's detail
page renders its header with no file list at all in this build. That last one is a bug, and
until it is fixed there is nothing honest to point a camera at.
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
END = 2.6  # end-card seconds

SHOTS = [
    # The opening rides real motion: the reply arrives line by line with the header's
    # "CPU . 32768 ctx" in frame. The window 5.4..10.6 is bounded at both ends. Earlier and
    # the reply has not visibly started; later and it runs past 11.0 s, where that recording
    # pressed Home, which once put the phone's lock-screen wallpaper inside the card under
    # the words "It answers on-device". Check the tail of a shot, not only its head.
    dict(src="fs-specialuse-generation.mp4", at=6.6, length=4.0, crop=(40, 262, 1200, 538),
         eyebrow="on device", head="It answers on-device",
         sub="No sign-in. The model file is on this phone."),

    # Held, and cut to end at 2250, above the composer: the chat box has no business in a
    # shot whose whole subject is the telemetry line.
    dict(src="fs-specialuse-generation.mp4", at=46.6, length=3.4, hold=True,
         crop=(0, 1676, FULL_W, FULL_H),
         eyebrow="live telemetry", head="It shows its own speed",
         sub="Speed and context, printed while it answers."),

    # Two files, each card centred on its own measured edges: the bands are 513 px tall with
    # 33 px gaps, so a 574 px window leaves about 30 px above and below and slices nothing.
    # The cut between them is what says "per file": 664 MB needing 1.13 GB at ~98 tok/s,
    # then 697 MB needing 1.17 GB at ~93.
    dict(src="shot-fit.mp4", at=2.4, length=3.0, hold=True,
         crop=(0, 1272, FULL_W, FULL_H),
         eyebrow="before download", head="It says if it fits",
         sub="What each file needs, and how fast it will run."),
    # The other verdict, from the 8B page, and deliberately a different screen: two cards
    # from the same list that both say "Runs comfortably" differ so little that the cut
    # between them barely registers, and a caption changing over an apparently unchanged
    # picture reads as a stuck title rather than as a second piece of evidence. This card
    # says the opposite thing, about the very file the next shot downloads. Its cards are
    # 411 px tall on a 444 px pitch, so a window wide enough to hold the Download button
    # cannot avoid the neighbours: it is centred instead, with 29 px of the card above and
    # 31 px of the one below, which reads as a list rather than as a slip.
    dict(src="fs-datasync-download.mp4", at=1.2, length=3.2, hold=True,
         crop=(40, 1203, 1200, 538),
         eyebrow="and when it will not", head="It says when it is tight",
         sub="4.80 GB asking for 5.29, and it tells you first."),

    # Motion, and the only other shot that has any: the byte counter climbs and the bar
    # grows while the layout underneath stays perfectly still, which is the one kind of
    # movement a fixed crop can hold. 1180 x 529 rather than the full width because the
    # publisher row below the card creeps into a 1280-wide window.
    dict(src="fs-datasync-download.mp4", at=5.0, length=3.6, crop=(50, 368, 1180, 529),
         eyebrow="from hugging face", head="Then it fetches it",
         sub="Progress inside the app, and a cancel that stops it."),

    # The tools. The first view is the screen's own explainer card, centred on its measured
    # edges: it says the file tools stay off until a folder is shared, which is both the
    # strongest privacy claim on the screen and the reason those rows read "Waiting for a
    # folder". Any view of the list itself is three-quarters rows in that waiting state,
    # which made a live product look inert. The second view is centred exactly on the
    # boundary: the local group's last row sits 200 px above the "Leaves the device" header
    # and the network group's first row 200 px below it, so the split is symmetrical in
    # frame rather than approximately so. Both crops land on measured boundaries rather
    # than near them: 380 puts the screen's own "What the model may do while it answers"
    # line 10 px from the top with the card 11 px from the bottom, and 1412 is exactly the
    # divider above the "Run a script" row, so the frame opens on a whole row instead of a
    # sliced title. What the bottom edge cuts is a row continuing below the fold, which is
    # what a list does; what the top edge cuts is a mistake.
    dict(src="shot-tools3.mp4", at=0.4, length=3.2, hold=True,
         crop=(0, 380, FULL_W, FULL_H),
         eyebrow="nothing by default", head="Nothing is shared by default",
         sub="The file tools stay off until you pick a folder."),
    dict(src="shot-tools3.mp4", at=7.0, length=3.4, hold=True,
         crop=(0, 1412, FULL_W, FULL_H),
         eyebrow="your call", head="Local and networked, split",
         sub="Each one says which side of the phone it works on."),
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
                                    shot["sub"], mask, shot["crop"], 1.0))
        # 2.6 s. Both reviewers called 4.6 s pacing malpractice: 17 percent of the runtime
        # on a static logo, on a listing whose cover image is already that same brand art.
        clips.append(still(endcard(work / "end.png"), END, work / "end.mp4"))
        join(clips, OUT)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print(f"wrote {OUT}  ({sum(s['length'] for s in SHOTS) + END:.1f}s)")


if __name__ == "__main__":
    main()
