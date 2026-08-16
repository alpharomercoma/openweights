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
OpenWeights
```

**Short description** (80 characters)

```
Run open-weight AI models on your phone. No account, no cloud, no telemetry.
```

**Full description** (4000 characters)

```
OpenWeights runs open-weight language models directly on your phone. There is no account to
create, no server to talk to, and nothing measured about you.

Search Hugging Face from inside the app, find out whether a model will actually run on your
device before you download it, and chat with it. Every token is produced by your own
hardware.

ANY MODEL, NOT A CATALOGUE
Other on-device apps hand you a short list somebody else chose. OpenWeights hands you the
Hub. If someone published a GGUF an hour ago, you can run it.

HONEST ABOUT YOUR DEVICE
Before you spend gigabytes, the app reads the model's header over the network and tells you
what it needs at the context length you picked, roughly how fast it will be, and whether it
will run at all on your phone.

REAL NUMBERS, IN FRONT OF YOU
Tokens per second, time to first token, and how full the context window is, shown while you
chat rather than hidden.

YOURS TO TUNE
Temperature, top-k, top-p, min-p, repeat penalty, context length, system prompt and threads,
saved per model, with presets.

MORE THAN TEXT
Images and audio for models that ship a projector for them, documents any model can read,
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

Two tools do reach the internet, because they have to: web search and page fetching. The
first time either would send anything, the app asks and shows you the request, and both can
be switched off in the Tools tab. Your Hugging Face token, if you set one, is encrypted with
a key held in the Android Keystore and is sent only to Hugging Face.

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
| App icon | `icon-512.png` | 512 x 512, 32-bit with alpha, 54 KB |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 x 500, 24-bit, no alpha |
| Phone screenshots | `screenshots-phone/01..05` | five at 1080 x 1920, 24-bit, no alpha |

The screenshots are the one place the product explains itself, so they are captioned in that
order: the telemetry, a tool round, a plan, the Hub, and the off switches. The first one
shows tokens per second, because that number is the difference between this and every
assistant that hides it.

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
  contents, and the app asks first. That the user had to tap is a fact about consent, not a
  reason to leave the row off.
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
| Unrestricted internet | **Yes** | `fetch_url` retrieves a public web address the model chose and shows what came back. The user does not browse, and every fetch is approved, but the content that arrives is unfiltered and that is what the question is about |

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
