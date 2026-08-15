# Play Store release checklist

What has been verified, and what a human still has to do in the Play Console. Every claim
below was checked against the build rather than assumed.

## Verified in the build

| Requirement | State | How it was checked |
|---|---|---|
| `targetSdk` 36 | Done | Required for new apps from 2026-08-31 |
| 16 KB page alignment | Done | All 18 native libraries have `2**14` LOAD alignment |
| ABI | arm64-v8a only | 32-bit ARM cannot usefully run inference |
| R8 minify and resource shrink | On, **and the result is run on a device** | See below |
| Android lint, release variant | Clean | Now part of `./gradlew verify` |
| Upload signing | Config reads from `keystore.properties` or environment | Never from the repository |
| Cleartext traffic | Disabled | `usesCleartextTraffic="false"` |
| Backup and device transfer | Everything excluded | `data_extraction_rules.xml` |
| Download size | 20.9 MB AAB, no bundled model | Under the 200 MB cellular threshold with room to spare |

### R8 nearly shipped a broken app

JNI resolves by name at runtime. R8 renames `io.github...ui.Destination` to `q90`, and
would have done the same to `LlamaBridge`, its 14 `external fun` declarations, the
`TokenSink` and `ReplySink` callback interfaces that native code finds with `GetMethodID`,
and `LlamaException`, which native code finds with `FindClass` in order to report a failure.
Every one of those would have become an `UnsatisfiedLinkError` or a null method id the
first time a user loaded a model, and nothing in the test suite would have noticed, because
the tests run on the debug variant.

`core/engine/consumer-rules.pro` keeps exactly that surface, and it is a consumer rule so
it travels with the module. Verified by installing the signed, minified release on a
Snapdragon 8 Elite and generating: the model loaded, the OpenCL backend registered, and
the reply came back at 25 tok/s.

**Any change to the JNI surface has to be re-checked against a release build.** The debug
build cannot tell you.

## Permissions, and why each one exists

| Permission | Why | When asked |
|---|---|---|
| `INTERNET` | Hugging Face search and downloads, and the web tools below | Never prompted; normal permission |
| `RECORD_AUDIO` | Dictation, through the on-device recogniser only | First time the mic is tapped |
| `POST_NOTIFICATIONS` | Says a reply has finished, and shows download progress | The first time a reply or a download starts |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keeps a model download running once the app is off screen | Never prompted; normal permissions |

An earlier version of this document said `POST_NOTIFICATIONS` had been declared, never
used, and removed. It is declared and it is used: a reply on a phone takes tens of seconds
and sometimes minutes, which is long enough to put the phone down, and `ReplyNotifier`
posts exactly one notification when the app is not on screen.

It used to be asked for in `onCreate`, which put a system dialog over the first frame a new
user ever saw and paused the activity underneath it. The argument for that was sound about
the wrong end of the wait: by the time a reply lands the phone is in a pocket, which is no
moment for a dialog. So it is asked when the waiting starts instead, on the first send or the
first download, with the phone in the user's hand and the reason in front of them. Somebody
who opens the app to look at it is never asked at all. `android.hardware.microphone`
is declared `required="false"`, because `RECORD_AUDIO` otherwise makes Play hide the app
from every device without a microphone, and dictation is one optional way to enter text.

### The foreground service declaration

There is one, of type `dataSync`, and it needs the Play Console declaration form and a
video. What to say on it:

- **What the service does.** Downloads a model file the user has explicitly chosen, from
  Hugging Face to the app's own storage. One to eight gigabytes, which is minutes on a phone
  connection.
- **Why it has to run in the foreground.** Nobody watches a progress bar for minutes. The
  moment the user switches apps, Android is free to reclaim the process, and without the
  service the transfer stops and they come back to a bar that has not moved. The app is
  useless until a model finishes arriving, so this is not a background convenience, it is
  the one transfer the product depends on.
- **Why no other API fits.** `setExpedited` gives a few minutes at most. `DownloadManager`
  cannot verify the Hub's SHA-256, cannot resume through the Hub's redirect chain with an
  `Authorization` header intact, and writes into shared storage the app would then have to
  copy out of, doubling the space needed for a file this size.
- **User visibility and control.** A low-importance notification shows the model's name and
  the bytes transferred, with a Cancel action wired to `WorkManager.createCancelPendingIntent`.
  It only appears while a download the user started is running.

The video needs to show: tapping download in Discover, the notification appearing, leaving
the app, the notification still counting up, and Cancel stopping it.

## Data safety form

An earlier draft of this document said "data collected: none". That is wrong and would
have been a false declaration. Play counts data as collected when it is transmitted off the
device **at all**, including when it is only processed in flight and never stored. The app
transmits to Hugging Face on the user's behalf, so the form has to say so.

What actually leaves the device:

| Leaves the device | When | Declared as |
|---|---|---|
| Search terms typed into Discover | On search | App activity, or search history |
| Repository and file identifiers | On open and download | App activity |
| The Hugging Face access token, if the user set one | Every Hub request, as an `Authorization` header | Credentials |
| **What the model decides to search for** | Whenever it uses `web_search`, which is on by default | App activity, and treat it as user content |
| **A page address the model chose, and the request for it** | Whenever it uses `fetch_url`, which is on by default | App activity |
| **Text out of a file in the shared folder** | Only if the user approves a search or fetch after `read_file` has run in the same turn | **Files and docs**, and treat it as user content |
| Standard request metadata, including IP | Every request above | Handled by the recipient |

**Files and docs is the row that came with the file tools, and it is the one to be careful
about.** Nothing about a shared folder is transmitted by itself: the tools read locally, and
the folder is never uploaded. The exposure is indirect and worth stating plainly, because it
is the sort of thing a reviewer finds and a declaration should not have to be defended
afterwards. A file can hold text somebody else wrote, a small model is very good at
repeating a pattern it was just shown, and a search query is a way off the device. So after
`read_file` has put a file's contents into a turn, `web_search` and `fetch_url` ask before
they run, in every mode, for the rest of that turn. Answering yes is the user transmitting
their own file, which is a reasonable thing to let somebody do and not a reasonable thing to
do on their behalf. `search_files` is not part of this: it reports paths and never contents.

Declare Files and docs anyway. The control makes the transfer deliberate; it does not make
it impossible, and "the user had to tap" is a fact about consent rather than a reason to
leave the row off the form.

The two bold rows above it are the ones this document previously got wrong, and they are the ones
that matter most. It used to say that chats and prompts never leave the device. That has
not been true since the web tools shipped, and they ship **switched on**: `ToolSwitches`
defaults every tool to enabled. The query is composed by the model rather than typed by the
user, which makes it no less sensitive, because the model composes it out of the
conversation. Anything the user pasted or attached can end up in it. The form should be
filled in on that basis.

What still never leaves the device: the conversation itself, model replies, attachments,
usage totals and content reports. There is no analytics SDK, no crash reporter, no account
and no backend of ours. The model runs here and nothing about a reply is uploaded.

**Hosts contacted.** `huggingface.co` and the delivery hosts it redirects downloads to;
`duckduckgo.com` for search; and, through `fetch_url`, whatever host the model found in a
search result. That last one cannot be enumerated in advance, which is worth saying plainly
in the privacy policy rather than implying a fixed list. The listing should say "Hugging
Face and its content delivery network, DuckDuckGo, and pages the assistant is asked to
read".

**The token** is encrypted with an AES-GCM key held in the Android Keystore, is attached
only to Hub requests, and is never logged. It is optional; the app works without one for
public repositories.

**Deletion.** Everything of ours is on the device. Deleting a conversation deletes its
messages and the files attached to them; uninstalling removes all of it. Data held by
Hugging Face as a result of the user's own requests is subject to their policy, and the
listing should link to it.

## Generative AI content policy

Play requires an app that generates AI content to let people **report or flag offensive
output from inside the app, without leaving it**. An earlier draft of this document called
that a decision for a human and noted there was no such flow. That was a release blocker,
not a decision.

There is now a report action on every model reply. It offers a reason, an optional note,
and shows exactly what the report will contain before it is filed: the model name, the
reason, the note, and the reply itself. The report is stored on the device in a
`content_reports` table, added by `MIGRATION_2_3`.

Verified on a Snapdragon 8 Elite in the debug build: all four message actions render, the
report sheet opens, a reason can be chosen, and submitting dismisses it. Three unit tests
cover the write against a real Room database, including that a report with no model is
refused rather than filed against nothing. **The report action has not been driven by hand
in the release variant**, because the device tunnel dropped first; the label is present in
the minified dex and the other conditional rows in the same file render there, so the risk
is low but it is not zero.

Adding the fourth action exposed a layout bug worth remembering. The actions sheet was
using the default partially-expanded state, which clipped the last row off the bottom with
nothing to indicate it existed. Adding `verticalScroll` made it worse rather than better:
a scrollable column will accept any height, so the sheet handed it the leftover space and
still showed three rows. The fix is `skipPartiallyExpanded = true` and no scroll, letting
the sheet size to its content.

Nothing is transmitted, because there is no server to transmit to and acquiring one would
break the only promise this app makes. What the reports are for is the app itself: they are
the sole quality signal available when nothing is measured remotely, and a model that
collects reports is one worth warning the next person about.

Two things still need a human before submission:

1. Whether Play accepts device-local reporting for an app with no backend. The reporting
   control is in-app and requires no exit, which is what the policy asks for, but the
   expectation that reports "inform the developer" is only satisfiable here if the user
   chooses to send one. Ask review directly rather than guessing.
2. The listing should say plainly that the user chooses the model, that models come from
   third parties, and that their behaviour is the publisher's rather than ours.

## Still to do, and none of it is code

Every box that can be filled in ahead of time is filled in, in
[store-listing.md](store-listing.md): the name, both descriptions, the data safety answers row
by row with the reasoning behind each, the generative AI declaration, and the content rating
notes. What is left is the part that needs a person, a key, or a graphics tool.

1. Create the upload key and enrol in Play App Signing. Never commit it.
2. Publish [privacy-policy.md](privacy-policy.md) at a public URL and paste it into the
   Console. GitHub Pages on this repository is enough.
3. Make the feature graphic and the phone screenshots. Sizes are in the listing document.
4. Answer the content rating questionnaire.
5. File the generative AI content declaration, and ask review the two open questions in it.
6. Record the foreground service video and submit that declaration.
7. Internal testing track, then read the pre-launch report. It runs the app on real
   devices and is the cheapest way to find a crash on hardware we do not own.
8. Decide the launch countries and whether an age rating gate is needed.

## Known gaps a reviewer would be right to raise

- **No baseline profile**, so first-run startup and first scroll are slower than they need
  to be. The release build carries the merged profiles of the AndroidX libraries it uses
  and nothing of its own.
- **No crash reporting**, by choice. A crash on a device we do not own is invisible to us
  unless a user opens an issue. The pre-launch report partly covers this.
- **The web tools are on by default.** `web_search` and `fetch_url` are switched on the
  first time the app runs, so a question can leave the device before the user has looked at
  the Tools tab. Everything else in the app is local, which makes this the one place the
  promise bends, and the data safety section above declares it. `fetch_url` will only reach
  public addresses, so a page cannot talk the model into reading the router, but that is a
  bound on the damage rather than an answer to the question of what the default should be.
- **No upload key.** `keystore.properties` does not exist in this checkout, so
  `bundleRelease` produces an unsigned AAB. Creating the key and enrolling in Play App
  Signing is the first Console step and has deliberately not been done for you.
