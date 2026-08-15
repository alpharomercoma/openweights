# Play Console: what to put in each box

Everything a human has to type or upload, written out so the submission is a matter of
copying rather than composing. The claims here match the app; where a form asks something the
code decides, the code was read rather than remembered.

The checklist of what has been verified in the build is in [play-store.md](play-store.md).
The policy this listing has to link to is [privacy-policy.md](privacy-policy.md), which needs
a public URL before submission.

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

**Category**: Tools. **Tags**: AI assistant, developer tools.

**Contact email**: the address on the GitHub account.

**Privacy policy URL**: the published copy of `docs/privacy-policy.md`. GitHub Pages on this
repository is enough; a raw file URL also works but reads badly.

## Graphics still to make

| Asset | Size | Notes |
|---|---|---|
| App icon | 512 x 512 PNG | The launcher icon at listing size |
| Feature graphic | 1024 x 500 PNG | No screenshot content; it is cropped hard on some surfaces |
| Phone screenshots | 2 to 8, 16:9 or 9:16 | Chat mid-reply with the token counter visible; the fit estimate in Discover; the Tools tab; a plan with its steps |

The screenshots are the one place the product explains itself. The reply-in-progress shot
should show tokens per second, because that number is the difference between this and every
assistant that hides it.

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

Answer honestly on the basis that the app displays user-directed generative output:

- No violence, sexuality, profanity or drug references authored by the app.
- Users can interact with a model whose output is not controlled by the app. Where the
  questionnaire asks about user-generated content, say yes and point to the report action.
- No user-to-user communication, no location sharing, no purchases, no ads.

## Release track

Internal testing first, then read the pre-launch report before promoting. It runs the app on
real devices, which is the cheapest way to find a crash on hardware nobody here owns, and
this app has no crash reporter of its own.
