# Play Store graphics

Everything the Console asks to be uploaded, and how to make it again.

| File | Play field | Spec | What it is |
|---|---|---|---|
| `icon-512.png` | App icon | 512 x 512, 32-bit PNG with alpha, under 1024 KB | The launcher mark at listing size |
| `feature-graphic-1024x500.png` | Feature graphic | 1024 x 500, 24-bit PNG, no alpha | Header art. No screenshot content: Play crops it hard on some surfaces |
| `screenshots-phone/*.png` | Phone screenshots | 1080 x 1920, 24-bit PNG, no alpha | Five captioned screens |

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
- **Tablet screenshots.** The app is `portrait` only and is not submitted for large screens.
  Adding them means adding a tablet layout first, not adding a picture.
- **Any number the app cannot produce.** 13.8 tok/s is Q4_0 measured on the test phone and
  4096 ctx is the shipped default. A listing that promises a rate the hardware misses is a
  one-star review with a receipt.
