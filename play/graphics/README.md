# Play Store graphics

Everything the Console asks to be uploaded, and how to make it again.

| File | Play field | Spec | What it is |
|---|---|---|---|
| `icon-512.png` | App icon | 512 x 512, 32-bit PNG with alpha, under 1024 KB | The launcher mark at listing size |
| `feature-graphic-1024x500.png` | Feature graphic | 1024 x 500, 24-bit PNG, no alpha | Header art. No screenshot content: Play crops it hard on some surfaces |
| `screenshots-phone/*.png` | Phone screenshots | 1080 x 1920, 24-bit PNG, no alpha | Five captioned screens |
| `screenshots-tablet-7/*.png` | 7-inch tablet | 1200 x 2133, sides within 320..3840 | Four, rendered at 600dp |
| `screenshots-tablet-10/*.png` | 10-inch tablet | 1800 x 3200, sides within 1080..7680 | Four, rendered at 900dp |

Play takes 2 screenshots at minimum and 8 at most, and promotes listings with 4 or more at
1080p. There are five. It also refuses any screenshot whose long side is more than twice its
short one, which is why these are 9:16 and not the 1220 x 2712 a modern handset actually
produces.

## Making them again

**The icon and the feature graphic** are HTML rendered by headless Chrome, which is what lets
them use the app's own IBM Plex faces straight out of `core/designsystem`. The sources are in
the session scratchpad rather than here; what is worth keeping is the recipe, since both are
about twenty lines of CSS:

- Graphite `#0B0D0F` lifted by a radial to `#1A1E23`, brass `#F0A93B` on top.
- The icon is three stadium bars, widths 272 / 190 / 112 at height 60, centred in 512.
  One hue and no value ladder, because at 96 px a third bar dimmed against graphite goes
  muddy and stops reading. Same geometry as `ic_launcher_foreground.xml`, scaled.
- The feature graphic is the lockup on the left and three claims on a brass spine at
  x=610. Asymmetric on purpose: a centred logo over a tagline is the shape every AI app
  ships and it says nothing about this one.

**The screenshots** come from `PlayScreenshots`, a Robolectric run in `app/src/test`:

```
OPENWEIGHTS_SCREENSHOTS=/tmp/shots \
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  ./gradlew :app:testDebugUnitTest --tests '*PlayScreenshots*' --rerun-tasks

python3 play/graphics/frame.py /tmp/shots play/graphics/screenshots-phone
```

The first step draws the real composables at 360 x 640 dp on an xxhdpi night qualifier,
which is 1080 x 1920 px in the theme the app is designed for. Without the environment
variable each one returns immediately, so `./gradlew verify` runs them as no-ops and nobody
has to remember they are there. The second step adds the caption and the frame.

Regenerate both steps after any change to `ChatScreen`, `DiscoverScreen`, `ToolsScreen` or
the theme. That is the reason they are rendered rather than captured: a UI change should
cost a command, not an afternoon of re-staging conversations on a phone.

## What is deliberately not here

- **A promo video.** Play takes one YouTube URL, so there is nothing to upload from a repo.
- **Chromebook and XR screenshots.** Neither form factor is targeted.

## The tablet shots came with a fix attached

This section used to say tablet screenshots would mean adding a tablet layout first rather
than adding a picture, and that turned out to be exactly right. Rendered at a ten inch
tablet's 900dp, the chat screen was a phone stretched sideways: the reply ran the full width
at around a hundred and sixty characters a line, roughly twice what anyone reads
comfortably, with the composer stretched to match and a band of empty graphite down the
middle. Uploading that would have advertised the experience rather than the app.

So `ChatScreen` caps its content at `READABLE_WIDTH` and centres it, which changes nothing
on a phone — the window is narrower than the cap — and is the whole difference on anything
larger, foldables included. The screenshots are of the fixed layout.
- **Any number the app cannot produce.** 13.8 tok/s is Q4_0 measured on the test phone and
  4096 ctx is the shipped default. A listing that promises a rate the hardware misses is a
  one-star review with a receipt.
