# Where web search comes from

> **Status 2026-09-01:** grown since written. DuckDuckGo is no longer the whole of
> it: the app ships DuckDuckGo, Brave and Yahoo behind per-engine switches, an
> optional search-only proxy, and an optional Context7 documentation source. Bing and
> Google were measured unreachable from phones and removed (see the provider chain in
> `core:tools` and the switches on the Tools screen).

Written 2026-08-10, to answer one question that turned out to be three: can the app run
SearXNG on the phone, would it help if it could, and what should the default be.

The short version is that DuckDuckGo is the whole of it, a self-hosted SearXNG was tried and
removed, and the thing actually worth building is neither of those.

Updated 2026-08-11: the SearXNG field is gone. It worked, and that was never the question.
It was a text box asking for the address of a server the user had to run, on a screen in an
app whose entire promise is that it needs nothing else. Nobody was going to fill it in, and
an option nobody uses is not a feature, it is a thing that has to keep working. What it did
for a person running many queries at once is real and is written down below, so the case for
bringing it back is on the record if that person ever turns up.

## What was measured

Everything below was run on 2026-08-10 against a real phone and a real instance, not read
off a README.

| Approach | Result |
|---|---|
| DuckDuckGo two-step (cookies, then POST with a Referer) | Answers. This is what the `ddgs` Python package does and what the app does |
| DuckDuckGo lite and html endpoints, plain GET | 202, no results |
| DuckDuckGo Instant Answer API | Empty for anything that is not a dictionary word |
| Public SearXNG instances | 403 and 429 |
| Self-hosted SearXNG, JSON on, limiter off | Answers. 20 results for the query that started this |
| Mojeek, Brave, and the rest without a key | Stubs or a signup page |

There is no keyless general web search that is not a scraper. That is the whole finding,
and every option below is a way of arranging around it.

## Why public SearXNG instances fail

Not rate limiting, or not only. The JSON format is off by default, and
[the documentation says what happens then](https://docs.searxng.org/dev/search_api.html):
"Requesting an unset format will return a 403 Forbidden error. Be aware that many public
instances have these formats disabled." Every public instance tried returned 403 or 429.

An instance you run yourself can turn JSON on and the limiter off. That is the difference,
and it is the only difference.

## Why it cannot run on the phone

| | |
|---|---|
| Image | 90 MB, `linux/arm64` |
| Runtime | CPython 3.14.6 |
| Native extensions | `lxml`, `granian` (Rust), `msgspec`, `markupsafe`, `yaml`, `setproctitle` |

Running that on Android means CPython built for Android plus every one of those C and Rust
extensions cross compiled with the NDK. Docker is not a way around it: containers need a
runtime and namespaces that a sandboxed app does not get without root, and an app that
downloaded and executed that stack would not survive Play review.

## Why it would not help even if it could

SearXNG has no index. It is a parser collection and an HTTP client, and it forwards to
Google, Bing, DuckDuckGo and the rest. On the phone, its requests would leave from the
phone, which is exactly where the app's requests leave from now. It would be a 90 MB Python
runtime making the same calls a few hundred lines of Kotlin already make.

Self-hosting does not remove the blocking either. It moves it to a machine you control,
where the address is yours and the limiter is yours to switch off.

## What it is actually worth

Breadth, and failing over. From the instance that answered the query this investigation
started with, out of 250 engine modules and 387 configured engines:

```
duckduckgo   → 10 results
google cse   → 20 results
brave        → too many requests
startpage    → Suspended: CAPTCHA
```

Half of what it tried was blocked. It answered because when two engines failed, two others
did not. That is the product: not a better source, a second and third source.

## The decision

**DuckDuckGo stays the default.** It needs no setup, and the app's promise on its own empty
screen is that nothing leaves the device. "First run Docker on a Mac" is not a default that
can be squared with that sentence.

**A self-hosted SearXNG is not shipped.** It was built, proved, and taken out again. The
instance answers and the app could talk to it, which is why the numbers above are real
measurements rather than estimates, but shipping it meant a settings field asking for a
server address in an app that otherwise asks for nothing. The loopback exemption in the
network security config went with it, so cleartext is off with no exceptions: `fetch_url`
fetches an address the model chose, and an unencrypted request to an unvetted host is the
one place here where somebody on the network gets to change what the model reads.

**searxist is not a fit.** It is [an Android app](https://codeberg.org/Linerly/searxist), a
Material You wrapper around public instances, not a library, not on Maven Central, and
GPL 3.0 against this project's Apache 2.0.

## What to build instead

More providers behind `SearchProvider`, in Kotlin, on the device. The measurement above is
the argument: one CAPTCHA should not mean no answer. That is what SearXNG does that is
worth having, and it does not need Python to do it.

If those providers are ever worth sharing, Maven Central is the JVM's npm and
`io.github.alpharomercoma` verifies through a GitHub account. Worth being clear about what
that would and would not buy: publishing does not raise anyone's rate limit. The value
would be in maintaining the parsers as the HTML underneath them changes, which is a standing
commitment rather than an extraction.

## Verified end to end

For the record, on 2026-08-10, against a self-hosted instance, the question that started
this ("who is Alpha Romer Coma", a person no 2.6B model has memorised):

```
+0.1s   turn withTools=true tools=[web_search, fetch_url] mode=AUTO
+13.7s  pass offered=true calls=1     web_search
+27.4s  pass offered=true calls=1     fetch_url
+62.4s  pass offered=false calls=0    answer
```

Correct, and sourced from the pages it fetched.
