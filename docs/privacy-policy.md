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
are switched on when the app is first installed, and the assistant can use either one without
asking first: it decides when to search and composes the query itself, out of the
conversation, which means what it sends can contain anything you have said or attached, and
it decides which page to fetch. You can turn either off at any time in the Tools tab, where
both are listed under a heading that says they leave the device; the app works without them
and everything else stays local. What each call sent is not hidden afterwards either — every
tool call is a row in the reply that used it, naming the tool and what it was given.

Two narrower situations still ask you before anything runs, in every mode except one
described below. The first is a page telling the assistant where to go next: once something
the assistant read this turn could have been written by someone other than you, a page it
fetches on that page's say-so, or a search it runs with a query that page could have
steered, is held for your approval, because otherwise a page could talk the assistant into
reading its own follow-up address and calling that a fetch you asked for. The second is your
own data: once something private has been read in a turn, from a shared file or otherwise,
anything that would carry data off the device in that same turn is held for your approval
too, regardless of which tool it is.

**Files you share.** If you give the app access to a folder, its contents are read on the
device and are never uploaded on their own. They can leave only through `web_search` or
`fetch_url`, and only if you approve it: once the assistant has read a file during a turn,
any search or page fetch in that same turn asks you first, for the reason above. Answering
yes is you choosing to send it.

**The one exception, and you have to type it.** Sending `/yolo` puts the conversation in a
mode where nothing is put to you at all, including that. It is off unless you turn it on, it
is named in the line under the model's name for as long as it is on, and it is gone the next
time the app starts. In that mode, a file the assistant has read can leave with a search or a
fetch without a prompt, and a page the assistant has read can choose the address of the next
fetch. Nothing else changes: tools you have switched off stay off, and every call is still a
row in the reply that names it.

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
