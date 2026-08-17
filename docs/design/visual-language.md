# OpenWeights visual language

The rules every screen follows. Written down because the app had drifted: four accent hues
competing for the same job, five different corner radii, and chrome that floated in the
middle of the screen instead of sitting where the eye expects it.

It has been rewritten once since, for the palette described below. What survived that
rewrite is more interesting than what changed: the arguments about data colour, about
motion, and about what belongs in the composer were all made against a different set of
colours and none of them depended on those colours being right.

## The idea

OpenWeights is an instrument you own. You chose the file, it runs on your silicon, and you
can watch how fast. The palette is **lime on ink**, with hueless greys between: three
colours and nothing else, ported from `alpharomercoma/portfolio-unleashed`, whose
`docs/design-system.md` and `globals.css` are the only places that project defines colour.

It was brass on graphite, which was chosen to stay out of a crowded room and did: ChatGPT is
neutral monochrome, Claude is terracotta on cream, Gemini is Google blue, Qwen is violet.
Lime keeps that property and buys something brass could not. Brass had to become a much
darker amber to survive a light theme, so the two themes never quite looked like the same
product. Lime is used boldly rather than as a garnish, is the same value in both themes, and
is bright enough to carry ink rather than needing a colour of its own to sit on.

## 60 / 30 / 10

| Share | Role | Dark | Light | Where |
|---|---|---|---|---|
| **60%** | Canvas | `#0D0E10` | `#FFFFFF` | Screen background, transcript, top bar. The default. |
| **30%** | Raised | `#161719` / `#232427` | `#F4F5F3` / `#E7E8E4` | Composer, user bubbles, cards, sheets, thumbnails. |
| **10%** | Lime | `#E0FF4F` | `#E0FF4F` | The action you came to take. Nothing else. |

**Treat the split as a budget, not a mandate.** It describes a reading screen well and a
management screen badly: Models is a list of cards, so it is nearly all "raised" by
construction, and Discover has a search field, sort chips, fit cards and a download button
all wanting attention at once. What actually has to hold everywhere is the *role*
assignment below. The percentages are a sanity check on it, not a target to hit.

| Role | Treatment |
|---|---|
| Reading surface | Canvas, primary text |
| Input surface | Raised fill, `outline` border |
| Action | A lime fill carrying ink. Never a lime word |
| Focus | The control's border becomes `primary` |
| Data | The signal scale, beside its number |
| Destructive | `error`, never lime, which would read as the recommended action |

**Text** rides on the neutrals: `#F5F6F3` primary, `#A2A4AB` secondary (dark); `#052B42`
ink and `#52555B` secondary (light).

Two boundary tokens, because they do different jobs. `outline` (`#6E7178` / `#7C7F86`) is
the boundary of a *control*. It clears 3:1 against canvas, raised and raised-high alike,
because it is often the only thing saying where a control begins. `outlineVariant`
(`#26272A` / `#E7E8E4`) is a decorative rule between rows and is never load-bearing.

Nothing above is eyeballed and nothing above is trusted to this document.
`PaletteContrastTest` computes WCAG relative luminance on every build and fails if body text
drops under 4.5:1 or a boundary under 3:1, on either canvas.

## The three rules that came with the palette

1. **Ink on lime, never white.** Lime is a light colour. Ink measures 12.99:1 on it.
2. **Lime is never text or a meaningful icon on a light surface.** It measures 1.13:1 on
   white, which is invisible rather than merely poor. A lime word on a light background
   means a lime fill carrying ink text.
3. **Measurement is a separate language.** See below.

Rule 2 has a consequence worth stating, because it is the one thing about this palette that
Material cannot express. `primary` is painted two ways by different components: a filled
`Button` uses it as a container, and a `TextButton`'s label, a `Slider`'s track, a caret and
a progress bar all use it as ink. On the dark canvas lime does both at 17:1. On paper it can
only be a fill.

So **`primary` is the readable one**: lime in the dark scheme, ink in the light one. Every
stock Material component is then legible in both themes with no call site overriding
anything. Lime as a fill lives in `AccentButton` and in the composer's send button, which
are lime on both canvases, and that is also a fair description of how often a screen should
have a primary action.

## Measurement colour is data, not decoration

The one place hue carries meaning: throughput and fit. That scale runs
**Teal `#3BA88F` / `#2F8B74` → Grey → Red `#FF6166` / `#E5484D`**, dark value first.

Grey through the middle, not the accent. The first draft of this document put brass at the
midpoint and argued that "pay attention" covered both roles. That was a rationalisation for
reusing one hue in two jobs, and it recreated exactly the failure it was meant to fix: an
older palette used one teal for both "fast" and "tap me", so neither reading was reliable. An
accent rail beside an answer reads as *selected* before it reads as *12 tok/s*.

That argument survives the rebrand and now cuts harder. Lime is both the action colour and,
being green, the obvious choice for "fast", so it would be a worse offender than brass was.
This is the single rule from the portfolio not applied literally: its palette has no data
colours, and this app needs three.

The teal is not the same value in both themes. The obvious `#3BA88F` measures 2.92:1 on
white and fails, so the light theme takes a darker `#2F8B74` at 4.15:1.

**Colour is never the only signal.** Every telemetry colour sits beside the number it
describes, the rail under a measured rate, the meter under a token count, and carries its
own description for screen readers. Hue is the glance; the digits are the answer.

Data hues appear in the speed rail, the context meter, fit verdicts and per-model
throughput. Never in chrome, never as a text colour for prose, and **never to colour a
volume**: a busy day is not a fast one. The week chart on Usage broke that rule for a while
and painted a quiet Sunday in the red at the bottom of the scale, as though not using your
phone were a fault. Its bars are neutral now, with the accent marking today, because height
already says how much.

## Shape

One scale, four steps. Nothing else.

| Token | Radius | Used by |
|---|---|---|
| `xs` | 8 dp | Chips, badges, inline code |
| `sm` | 12 dp | Attachment thumbnails, text fields inside sheets, small cards |
| `md` | 14 dp | Cards, list rows, message bubbles, dialogs |
| `lg` | 22 dp | The composer, bottom sheets |
| `pill` | full | Buttons and chips, without exception |

14 dp on a bubble and 22 dp on the composer is the middle the brief asks for: tighter than
the 28 dp-plus that reads as a toy, looser than the 4 to 8 dp that reads as a terminal.
Buttons are pills, which is the portfolio's rule and is applied with no exceptions: a couple
of dialogs used to ask for `sm` and the mixture read as an oversight rather than a choice.

## Motion

The old build used Compose Navigation's defaults, which are **700 ms fades**: slow enough
to feel like the app is thinking when it is not.

| Token | Duration | Used by |
|---|---|---|
| `instant` | 90 ms | Icon swaps, colour changes, pressed states |
| `quick` | 160 ms | List item changes, composer growth, disclosures |
| `standard` | 220 ms | Sheets, drawer, dialogs |

Easing is Material's `emphasized`, `cubic-bezier(0.2, 0, 0, 1)`, on enter and a plain fade
on exit, because nobody watches a thing leave. Pushed screens slide in from the side by
12 dp and fade, which is the one motion that says "this sits on top of the conversation and
you are coming back".

Some things must not animate at all. A measurement that has already settled should not still
be drifting towards its colour half a second later. That suggests a reading still being
taken, and an older build eased the speed rail over 600 ms and the context meter over
400 ms. Historical chart values, error and destructive states, and list reordering under a
reader's thumb are all in the same category: show the state, do not interpolate towards it.

## Type

Three families, all bundled in `res/font` rather than fetched as downloadable fonts,
because an offline-first app must not depend on Play Services to render its own text. Each
ships as a single variable TTF with a weight axis, so a family costs one file rather than
four.

| Role | Family | Used for |
|---|---|---|
| Display | Schibsted Grotesk | Titles and headlines, tight tracking, 600 to 700 |
| Body and UI | Hanken Grotesk | Everything else, 400 to 600 |
| Mono | Geist Mono | Numbers, model ids, quantization tags |

It was IBM Plex, which was a reasonable call and lost nothing but the match with the
portfolio it now shares a palette with.

- **Prose**: 16/26. Replies are read for minutes; the leading earns its space.
- **Titles**: Display at SemiBold with −0.2 sp tracking. Tighter than default, so headings
  read as labels rather than as sentences.
- **Numbers, model ids, quantization tags**: mono. Anything the user might compare row to
  row is monospaced so the columns line up and the digits stop dancing.
- **Never** monospace for prose. It is the app's tell, not its voice.

## The mark

Three bars, which read as weights: lengths 1, 0.7 and 0.41 of the longest, 0.22 as thick as
the longest is long, 0.32 apart centre to centre. Left aligned and descending, so it reads
as three quantities rather than as a menu. Lime on ink.

One hue, no value ladder. A ladder was tried and the third bar goes muddy against a dark
tile at launcher size; measured at 96 px, flat lime is the only version where all three bars
still read.

Those five numbers are the mark, and four things draw it: `Mark.kt` in the app,
`ic_launcher_foreground.xml`, `ic_notification.xml`, and `play/graphics/mark.py` for the
store icon and the feature graphic. They have drifted before. Anything drawing it again
reads the numbers rather than measuring a screenshot.

## Layout: the conversation is the app

There is no bottom navigation bar. There was, with five tabs, and it cost about 80 dp of
permanent chrome on every screen to offer four destinations that are visited rarely and one
that is the entire product.

- **Chat is the only top level surface.** Everything else is pushed over it or raised as a
  sheet, and returns you to the conversation.
- **The drawer** holds conversation history, and Tools, Usage and Settings in its footer. It
  lives inside `ChatScreen` rather than above the `NavHost`, which keeps the recomposition
  firewall the shell maintains and scopes the edge swipe correctly for free: a pushed
  Settings screen must not be swipeable back to chat history.
- **The model name in the top bar** opens a picker. Switching model is something you do
  about the conversation you are in, so it does not take you out of it. Browsing the Hub and
  managing what is installed both open from inside that picker.
- **Sampler settings** are a sheet raised from the top bar, with everything whose default is
  already right folded behind one Advanced row.
- The **top bar** sits directly under the status bar, on the canvas colour, full width. It is
  not floating and not translucent.
- The **composer** is docked to the bottom and rises with the keyboard. Never floating over
  the last message, which is the pitfall every mobile chat app trips over once.

Removing the bar removed the only consumer of the bottom system inset, so every content root
carries `Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))`.
That modifier participates in Compose's consumption chain and resolves to zero while an
ancestor is consuming, which is what allowed it to be added and verified on hardware before
the bar was deleted.

Two things stay capped on purpose: the conversation at 640 dp, so a tablet does not render a
reply at 160 characters a line, and the user bubble at 300 dp, so a short question does not
stretch across one.

## The composer, after ChatGPT

One rounded container holding everything, rather than controls scattered beside a field:

```
┌──────────────────────────────────────────┐
│ [thumb] [thumb]                          │  attachments, only when present
│ Message                                  │  text, grows to 6 lines then scrolls
│ ⊕  [thinking]                  ◍   ↑     │  actions
└──────────────────────────────────────────┘
```

The `+` is bottom-left because that is where every current chat app puts attachments, and
because a bar that starts with a plus reads as calm. It is always there. It used to appear
only for a model that accepts media, with a second document button beside it that bypassed
the sheet entirely, so the composer had two attach affordances and neither was reliably
present. One plus opens one sheet, and the rows a model cannot accept are the ones left out.

Send is a filled circle, bottom-right, lime when there is something to send and neutral when
there is not. It becomes a stop square while generating, because send and stop are never
both useful and a control that appears mid-conversation is one the thumb has to hunt for.

The layout stays two rows whether or not there is a draft. Collapsing to one row when empty
would save a few millimetres and buy a layout jump on the first keystroke, which is a bad
trade in the one control the user touches most.

## Reasoning follows the generation

A chain of thought is expanded while the model is writing it and folded away on the frame it
finishes, and a tap wins in either state. It used to be collapsed always and never move,
which failed in both directions at once: a reader who never tapped never saw the thinking at
all, and one who did tap was left with a finished chain of thought at full height above the
answer that arrived under it.

Watching a model think is worth seeing live and worth nothing afterwards. A block opened by
hand while the model was working still folds when it stops, because the reason to have it
open has gone: the tap is about the next few seconds, not forever.

## Message actions are visible, not hidden

Copy, read aloud and retry sit as an icon row directly under each finished reply, always
visible. Hover-to-reveal is a desktop pattern with no mobile equivalent, and burying the
single most-used action, copy, behind a long-press is the kind of thing that makes an app
feel unfinished. The long-press sheet stays for the rarer actions.

## What stays weird on purpose

The **speed rail**: a coloured rule down the left of every reply, its hue set by the
throughput that produced it. No other chat app shows you this, because no other chat app is
running on hardware you can feel getting hot. It is the one element allowed to be
unfamiliar, and everything around it is quiet so that it reads.
