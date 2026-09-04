# Play Console: what to put in each box

Everything a human has to type or upload, written out so the submission is a matter of
copying rather than composing. The claims here match the app; where a form asks something the
code decides, the code was read rather than remembered.

The checklist of what has been verified in the build is in [play-store.md](play-store.md).
The policy this listing has to link to is [privacy-policy.md](privacy-policy.md), published at
<https://alpharomercoma.github.io/openweights/privacy.html>. That is the URL to paste into the
Console; how the page is built and republished is in `play/site/README.md`.

## Store listing

**App name** (30 characters)

```
OpenWeights: Offline AI Chat
```

28 of the 30. The name was "OpenWeights" alone, which is 11, and the title is the field Play
weighs most heavily in search: leaving nineteen characters unused is the single largest thing
wrong with this listing. Nobody searches for "OpenWeights" who has not already been told about
it, and the words that carry the search are the ones after the colon.

"Offline" rather than "local" or "private" in the title, because it is the one a person types
when they want this and it is unambiguously true here. The other two go in the short
description, which is also indexed.

**The launcher label stays "OpenWeights"** and is meant to. `app_name` in `strings.xml` is
what sits under the icon on a home screen, where anything past about a dozen characters is
ellipsised into nonsense. Play's listing title and the launcher label are separate fields and
this is the ordinary reason they differ; they are not out of sync and should not be brought
into line.

**Short description** (80 characters)

```
Private offline AI chatbot. Run open-weight LLMs locally on your own device.
```

76 of the 80, and every word is a term somebody searches: private, offline, AI, chatbot,
open-weight, LLM, locally, device. The previous line, "Run open-weight AI models on your
phone. No account, no cloud, no telemetry", spent half its length on three negations that
nobody searches for and that the full description already makes at length.

Not keyword stuffing, which Play's metadata policy prohibits and which reads as spam to a
person: it is one sentence saying what the app is and one saying what it does, and each claim
is true of the build.

**Full description** (4000 characters)

```
OpenWeights runs open-weight language models directly on your phone. There is no account to
create, no server to talk to, and nothing measured about you.

Search Hugging Face from inside the app, find out whether a model will actually run on your
device before you download it, and chat with it. Every token is produced by your own
hardware.

BROWSE THE HUB, RUN WHAT FITS
Other on-device apps hand you a short list somebody else chose. OpenWeights lets you browse
GGUF repositories on Hugging Face, inspect the fit, and run supported architectures locally.

HONEST ABOUT YOUR DEVICE
Before you spend gigabytes, the app reads the model's header over the network and tells you
what it needs at the context length you picked, roughly how fast it will be, and whether it
will run at all on your phone.

REAL NUMBERS, IN FRONT OF YOU
Tokens per second, time to first token, and how full the context window is, shown while you
chat rather than hidden. On a 2024 flagship a 2.6B model answers at 16 to 25 tokens a second
and a follow-up question starts replying in under half a second, because the conversation
already in memory is not read twice.

YOURS TO TUNE
Temperature, top-p, top-k, repeat penalty, context length, the system prompt and what the
model is told about its tools, all saved per model. Where a model can think before it answers
that is a switch too, and on a phone with a working GPU you can say which processor holds the
layers.

MORE THAN TEXT
Images and audio for models that ship a compatible projector, documents supported by the
selected model,
dictation through your phone's own on-device recogniser, and read-aloud.

AN ASSISTANT THAT CAN ACT
The model can search the web, read a page, and work with a folder you choose to share. You
decide how much rope it gets: approve every step, let it run, or ask it to plan first and say
what it would do before anything happens. It can ask you a clarifying question and follow a
checklist you can tick yourself. Every tool can be switched off.

PRIVATE BY CONSTRUCTION
Your conversations, your models and your usage stay on the device. There is no analytics SDK,
no crash reporter, and no backend of ours. Automatic backup and device transfer are switched
off, so a new phone does not inherit your chats. Uninstalling removes everything.

Two tools do reach the internet, because they have to: web search and page fetching. Both are
listed under a heading that says they leave the device, both can be switched off, and every
call one makes is a row in the reply that names it and what it was given. Your Hugging Face
token, if you set one, is encrypted with a key held in the Android Keystore and is sent only
to Hugging Face.

WHAT YOU NEED
Android 12 or newer, a 64-bit ARM device, and enough memory for the model you choose. The app
will tell you before you download.

Models come from third parties. You choose which one to run, and what it says is its
publisher's work rather than ours. Open source, at github.com/alpharomercoma/openweights.
```

**Category**: Productivity, as filed. Tools was the other candidate and is the better fit for
what the app is — the description assumes the reader knows what a GGUF is, and tokens per
second is a first-class feature — but the category is reversible, carries no compliance
weight, and the Tools *tag* below recovers most of the discovery either way.

**Tags**: Personal assistant, Productivity, Privacy & security, Tools. Play's tag vocabulary
is a fixed list, and an earlier version of this line invented two that are not in it.

Four rather than the five allowed, deliberately. Tags decide the peer group Play benchmarks
this app's crash and ANR rates against, so a fifth chosen to fill the slot would put it
beside apps it has nothing in common with and make its vitals read worse than they are.
Nothing else on the list is true of this app.

**Contact email**: the address on the GitHub account. It is shown publicly on the listing,
which is the reason to use one that can absorb it.

**Website**: <https://alpharomercoma.github.io/openweights/>, the same site the privacy
policy is served from.

**External marketing**: left on. It is Google advertising the listing off-Play and involves
no data from inside the app, so it does not touch what the policy claims. Changes to it take
sixty days.

**Privacy policy URL**: the published copy of `docs/privacy-policy.md`. GitHub Pages on this
repository is enough; a raw file URL also works but reads badly.

## Graphics

Made, checked against the spec, and in `play/graphics`. How to regenerate any of them is in
`play/graphics/README.md`.

| Asset | File | Spec |
|---|---|---|
| App icon | `icon-512.png` | 512 x 512, 32-bit with alpha, 3 KB |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 x 500, 24-bit, no alpha |
| Phone screenshots | `screenshots-phone/01..06` | six at 1080 x 1920, 24-bit, no alpha |
| 7-inch tablet | `screenshots-tablet-7/01..05` | five at 1200 x 2133 |
| 10-inch tablet | `screenshots-tablet-10/01..05` | five at 1800 x 3200 |
| Promo video | `play/videos/listing-promo.mp4` | 1920 x 1080, 27.4 s, H.264, silent track |

## The promo video

Made by `play/graphics/cut.py` from real captures; `promo.py` holds the compositor. Four
facts about how Play shows it decided every design choice: it autoplays **inline, muted,
for up to 30 seconds**, so the cut is 25.6 s and says everything in type; it plays in a
**landscape** player, so it is 1920 x 1080; the **feature graphic is its cover image**, so
it stays in that art's colour family and imports the same mark; and **ads must be off**,
which is why the file Play points at carries no music. A Content ID claim can force ads onto
a video, and that would put the listing out of policy, so silence is a compliance decision
before a taste one.

### Two uploads, and which one goes in the box

Play does not host the file. The listing field takes a **YouTube URL**, and Play plays that
video inline and muted. So there is no such thing as "silent on Play, scored on YouTube" for
one upload: whatever is on the YouTube video is on the listing video, muted.

There are therefore two files and two uploads, and only one of them may be pasted into the
Console:

| File | Audio | Where it goes |
| --- | --- | --- |
| `play/videos/listing-promo.mp4` | silent | **the Play listing field** |
| `play/videos/listing-promo-scored.mp4` | original cue | the YouTube channel only |

The picture is byte-identical between them; `play/graphics/score.py` copies the video stream
through and only replaces the audio, so the two can never drift apart.

**YouTube settings for the linked upload**: Public, ads off, not age restricted, not made
for kids. Paste the full `https://www.youtube.com/watch?v=...` form. The thumbnail does not
matter to Play, which uses the feature graphic as the player's cover.

### The score, and what was refused

`play/graphics/score.py` synthesises the cue from oscillators and band-limited noise. No
samples, no loops, no library track: an original composition is still copyrighted, and it is
copyrighted to its author, so the achievable reading of "no copyright" is no third-party
material and nothing for Content ID to match.

The brief asked for an enterprise keynote bed with applause. codex and agy were asked
independently and refused the same two things for the same reasons. **Applause** invents an
audience, which is the argument this project already accepted when it kept UI click foley
out: invented events are the audio equivalent of staging footage, and canned applause over a
screencast reads to this audience as parody. A **corporate hype bed** fails differently, by
scoring unembellished evidence like a launch keynote and telling the viewer to distrust the
thing being proved. Both proposed the same replacement in nearly the same words, and that is
what is here: a sparse electronic pulse, quiet computational momentum rather than triumph.

75 BPM in 4/4 makes a bar exactly 3.2 s, the length of the opening shot, and eight bars
exactly 25.6 s, the length of the film. The pulse starts on frame one with no intro, the
sequence enters at bar 2, a high tick at bar 3, the pad opens at bar 4, the pulse firms up
under the download at bar 5, bar 6 strips back so "no folder shared" is heard in the clear,
bar 7 widens, and at 23.0 s the pulse stops dead for a single low hit and a chord resolving
to the tonic under the end card. No crescendo: "No account" is a statement, not a climax.
Measured on the render, integrated **-18.0 LUFS**, true peak **-2.9 dBTP**, LRA 4.1 LU, and
the loudest bar is bar 7 rather than the ending.

**The silence is deliberate, and so is the track.** The file carries a silent AAC stream at
about -91 dB of encoder noise, which is below the 16-bit floor and inaudible. Stripping it
would not change what anyone hears; it would only produce a container with no audio stream,
which is what a strict transcoder or player refuses. Music is out on the policy above.
Foley is out because there is nothing to sound: the shots are held frames and arriving text,
with no taps or transitions, so clicks would be invented events.

**The ground is not the feature graphic's flat ink, on purpose.** The cover art sits on
`#052B42`, but the app's own dark surface is a neutral near-black (`OpenWeightsColors.Canvas`,
`#0D0E10`). Real capture laid on flat navy read as a sticker on a billboard, so the field is
a vertical gradient from `#06202F` to `#03101A`: dark enough for the capture to settle into,
blue enough to stay in the cover's family. The capture sits on a card with a 26 px radius
masked into the alpha (so the corners are real, not drawn over), a 2 px `#17435F` hairline,
and a navy-tinted shadow rather than a black one, which turns to dirt after compression.

**The mark is imported, never redrawn.** An earlier end card rebuilt the three bars by hand
and put their height at 0.079 of the longest bar instead of the real 0.22, so they drew long
and thin. `endcard()` now calls `mark.draw_mark`, the same function behind the icon and the
feature graphic.

Four shots and an end card, each claim shown by the frame under it: a reply streaming with
the header's `CPU . 32768 ctx` in view; the app's own `114->30 tok/s 15.9s` and `ctx 5%` at
1.28x; one model file's `needs 1.13 GB . ~98 tok/s prefill` before any download; and the
Tools screen across the boundary between its two literal groups, `On this device` and
`Leaves the device`. Hard cuts, because a dissolve also dissolves the two captions and for a
few frames the viewer reads both.

The opening headline types on at **120 characters a second**, finishing in about 0.17 s. The
app decodes roughly 30 tokens a second in that very shot and a token averages about four
characters, so 120 cps is the honest match, and a muted autoplay punishes a headline that
cannot be read at once. The eyebrow, the subhead and the streaming reply are all on screen
at frame one.

**One card, one grid.** Every shot occupies the same 1560 x 700 rectangle at x=180, y=96;
the lime rule spans exactly that card's width; the eyebrow and headline align to its left
edge and the subhead to its right. Every crop is cut to the card's 2.2286:1 aspect and
centred on the phone's own 640 px axis, so the app's content sits centred and nothing
shifts between shots. An earlier version sized the card from each crop, which put the card,
the rule and the caption on three different grids and left 100 px of dead space below
against 74 above. Measured on a render, the margins are now 96 above and 95 below.

**Every headline types on**, at 120 characters a second, so each finishes inside 0.25 s and
reads as the caption snapping into place. codex argued for keeping it to the first shot
only; agy argued that a one-off effect reads as a mistake while a consistent one reads as a
language, and that is the call taken here.

**Held frames, not crops into a scroll.** A rectangle cropped into a moving list cannot be
framed: wherever the cut falls some card is sliced, and the gap above it stops matching the
gap below. That produced the defects this file went through three review rounds without
anyone catching, because codex and agy were reviewing a written description and never saw a
frame. The app's own geometry is now measured off the pixels and the crops land on it: file
cards are 513 px tall on the model page with 33 px gaps, the 8B page's are 411 px on a
444 px pitch, and the Tools screen's row dividers sit at 782, 1065 and 1410, so the crop
opens on a whole row rather than a sliced title. Where a card cannot be isolated, the
neighbours are shown symmetrically, 29 px above and 31 px below, which reads as a list.

**Two shots move, and they are the only two that can.** The opening rides the reply arriving
line by line; the download shot rides the byte counter climbing while its layout stays
perfectly still, which is the one kind of motion a fixed crop can hold. Everything else is
held, with the cut between two views carrying the change. Measured on the finished file, the
longest motionless stretch is 3.0 s and there is a cut about every three seconds.

**The question is one a person would ask.** The opening asks for five things to check
before buying a used phone, and the answer is a list, which is the shape a 1.2B model gets
right and which reads in five seconds. An earlier take asked it to write a message to a
landlord and it wrote *about* contacting one instead: a bad prompt and a worse answer, and
the sort of thing to judge before filming rather than after. The telemetry shot is held from
that same take, so the first two shots are one conversation rather than two.

Those two shots come from the **accelerated debug** build, which carries `applicationIdSuffix
".debug"` and therefore installs alongside a Play copy without touching it. That is the way
to shoot v2 footage on a phone running the published v1: no uninstall, nothing destroyed.

**The copy says what a person would say.** "Then it downloads it", not "fetches", which is
developer language and could even be read as cloud inference. "No folder is shared by
default" rather than "nothing is shared", because the screen is about the workspace folder
specifically and the broader claim would outrun it. "You control what goes online" rather
than "local and networked, split", which was accurate and inert.

**The evidence is paired.** "It says if it fits" over a file that runs comfortably, then
"It says when it is tight" over the 4.80 GB file that wants 5.29 GB, which is the same model
the next shot downloads. Two cards that both said "Runs comfortably" differed so little that
the cut between them did not register, and a caption changing over an apparently unchanged
picture reads as a stuck title.

**Uploading it.** YouTube, 16:9 (not Shorts), **Public or Unlisted** (Private breaks the
listing), embedding allowed, no age restriction, not made for kids, no region blocks,
monetisation off, auto-captions off so they do not sit on the lower third. Wait for HD
processing to finish before pasting the URL into the Console, and paste the plain watch
URL, not a playlist or timestamped link.

**Not in it, on purpose.** The web-search citation chip, because a video whose first claim
is "no sign-in, the model file is on this phone" should not then show the app fetching a
page. The canvas builder, whose tools stay off until a folder is shared and which
returns an empty reply on a 1.2B model, so showing it would mean staging a result. And the two-runtimes claim, which is the product's real differentiator but
had no honest footage: Discover shows no engine label per row, and a `.pte` model's detail
page renders its header with **no file list at all** in build 467, which is a bug worth
fixing and, until it is, a claim the camera cannot support.

The screenshots are the one place the product explains itself, so they are captioned in that
order: the telemetry, a tool round, a plan, the Hub across both runtimes, the Models screen
with a GGUF and a compiled `.pte` side by side, and the off switches. The first one shows
tokens per second, because that number is the difference between this and every assistant
that hides it; the fifth exists because the second runtime is the largest thing that changed
since the listing was first drafted and a single frame can show it.

The feature graphic says the same thing in one image: the lockup, six model families the app
has run on the test phone through both engines, and which file format each engine opens.

They are rendered from the real composables by a Robolectric run rather than captured off a
phone, which is worth knowing for two reasons. A capture from this test handset is
1220 x 2712 and Play rejects any screenshot whose long side is over twice its short one, so a
capture could not have been uploaded as taken. And a render costs one command after a UI
change, where a capture costs an afternoon of staging conversations on a device that has to
be unlocked to drive.

**The launcher icon changed with them.** It had kept the violet from before the palette moved
to brass on graphite, which made it the only screen still wearing the old design and the
first one anybody sees. `docs/design/visual-language.md` says why violet went: it is Qwen's
colour, and the point of brass was not to be standing in someone else's room.

## Data safety form

Play counts data as collected the moment it leaves the device, whether or not anybody stores
it. This app transmits on the user's behalf, so the form says so. Answers, section by section:

**Does your app collect or share any of the required user data types?** Yes.

**Is all of the user data collected by your app encrypted in transit?** Yes. Cleartext is
disabled at the manifest level, so every request is HTTPS.

**Do you provide a way for users to request that their data is deleted?** Yes, in the app.
Deleting a conversation, a model, or the app removes it; there is nothing held elsewhere.

| Data type | Collected | Shared | Purpose | Optional |
|---|---|---|---|---|
| App activity, other actions | Yes | No | App functionality | No |
| App info and performance, other | No | No | | |
| Personal info, other (the Hugging Face token) | Yes | No | App functionality, account management | Yes |
| Files and docs | Yes | No | App functionality | Yes |
| Messages, other in-app messages | Yes | No | App functionality | Yes |

Reasoning for each row, so the declaration can be defended rather than remembered:

- **App activity** covers model searches, which repository was opened, and the queries the
  assistant sends to a search engine. Not optional, because searching the Hub is how a model
  is found at all.
- **Personal info** covers the access token. Optional: public repositories work without one.
- **Files and docs** is declared even though no file is uploaded by itself. Once the
  assistant has read a file in a turn, a search or fetch in that same turn can carry its
  contents, and the app asks first unless the user has typed `/yolo`. That the user had to
  tap is a fact about consent, not a reason to leave the row off, and the mode that skips the
  tap is a reason to keep it on.
- **Messages** covers the same exposure for conversation text: the assistant composes its
  search query out of the conversation, so what you typed can reach a search engine.

Nothing is marked as shared. "Shared" in Play's sense means transferring to a third party for
their own purposes; these transfers are the user's own request being carried out, which Play
counts as collection rather than sharing. No data is processed ephemerally-only, because the
recipients are third parties who keep their own logs.

Nothing is collected for analytics, advertising, personalisation, or fraud prevention,
because none of those exist here.

## Generative AI content declaration

**Does your app contain generative AI features?** Yes: it runs a language model the user
chose and shows what it produces.

**How does your app handle offensive output?** There is a report action on every reply. It
offers a reason, an optional note, and shows exactly what the report will contain before it is
filed. The report is stored on the device.

Two things to raise with review rather than assume:

1. Whether device-local reporting satisfies the policy for an app with no backend. The control
   is in the app and requires no exit, which is what the policy asks for; the expectation that
   reports inform the developer is satisfiable here only if the user chooses to send one.
2. That the models are third-party and user-chosen, which the listing says in its last
   paragraph.

## Foreground service declaration

Type `dataSync`, for model downloads. The words to use and the video to record are in
[play-store.md](play-store.md#the-foreground-service-declaration).

## Content rating questionnaire

One questionnaire produces every regional rating at once: ESRB for North America, PEGI for
Europe, USK for Germany, ClassInd for Brazil, GRAC for Korea, ACB for Australia. Answer it
about **what the app itself contains and does**, which for this app is the whole difficulty,
because the app authors no content at all and displays whatever a model the user chose
produces.

Misrepresenting content gets an app removed, and under-declaring is the most common reason a
rating is rejected, so where a question is genuinely ambiguous the answer below is the one
that declares more rather than less.

**Category.** Utility, Productivity, Communication, or Other. Not the
Social/Forums/User-Generated-Content category: nothing any user types is ever seen by another
user, because there is no account, no server and no sharing.

**The questionnaire asks what the app can contain, and this app can contain whatever a model
says.** An earlier draft of this section answered No to the content descriptors on the
grounds that the app authors nothing and ships no model. That reasoning is true and the
answer it produced was wrong, which an outside review caught before submission.

The exclusion printed beside the question — "this does not refer to user-generated content" —
is there to stop a social app declaring Yes merely because its users can swear at each other.
It does not cover this app. Model output is not another user's content; it is content this
app generates and puts on screen, and Google's AI-generated content policy makes the
developer answerable for it. There is no output filter anywhere in the turn path, the weights
are chosen by the user from a third-party repository, and an uncensored one can produce every
category below. Nothing here can promise otherwise.

So the descriptors are answered for what the app is capable of rather than for what it
authors, and the asymmetry decides the doubtful ones: under-declaring is the most common
cause of a rejected rating and Play treats it as misrepresentation, while over-declaring
costs a higher age band and nothing else.

| Section | Answer | Why |
|---|---|---|
| Potentially offensive language | **Yes** | A model the user chose can produce it and nothing filters the output |
| — is it the focus of the app? | No | The app runs models. It is not about the language they produce |
| — minor profanities | **Yes** | As above |
| — moderate or significant swearing | **Yes** | As above |
| — discriminatory language | **Yes** | As above. An uncensored model will produce it if asked |
| — sexual expletives | **Yes** | As above |
| Violence | **Yes** | Described rather than depicted, but a model will describe it |
| Sexuality and nudity | **Yes** | In text, for the same reason |
| Controlled substances | **Yes** | A model asked about drugs answers about drugs |
| Horror and fear | **Yes** | In text |
| Gambling and contests | No | Nothing in the app wagers, and no model output is a wager. No loot, no prizes, no simulated gambling |

Expect a high band back, around Mature 17+ and PEGI 16 to 18, which is where the comparable
assistants sit and is the honest price of the answer above. Do not tune the answers to reach
a lower one: a rating authority can override the result, and being overridden means retaking
the questionnaire with the reviewer already sceptical.

### The four that are not about depicted content

These appear on the listing as interactive elements rather than raising the age band on their
own, and they are where an app like this actually has something to declare.

| Element | Answer | Why |
|---|---|---|
| Users interact | **No** | No accounts, no server, no messaging, no sharing. Conversations are rows in a local database and reach nobody |
| Shares location | **No** | The app asks for no location permission and sends no location |
| Digital purchases | **No** | No billing library, no purchases, no ads |
| Unrestricted internet | **Yes** | `fetch_url` retrieves a public web address the model chose and shows what came back. The user does not browse, and a fetch asks first once anything has been read in that turn, but the content that arrives is unfiltered and that is what the question is about |

### Two separate declarations, and only one of them is user-generated content

An earlier draft said to answer **yes** to user-generated content and point at the report
action. That conflates two separate things, and answering yes there would have been
inaccurate in the direction that costs the most:

- **User-generated content**, as Play means it, is content one user creates that *another*
  user can see. There are no other users here. Answering yes puts the app in the social
  category and attracts moderation obligations no app without a backend can meet.
- **AI-generated content shown to the person who asked for it** is a different declaration
  entirely, and it lives in the Generative AI section above, where the report action is the
  in-app flagging feature that policy requires.

Declare the second. Do not declare the first.

### What to expect back, and the one thing worth pre-empting

With nothing depicted and unrestricted internet declared, the bands should come back low,
around ESRB Everyone and PEGI 3 to 7, with "Unrestricted Internet" printed beside them. Do not
tune the answers to reach a number; a rating authority can override the result, and being
overridden means retaking the questionnaire with the reviewer already sceptical.

The thing most likely to draw a question is not in the questionnaire at all: **this app runs
weights the user chose, so the developer cannot promise what the model will say.** Google's
AI-generated content policy makes the developer responsible for output. Say plainly, in the
review notes, what is true:

- The app ships no model and no default that produces restricted content. Every model is
  downloaded by the user from a third-party repository they picked.
- Output is generated on the device, shown only to the person who asked, and never
  transmitted, published or shared by the app.
- Every reply carries a report action, which is the in-app flagging the policy requires.
- The listing's last paragraph already says the models are third-party and their behaviour is
  their publishers'.

Retake the questionnaire if any of this changes; a shipped model catalogue would change it.

## Release track

Internal testing first, then read the pre-launch report before promoting. It runs the app on
real devices, which is the cheapest way to find a crash on hardware nobody here owns, and
this app has no crash reporter of its own.
