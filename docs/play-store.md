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
| `INTERNET` | Hugging Face search and model downloads | Never prompted; normal permission |
| `RECORD_AUDIO` | Dictation, through the on-device recogniser only | First time the mic is tapped |

`POST_NOTIFICATIONS` was declared and never used. Removed. `android.hardware.microphone`
is declared `required="false"`, because `RECORD_AUDIO` otherwise makes Play hide the app
from every device without a microphone, and dictation is one optional way to enter text.

There is no foreground service and no `FOREGROUND_SERVICE` permission, which keeps the app
out of the Play Console's foreground service declaration flow entirely.

## Data safety form

An earlier draft of this document said "data collected: none". That is wrong and would
have been a false declaration. Play counts data as collected when it is transmitted off the
device **at all**, including when it is only processed in flight and never stored. The app
transmits to Hugging Face on the user's behalf, so the form has to say so.

What actually leaves the device, and only when the user searches or downloads:

| Leaves the device | When | Declared as |
|---|---|---|
| Search terms typed into Discover | On search | App activity, or search history |
| Repository and file identifiers | On open and download | App activity |
| The Hugging Face access token, if the user set one | Every Hub request, as an `Authorization` header | Credentials |
| Standard request metadata, including IP | Every Hub request | Handled by the recipient |

What never leaves the device: chats, prompts, model replies, attachments, usage totals and
content reports. There is no analytics SDK, no crash reporter, no account and no backend of
ours.

**Hosts contacted.** `huggingface.co`, and the delivery hosts it redirects downloads to.
The listing and the privacy policy should say "Hugging Face and its content delivery
network", not a single hostname, because a download follows a redirect off the API host.

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

1. Create the upload key and enrol in Play App Signing. Never commit it.
2. Store listing: title, short and full description, feature graphic, phone screenshots.
3. Privacy policy at a public URL. The content is the data safety section above.
4. Content rating questionnaire.
5. Generative AI content declaration, per the section above.
6. Internal testing track, then read the pre-launch report. It runs the app on real
   devices and is the cheapest way to find a crash on hardware we do not own.
7. Decide the launch countries and whether an age rating gate is needed.

## Known gaps a reviewer would be right to raise

- **Downloads do not survive leaving the app.** `ModelsViewModel.download` runs in
  `viewModelScope`, so a multi-gigabyte download dies when the process does. This is not a
  policy violation, it is a product defect, and the fix is WorkManager with a `dataSync`
  foreground service, which brings its own Play declaration.
- **No baseline profile**, so first-run startup and first scroll are slower than they need
  to be.
- **No crash reporting**, by choice. A crash on a device we do not own is invisible to us
  unless a user opens an issue. The pre-launch report partly covers this.
