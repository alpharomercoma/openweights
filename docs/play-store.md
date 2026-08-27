# Play Store release checklist

What has been verified, and what a human still has to do in the Play Console. Every claim
below was checked against the build rather than assumed.

## Verified in the build

Everything in this table was re-checked against the artifact on 2026-08-24, not against the
intent. Where a row says "measured", the command is in the row.

| Requirement | State | How it was checked |
|---|---|---|
| `targetSdk` 36 | Done | `aapt2 dump badging` on the release APK |
| 16 KB page alignment | Done | All **19** native libraries report `2**14` LOAD alignment under `llvm-objdump -p` |
| ABI | arm64-v8a only | `native-code: 'arm64-v8a'` in the badging dump |
| R8 minify and resource shrink | On, **and the result is run on a device** | See below |
| JNI names survive R8 | Done | `verifyJniSymbols`: all 6 names present in both the APK and the AAB |
| Debuggable | Off | No `application-debuggable` in the badging dump |
| Android lint, release variant | Clean | Part of `./gradlew verify` |
| Upload signing | Config reads from `keystore.properties` or environment | Never from the repository |
| Cleartext traffic | Disabled | `usesCleartextTraffic="false"` |
| Backup and device transfer | Everything excluded | `data_extraction_rules.xml` |
| Download size | **23.5 MB AAB**, no bundled model | Under the 200 MB cellular threshold with room to spare |

### The release build was run, not just built

A minified, resource-shrunk release APK signed with a throwaway key was installed on a
Snapdragon 8 Gen 3 and driven by hand: the app launched, chose
`libggml-cpu-android_armv8.6_1.so`, registered the OpenCL backend, loaded LFM2.5 2.6B at a
4096 token window, and answered a question at 9.8 tok/s. Tools were offered and none were
called, which is the right answer to "name one colour".

That is the check nothing else can stand in for. R8 does not run in a debug build, and the
one failure this catches, a renamed JNI symbol, is invisible until a user loads a model.
`verifyJniSymbols` is the cheap guard and this is the real one; do both.

The throwaway key is exactly that. It lives outside the repository, it is not the upload key,
and it exists only so a minified build can be installed. Play App Signing is still step one
of the Console work.

One trap, since it cost a run: a release APK signed with a throwaway key and the
`nonMinifiedRelease` build the profile generator installs share an application id and do not
share a signature, so the second refuses to replace the first with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall before generating.

### The baseline profile is ours, but must be refreshed after launch changes

`:baselineprofile` drives the app on a device and records what a cold start and one visit to
each tab actually run, so ART compiles it ahead of time instead of interpreting it. The
result is checked in at `app/src/release/generated/baselineProfiles/baseline-prof.txt`, which
is what lets a machine with no phone attached still build a profiled release.

Measured on the previous artifact, before and after: the release APK carried one 10.6 KB `.dm` of
merged AndroidX profiles and nothing of ours; it now carries two, 12.5 and 12.6 KB, and the
profile behind them has 1,658 lines naming our own classes out of 24,749. `profileinstaller`
is a dependency now as well, because a profile that ships and is never applied is the quiet
way to do this work twice.

The checked-in profile predates the current drawer-first navigation and branded splash launch
path. Treat its numbers as historical until a representative device records the current
startup flow; do not use them as a release performance claim.

```
./gradlew :app:generateReleaseBaselineProfile     # needs a device, rewrites the file above
```

Re-record it when startup changes shape. A stale profile is not wrong, only less useful: it
describes methods that still exist and misses the ones that replaced them.

### R8 nearly shipped a broken app

JNI resolves by name at runtime. R8 renames `io.github...ui.OpenWeightsApp` to `q90`, and
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

Nine, not five. The four at the bottom are WorkManager's and are in the shipped manifest
whether or not anybody writes them down; this table used to list only the ones we declare by
hand, which is not the same list a reviewer sees.

| Permission | Why | When asked |
|---|---|---|
| `INTERNET` | Hugging Face search and downloads, and the web tools below | Never prompted; normal permission |
| `RECORD_AUDIO` | Dictation, through the on-device recogniser only | First time the mic is tapped |
| `POST_NOTIFICATIONS` | Says a reply has finished, and shows download progress | The first time a reply or a download starts |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keeps a model download running once the app is off screen | Never prompted; normal permissions |
| `WAKE_LOCK` | WorkManager, so a download in progress is not suspended mid-transfer | Never prompted |
| `ACCESS_NETWORK_STATE` | WorkManager, to honour the "needs a network" constraint on a download | Never prompted |
| `RECEIVE_BOOT_COMPLETED` | WorkManager, to resume an unfinished download after a reboot | Never prompted |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | WorkManager's own signature permission, guarding its internal broadcasts | Never prompted, not user visible |

`RECEIVE_BOOT_COMPLETED` is the one that looks alarming on a listing, so it is worth being
able to answer for: nothing of ours runs at boot. It is there so that a model download the
user started, which can be gigabytes and take longer than a phone stays awake, survives a
restart instead of beginning again. Removing it would need `tools:node="remove"` and would
break exactly that.

**Implied features.** `aapt2 dump badging` reports `android.hardware.faketouch` and
`android.hardware.screen.portrait`, neither of which is declared. They are derived, the second
from `android:screenOrientation="portrait"` on the activity, and Play filters devices on
implied features as well as declared ones. In practice that excludes nothing with a touch
screen, but it is the reason the listing should not promise anything about desktop-shaped
devices.

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

### The foreground service declarations

There are **two**, of different types, and Play asks about each one separately. Both need
the declaration form and a video.

| Type | Service | What it is for |
| --- | --- | --- |
| `dataSync` | WorkManager's `SystemForegroundService` | Downloading a model the user chose |
| `specialUse` | `runtime.GenerationService` | Letting a reply, a goal, or a Watch's own check finish when the app is not on screen |

#### 1. `dataSync`, for downloads

What to say on it:

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

#### 2. `specialUse`, for generation

Added when the app learned to keep answering after the user leaves the screen, and extended
without a new declaration when a Watch learned to check on its own schedule: both hold the
same service, for the same reason, and Play asks about the service, not the feature that
happens to be using it at the moment. `specialUse` is the type Play scrutinises hardest,
because it is the one with no fixed meaning, so the declaration has to carry the measurement
rather than an assertion.

The manifest already states the subtype, and the Console answer should match it:

```
On-device language model inference the user asked for, right away or on a schedule they set
```

- **What the service does.** Keeps the app's process running while a language model, loaded
  from the user's own storage, produces a reply, works through a goal, or runs one tick of a
  Watch the user set up. The service itself transfers nothing and contacts nothing — it
  raises the process and does no networking of its own — and the reply generation it holds
  the process open for is arithmetic on this device's own processor. What it holds open for a
  goal or a Watch step can be more than that: if `web_search` or `fetch_url` are on (see
  below), the turn those tools run inside is the same turn this service is holding, so a
  research step reaching the network happens inside the window this service keeps alive, not
  outside it.
- **Why it has to run in the foreground.** Measured on an Android 14 phone: backgrounding
  the app mid-reply takes its `oom_score_adj` from 0 to between 400 and 700, puts it in the
  cached process state, and the process then accumulates **zero** CPU ticks. Android freezes
  cached processes, so a reply in flight does not slow down, it stops. With the service the
  same measurement reads `oom_score_adj` 50, process state 4, and 1,440 CPU ticks over ten
  seconds. A reply takes tens of seconds on a phone, a goal takes minutes, and a Watch is the
  same turn run again on the schedule the user picked; none of them run at all if the process
  is frozen the moment the screen goes off.
- **Why no other type fits.** `dataSync` is for transferring data, which this does not do,
  and misdeclaring it would be worse than asking; Android 15 also caps it at six hours a
  day, which a Watch checking for weeks would exceed. `shortService` ends after three
  minutes, which is shorter than one long answer on a mid-range chip and far shorter than a
  Watch's own lifetime. `mediaPlayback`, `camera`, `location`, `phoneCall`,
  `connectedDevice`, `mediaProjection`, `health` and `remoteMessaging` describe things the
  app does not do.
- **Why not WorkManager instead.** The work is not deferrable and not restartable. It is one
  reply or one Watch tick, in progress, holding the model's KV cache in memory; a worker that
  was killed and retried would start the answer again from nothing, and the cache it rebuilt
  would cost the user the eleven to nineteen seconds of prefill measured elsewhere in this
  repository. A Watch checking more often than every fifteen minutes also cannot be
  WorkManager's `PeriodicWorkRequest` at all: that API refuses to repeat faster than fifteen
  minutes, silently rounding a shorter interval up, which is why a fast Watch holds this
  service instead and a fifteen-minute-or-slower one uses `PeriodicWorkRequest` as its own
  backstop rather than holding anything.
- **User visibility and control.** A low-importance notification says what is happening and
  opens the app when tapped. For a reply or a goal it appears only while the model is
  producing something the user started, and is taken down the moment the turn ends, however
  it ends, including when it is cancelled or fails. For a Watch checking faster than every
  fifteen minutes, the same notification stays up for as long as that Watch is active, names
  the check by the task the user gave it, and is taken down the moment the user stops or
  removes it, or three checks in a row fail.

The video needs to show: asking a question, the notification appearing, leaving the app, the
reply still arriving when you come back, and the notification gone once it has finished. If
the goal feature is being demonstrated in the same video, show it advancing through more
than one step with the app off screen. If a Watch faster than fifteen minutes is being
demonstrated, show it being set up, the notification appearing and naming the check, at
least one tick landing in the Watching screen with the app off screen, and the notification
gone once the Watch is stopped — since that is the other case the service exists for.

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
| **Text out of a file in the shared folder** | Only if the user approves a search or fetch after `read_file` has run in the same turn, or has typed `/yolo` | **Files and docs**, and treat it as user content |
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

**`/yolo` waives that prompt, and the row is written to cover it.** It is a mode the user
types, it is named on screen while it is on, and it does not survive the process, but while
it is on a file that has been read can leave with a search or a fetch and nobody is asked.
That is why the row says "or has typed `/yolo`" rather than describing the prompt as the only
route: a declaration that holds only in the default mode is a declaration that is wrong for
whoever changed the mode.

Declare Files and docs anyway. The control makes the transfer deliberate; it does not make
it impossible, and "the user had to tap" is a fact about consent rather than a reason to
leave the row off the form. That argument is stronger, not weaker, once a mode exists that
skips the tap.

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

## Version codes are counted, not typed

Play's only rule for a version code is that it must be higher than every code uploaded
before it, forever, and there is no way back: a code that has been used is used, and a
bundle repeating one is refused at the door. Typing it by hand is a promise to remember
something indefinitely while thinking about something else.

So `versionCode` is `git rev-list --count HEAD`, and `versionName` is still typed. That split
is the point rather than an accident: a version name says how big a change this is, which is
a judgement no tool can make, and a version code is a counter Play uses to order uploads and
nothing else.

The commit count was chosen over the two obvious alternatives:

- **A CI build number** does not survive a workflow being renamed or recreated, and it
  resets to one when it happens. A version code that goes down cannot be undone, and the
  same source would build a different code on a laptop than in CI.
- **Resolving from Play** — which the Triple-T plugin can do — needs a service account, a
  secret, and a network call, to answer a question the repository already knows. It is the
  right tool once uploads are automated, and it is a lot of machinery for a counter.

**A shallow clone breaks this, quietly, and that is the part worth knowing.**
`actions/checkout` fetches one commit by default, so the count is 1 there and a hundred and
something locally: the same source, two different codes, and the wrong one coming from the
machine that builds what ships. Worse, 1 is what used to be typed in, so it would have
looked right. Measured on this repository:

```
full clone     is-shallow=false   count=186
shallow clone  is-shallow=true    count=1
```

The build therefore refuses a shallow clone rather than believing it, and the workflow asks
for `fetch-depth: 0`. Release from `main`: the count is per branch, and a branch with fewer
commits builds a lower code, which Play rejects rather than accepts.

### Automating the upload itself

Not done, and it is a separate job from the version code. The shape is a workflow triggered
by a tag, which builds the bundle and hands it to Play, and it needs two secrets this
repository deliberately does not have: the upload keystore, and a Play service account key
with release permissions. Both are worth adding once there is something to release
repeatedly; until the first upload is done by hand there is nothing for it to promote.

## Still to do, and none of it is code

Every box that can be filled in ahead of time is filled in, in
[store-listing.md](store-listing.md): the name, both descriptions, the data safety answers row
by row with the reasoning behind each, the generative AI declaration, and the content rating
notes. What is left is the part that needs a person, a key, or a graphics tool.

1. Create the upload key and enrol in Play App Signing. Never commit it.
   Upload `app/build/outputs/mapping/release/mapping.txt` with the bundle, or every crash in
   the pre-launch report and in Android vitals arrives as `q90.a()`. It is 53 MB and is
   produced by every release build; Play takes it from the same upload screen.
2. Publish [privacy-policy.md](privacy-policy.md) at a public URL and paste it into the
   Console. GitHub Pages on this repository is enough.
3. Make the feature graphic and the phone screenshots. Sizes are in the listing document.
4. Answer the content rating questionnaire.
5. File the generative AI content declaration, and ask review the two open questions in it.
6. Record both foreground service videos, download and generation, and submit both declarations.
7. Internal testing track, then read the pre-launch report. It runs the app on real
   devices and is the cheapest way to find a crash on hardware we do not own.
8. Decide the launch countries and whether an age rating gate is needed.

## Known gaps a reviewer would be right to raise

- **No crash reporting**, by choice. A crash on a device we do not own is invisible to us
  unless a user opens an issue. The pre-launch report partly covers this, which is why
  `mapping.txt` has to go up with the bundle.
- **The web tools are on by default, and the default mode runs them without asking.**
  `web_search` and `fetch_url` both ship switched on, in `ToolSwitches`, for the reason
  documented there: a tool that ships off is a feature nobody finds. There was once a
  one-time prompt before the first thing either tool ever sent, so that discovery and
  consent were the same event; it is gone, deliberately, per the note at
  `AgentRunner.allowed`. An agent that stops to ask whether it may search is not an agent,
  and the call is not hidden afterwards: it is a row in the reply that used it, naming the
  tool and what it was given, in the same conversation the user is already reading. Turning
  either tool off is one tap away in the Tools tab, which is named as the place to look
  wherever this is declared to the user.

  What still asks, in every mode but `/yolo`, is narrower and is about two specific risks
  rather than about using the tools at all: a call whose destination could have been steered
  by something untrusted the turn just read (a page telling the model where to go next —
  `fetch_url` only, since `web_search`'s destination is the configured provider regardless of
  what the query says), and a call that could carry data off the device after the turn has
  read something private (either tool). Neither condition is the ordinary case, so the
  ordinary search or fetch does not stop to ask, and the two that do are declared as such
  rather than folded into "the tools ask before they run."
- **No upload key.** `keystore.properties` does not exist in this checkout, so
  `bundleRelease` produces an unsigned AAB. Creating the key and enrolling in Play App
  Signing is the first Console step and has deliberately not been done for you.
- **A fast Watch restored on a background process start can run unprotected.**
  `OpenWeightsApplication.onCreate` calls `watches.sync()` on every process start
  (`OpenWeightsApplication.kt`), including one the system triggered in the background rather
  than one a person opening the app caused. `GenerationService.hold` deliberately swallows a
  refused `startForegroundService` call — Android 12+ can refuse one from a background start
  — and generates anyway rather than crashing, which is the right call for a turn the user is
  looking at (`GenerationService.kt`). A fast Watch's own ticker inherits that same swallow:
  it starts regardless of whether the hold actually got the foreground guarantee, so a Watch
  restored this way can tick unprotected until the next thing raises the process. Rare — it
  needs a background process start with a fast Watch already scheduled — and not a crash,
  only a tick Android may freeze before it finishes.
