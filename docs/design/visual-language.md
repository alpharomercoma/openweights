# OpenWeights visual language

The rules every screen follows. Written down because the app had drifted: four accent
hues competing for the same job, five different corner radii, and chrome that floated in
the middle of the screen instead of sitting where the eye expects it.

## The idea

OpenWeights is an instrument you own. You chose the file, it runs on your silicon, and you
can watch how fast. The palette is **brass on graphite** — the colours of a precision
instrument rather than a consumer assistant. Warm enough to read for an hour, technical
enough to be honest about what it is.

This also keeps us out of a crowded room: ChatGPT is neutral monochrome, Claude is
terracotta on cream, Gemini is Google blue, Qwen is violet. Nobody is warm-amber-on-graphite.

## 60 / 30 / 10

| Share | Role | Dark | Light | Where |
|---|---|---|---|---|
| **60%** | Canvas | `#0B0D0F` | `#FBFBF9` | Screen background, transcript, top bar. The default. |
| **30%** | Raised | `#181C21` / `#242A31` | `#F0F0EB` / `#E3E3DD` | Composer, user bubbles, cards, nav bar, sheets, thumbnails. |
| **10%** | Brass | `#F0A93B` | `#8A5A0E` | Send, the active tab, focus, selection. Action, and only action. |

**Treat the split as a budget, not a mandate.** It describes a reading screen well and a
management screen badly: Models is a list of cards, so it is nearly all "raised" by
construction, and Discover has a search field, sort chips, fit cards and a download button
all wanting attention at once. What actually has to hold everywhere is the *role*
assignment below — the percentages are a sanity check on it, not a target to hit.

| Role | Treatment |
|---|---|
| Reading surface | Canvas, primary text |
| Input surface | Raised fill, `outline` border |
| Selection / action | Brass fill with `onBrass`, or brass text on canvas |
| Focus | The control's border becomes brass |
| Data | The signal scale, beside its number |
| Destructive | `error` — never brass, which would read as the recommended action |

**Text** rides on the neutrals: `#ECF1F4` primary, `#9AA6AF` secondary (dark);
`#14181B` / `#535E67` (light).

Two boundary tokens, because they do different jobs. `outline` (`#6A7783` / `#7A8188`) is
the boundary of a *control* — it clears 3:1 against canvas, raised and raised-high alike,
because it is often the only thing saying where a control begins. `outlineVariant`
(`#20262C` / `#DCDCD6`) is a decorative rule between rows and is never load-bearing.

Every pair was checked rather than eyeballed: body text 17:1, secondary text 7.8:1 dark and
6.4:1 light, brass as text 9.7:1 dark and 5.7:1 light, `onBrass` on a brass fill 9.6:1 and
5.9:1. The light accent is much darker than the dark one for exactly this reason — a light
theme cannot carry the same amber.

## Measurement colour is data, not decoration

The one place hue carries meaning: throughput and fit. That scale runs
**Jade `#4FC08D` → Slate `#8C99A4` → Rust `#FF6F59`**.

Grey through the middle, not amber. The first draft of this document put brass at the
midpoint and argued that "pay attention" covered both roles — that was a rationalisation
for reusing one hue in two jobs, and it recreated exactly the failure it was meant to fix:
the old palette used one teal for both "fast" and "tap me", so neither reading was reliable.
A brass rail beside an answer would read as *selected* before it read as *12 tok/s*.

Grey also happens to be the honest middle. Most readings are unremarkable, so a rail that is
actually green or red is worth a glance, and the screen stays quiet the rest of the time.

**Colour is never the only signal.** Every telemetry colour sits beside the number it
describes — the rail under a measured rate, the meter under a token count — and carries its
own description for screen readers. Hue is the glance; the digits are the answer.

Data hues appear in the speed rail, the context meter, fit verdicts and per-model
throughput. Never in chrome, never as a text colour for prose, and never to colour a
*volume*: a busy day is not a fast one.

## Shape

One scale, four steps. Nothing else.

| Token | Radius | Used by |
|---|---|---|
| `xs` | 8 dp | Chips, badges, inline code |
| `sm` | 12 dp | Attachment thumbnails, text fields inside sheets, small cards |
| `md` | 16 dp | Cards, list rows, message bubbles, dialogs |
| `lg` | 24 dp | The composer, bottom sheets |
| `full` | pill | Send button, active nav indicator |

16 dp on a bubble and 24 dp on the composer is the middle the brief asks for: tighter than
the 28 dp-plus that reads as a toy, looser than the 4–8 dp that reads as a terminal.

## Motion

The old build used Compose Navigation's defaults, which are **700 ms fades** — slow enough
to feel like the app is thinking when it is not.

| Token | Duration | Used by |
|---|---|---|
| `instant` | 90 ms | Icon swaps, colour changes, pressed states |
| `quick` | 160 ms | Tab switches, list item changes, composer growth |
| `standard` | 220 ms | Sheets, drawer, dialogs |

Easing is Material's `emphasized` — `cubic-bezier(0.2, 0, 0, 1)` — on enter and a plain
fade on exit, because nobody watches a thing leave.

Some things must not animate at all. A measurement that has already settled should not
still be drifting towards its colour half a second later — that suggests a reading still
being taken, and the old build eased the speed rail over 600 ms and the context meter over
400 ms. Historical chart values, error and destructive states, and list reordering under a
reader's thumb are all in the same category: show the state, do not interpolate towards it.

## Type

IBM Plex, already bundled, and the right call: Plex Sans was drawn for interfaces and Plex
Mono for readouts, which is exactly this app's split. What was missing is a deliberate
scale — the build was using Material's defaults, which are tuned for nothing in particular.

- **Prose** — Plex Sans 16/26. Replies are read for minutes; the leading earns its space.
- **Titles** — Plex Sans SemiBold with −0.2 sp tracking. Tighter than default, so headings
  read as labels rather than as sentences.
- **Numbers, model ids, quantization tags** — Plex Mono. Anything the user might compare
  row to row is monospaced so the columns line up and the digits stop dancing.
- **Never** monospace for prose. It is the app's tell, not its voice.

## Layout: chrome sits where chrome sits

Two bugs, one cause — the outer scaffold and every inner screen were both applying system
insets, so everything was padded twice.

- The **top bar** sits directly under the status bar, on the canvas colour, full width. It
  is not floating and not translucent. A hairline appears under it only once the transcript
  is scrolled, so the boundary shows up when it is needed and not before.
- The **composer** is docked to the bottom, directly above the navigation bar, and rises
  with the keyboard. Never floating over the last message, which is the pitfall every
  mobile chat app trips over once.

## The composer, after ChatGPT

One rounded container holding everything, rather than controls scattered beside a field:

```
┌──────────────────────────────────────────┐
│ [thumb] [thumb]                          │  attachments, only when present
│ Message                                  │  text, grows to 6 lines then scrolls
│ ⊕                              ◍   ↑     │  actions
└──────────────────────────────────────────┘
```

The `+` is bottom-left because that is where every current chat app puts attachments, and
because a bar that starts with a plus reads as calm — the power is one tap away rather than
spread across the default state. Send is a filled circle, bottom-right, brass when there is
something to send and neutral when there is not. It becomes a stop square while generating,
because send and stop are never both useful and a control that appears mid-conversation is
one the thumb has to hunt for.

The layout stays two rows whether or not there is a draft. Collapsing to one row when empty
would save a few millimetres and buy a layout jump on the first keystroke, which is a bad
trade in the one control the user touches most.

The container's border is also the focus indicator: it is the only boundary this control
has, so it is the thing that answers when the field goes live.

## Message actions are visible, not hidden

Copy, read aloud and retry sit as an icon row directly under each finished reply, always
visible. Hover-to-reveal is a desktop pattern with no mobile equivalent, and burying the
single most-used action — copy — behind a long-press is the kind of thing that makes an app
feel unfinished. The long-press sheet stays for the rarer actions.

## What stays weird on purpose

The **speed rail**: a coloured rule down the left of every reply, its hue set by the
throughput that produced it. No other chat app shows you this, because no other chat app is
running on hardware you can feel getting hot. It is the one element allowed to be
unfamiliar, and everything around it is quiet so that it reads.
