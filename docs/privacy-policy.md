# OpenWeights privacy policy

Last updated 2026-08-15.

OpenWeights runs language models on your phone. There is no OpenWeights account, no
OpenWeights server, and no analytics or crash reporting of any kind. Nothing in this app
reports back to its developer.

That is the short version and it is true, but it is not the whole story, because the app can
reach the internet on your behalf. This document says exactly when, and what goes.

## What stays on your device

- Your conversations: what you typed, what the model replied, and the files you attached.
- The models you download, and the settings you keep for each one.
- Your usage totals: tokens, speed, and time spent generating.
- Reports you file about a model's output. These are stored in the app and sent nowhere.
- The contents of any folder you share with the app, and anything the assistant reads from
  it.

None of this is uploaded, backed up off the device, or readable by anyone else. Android's
automatic backup and device-to-device transfer are switched off for this app, so your chats
do not travel to a new phone. Uninstalling the app deletes all of it.

## What leaves your device, and when

| What | When | Where it goes |
|---|---|---|
| A search term you type in Discover | When you search for a model | Hugging Face |
| A repository or file name | When you open or download a model | Hugging Face, and its content delivery network |
| Your Hugging Face access token, if you set one | With every Hugging Face request | Hugging Face |
| A search query the assistant composed | Whenever it uses the `web_search` tool | DuckDuckGo |
| A web address the assistant chose, and the request for it | Whenever it uses the `fetch_url` tool | Whichever site the address names |

Every request above also carries the ordinary information any web request carries, including
your IP address, which the receiving service handles under its own policy.

**The two assistant tools deserve a paragraph of their own.** `web_search` and `fetch_url`
are switched on when the app is first installed, but **the first time either would actually
send something, the app asks you and shows you the request**. Nothing reaches the internet on
the assistant's behalf before you have answered that once.

If you say yes, searches go out from then on without asking again. The assistant decides when
to use them and composes the query itself, out of the conversation, which means what it sends
can contain anything you have said or attached. If you say no, that tool is switched off,
where you can see it and turn it back on later. You can change either switch at any time in
the Tools tab; the app works without them and everything else is local.

Fetching a page always asks, every time, because the address is the assistant's choice rather
than yours.

**Files you share.** If you give the app access to a folder, its contents are read on the
device and are never uploaded on their own. They can leave only through the two tools above,
and only if you approve it: once the assistant has read a file during a turn, any search or
page fetch in that same turn asks you first, in every mode. Answering yes is you choosing to
send it.

## Third parties

- **Hugging Face** ([privacy policy](https://huggingface.co/privacy)) receives your model
  searches and downloads, and your access token if you set one.
- **DuckDuckGo** ([privacy policy](https://duckduckgo.com/privacy)) receives the assistant's
  search queries.
- **Any site the assistant is asked to read.** This cannot be listed in advance, because it
  is whatever the assistant found. Requests are restricted to public internet addresses.

Data these services hold as a result of your requests is subject to their policies, not this
one.

## Your Hugging Face token

Setting a token is optional; public repositories work without one. If you set one it is
encrypted with a key held in the Android Keystore, is attached only to requests to Hugging
Face, is never written to a log, and is deleted when you remove it or uninstall the app.

## Permissions

- **Internet.** For everything in the table above.
- **Notifications.** To tell you a reply has finished and to show download progress. Asked
  for the first time you send a message or start a download. Declining costs you nothing but
  the notifications.
- **Microphone.** For dictation, and only when you tap the microphone. Speech is transcribed
  by the recogniser on your device; the app requests on-device recognition and does not use
  the online kind. No audio is stored or sent by this app.
- **Foreground service.** So a model download keeps running when you leave the app.

The app asks for no storage permission. Folders and files reach it only through Android's own
picker, one you chose at a time, and access can be revoked in system settings without
uninstalling.

## Children

OpenWeights is not directed at children. It runs models published by third parties, whose
output is not controlled by this app.

## Deleting your data

Delete a conversation to remove it and the files attached to it. Delete a model to remove its
weights. Uninstall the app to remove everything, including your token. There is nothing held
elsewhere for us to delete, because there is nowhere else.

## Changes

If this policy changes, the date at the top changes with it, and a version that changes what
leaves the device will say so in the app's release notes.

## Contact

Open an issue at https://github.com/alpharomercoma/openweights.
