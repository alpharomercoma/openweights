# Why a picture takes fifty seconds, and the one number that changes it

A user asked why an image turn takes so much longer than a text one, and whether an image
token slider is the fix. The answer to the second question is no, and the reason is worth
writing down, because the control the industry names is the one that makes this model
family slower.

Everything here is measured on a Qualcomm Device Cloud SM8650 (Snapdragon 8 Gen 3, Android
14) with `LFM2.5-VL-3B-Q4_0` and its Q8_0 projector, through `ImageTokenBenchmark`. The
probe is a 1080 by 2400 phone screenshot carrying text at four sizes: a 72pt heading, a
number in a coloured circle, a 34pt table, and a 24pt line at the bottom. Every arm is
asked to read all four, greedily, and is scored on which ones came back.

## Where the time actually goes

A picture does not arrive as text. The projector resizes it, cuts it into patches, runs a
vision transformer over them, and hands the language model a block of embeddings. That
block is then prefilled like any other prompt and sits in the KV cache for the rest of the
conversation. So one number governs the whole chain: how many embeddings there are.

Two things decide that number, and only one of them is a setting anywhere.

## The token budget, which is the obvious control and the wrong one

libmtmd takes `image_min_tokens` and `image_max_tokens`. Setting them looks exactly like
the control this app wants. Swept at the size the app actually sends, longest edge 1024:

| image tokens | prompt tokens | prefill | total | read |
| ---: | ---: | ---: | ---: | --- |
| automatic | 280 | 8.5 s | **9.1 s** | all but the 24pt line |
| 16 | 575 | 20.0 s | 20.8 s | everything |
| 32 | 589 | 21.2 s | 22.0 s | everything |
| 64 | 620 | 26.2 s | 27.1 s | everything |
| 128 | 677 | 32.8 s | 33.7 s | everything |
| 256 | 280 | 13.1 s | 13.9 s | all but the 24pt line |
| 512 | 594 | 35.9 s | 36.9 s | everything |

Asking for **fewer** tokens made the turn nearly four times slower, and the prompt more
than twice as long. That is not noise and it is not a bug in the plumbing.

LFM2's preprocessor tiles. `mtmd_image_preprocessor_lfm2::should_tile` compares the
picture's own pixel count against `image_max_pixels * 2`, and `image_max_pixels` is exactly
`image_max_tokens * patch_size² * n_merge²`. Under that threshold the picture is resized
once and encoded once. Over it, the picture is cut into a grid of 512-pixel tiles, up to
ten of them, and **every tile is a flat 256 tokens** whatever the budget says. So lowering
the budget lowers the threshold, which turns tiling on, which multiplies the cost. The
budget then applies only to the thumbnail that rides along with the tiles, which is the 16,
32, 64, 128 column climbing by roughly the amount asked for.

This is also why the picture is read *better* at a lower budget: tiles preserve detail the
single resized view threw away. The control works, in the sense that it trades speed for
quality. It runs backwards from its own name.

## The size of the picture, which is the control that works

Same model, same question, projector limits left alone, only the picture resized:

| longest edge | pixels | prompt tokens | prefill | total | read |
| ---: | ---: | ---: | ---: | ---: | --- |
| 384 | 66 k | 122 | 4.2 s | **5.8 s** | all but the 24pt line |
| 512 | 118 k | 162 | 6.1 s | 7.2 s | all but the 24pt line |
| 768 | 266 k | 280 | 13.5 s | 14.8 s | **everything** |
| 1024 | 472 k | 280 | 12.7 s | 13.5 s | all but the 24pt line |
| 1536 | 1.06 M | 2851 | 150.6 s | **151.7 s** | everything |
| as shot, 1080x2400 | 2.59 M | 2851 | 100.4 s | 101.2 s | everything |

Monotonic, and with a cliff in it. Between 384 and 1024 the cost roughly triples and the
answer barely changes. Between 1024 and 1536 it goes up **eleven times**, because that is
where tiling starts. Above the cliff nothing more is bought: 1536 and the full 1080 by 2400
produce the same 2851 tokens, because the tile grid saturates at ten.

Note that 768 and 1024 both land on the same 280 tokens, and the smaller one read the fine
print while the larger did not. Both are resized to the projector's own 256-token ceiling;
768 gets there with less downscaling, so slightly more of the 24pt line survives. It is one
sample and the difference is a single line of text, so it is a reason not to go below 768
rather than a reason to prefer it to 1024.

### The same ladder on the Poco, and the 2048 stop that was missing

The table above was measured on the cloud device, and its 2048 row was never captured. Rerun
end to end on the phone this app is developed against:

| longest edge | prompt tokens | encode+prefill | decode | total | read |
| ---: | ---: | ---: | ---: | ---: | --- |
| 384 | 122 | 3.9 s | 2.2 s | **6.1 s** | heading, circle |
| 512 | 162 | 7.2 s | 1.6 s | 8.8 s | **everything** |
| 768 | 280 | 20.8 s | 0.7 s | 21.6 s | **everything** |
| 1024 | 280 | 20.6 s | 8.6 s | 29.3 s | all but the 24pt line |
| 1536 | 2851 | 264.8 s | 1.4 s | **266.2 s** | everything |
| 2048 | 2337 | 221.3 s | 1.4 s | 222.8 s | everything |

The cliff is confirmed and it is worse here than on the cloud device: 1024 to 1536 is a
factor of nine, and both stops above it cost between three and a half and four and a half
minutes for one picture.

**Above the cliff the cost is not monotone in the size of the picture.** 2048 produces 2337
tokens and 1536 produces 2851, so the larger image is the cheaper one by 43 seconds. This is
not noise and it is not a mistake: LFM2 fits the image onto a tile grid of at most ten
tiles, each a flat 256 tokens, and which grid an image lands on depends on how its aspect
ratio divides rather than on how many pixels it has. A picture can cross into a coarser grid
by getting bigger.

That matters for the slider, because it means the top two stops do not read as "slower but
better". Somebody dragging from 1536 to 2048 gets a longer wait removed rather than added.
Both are far enough over the cliff that the distinction is academic, and both are past the
point where anything more is read, but the doc should not imply an ordering that the
measurement does not have.

**The 24pt line, again.** At 512 the model read all four facts in 8.8 seconds; at 1024 it
took 29.3 and dropped the small print. On the cloud device the same thing happened at 768
against 1024. Two independent runs on two devices now show a smaller image reading *more*
than 1024, which is more than the single sample the note above was hedged against. It is
still one image and one question, and the 1024 answer failed by paraphrasing the code
("Build 5071") rather than by not seeing it, so this is not yet a reason to move the
default. It is a reason for the next image run to be several pictures rather than one, and
to score reading rather than phrasing.

**Below 512 the model invents.** At 384 it did not merely miss the small print; it produced
a plausible, entirely fabricated GUID in its place, and got the percentage wrong. That is
the floor doing its job, and it is a better argument for where the floor is than legibility
is.

## What shipped

**A slider over the size of the picture, not over its tokens.** `Image detail`, in the
hyperparameter sheet, and only for a model that can actually read a picture. Six stops,
which are the six sizes that measured differently: 384, 512, 768, **1024 by default**, 1536,
2048. It is enforced in `AttachmentStore`, on the way in, because the file written there is
the file every later turn sends and nothing downstream can make a picture smaller again.

The default does not change. 1024 was already hard-coded, chosen by argument; it is now
chosen by measurement, and it is the largest size before the cliff. The floor is where a
phone screenshot's body text stops being legible at all. The ceiling is over the cliff on
purpose: somebody photographing a page of small print wants the tiles and should be the one
who decided to wait for them.

`ModelLoadParams.imageTokens` exists, reaches libmtmd, and is left automatic. It is kept
because it is the other half of the trade and the only way to reproduce the first table,
the same way `kvCacheQuantized` is kept and off. `ImageTokenBenchmark` is what exercises it.

## Two things to remember

**A single-image turn is 25 to 30 tok/s of apparent prefill, and that is not the prefill
rate.** The vision transformer's forward pass is inside `prefillMs` and contributes no
tokens, so the reported rate is the blend. On the full-resolution arm it is 28 tok/s against
the several hundred the same model manages on text.

**An image turn re-reads the whole conversation.** Embeddings are never compared against the
KV cache, so `cachedTokens` is zero on any turn with an attachment. The picture's cost is
paid once; the conversation behind it is paid again every time.

## Seven pictures later: the cliff is a line in area, and the setting was on the wrong axis (2026-09-05)

A user attached a 3:4 photograph of a poster, asked what it showed, and waited 213.8
seconds for 3.1k prompt tokens at a 0% cache hit. Everything above was measured on one
picture, a 1080 by 2400 screenshot, and that picture never tiles at a 1024 edge because it
is tall: 461 by 1024 is 472 thousand pixels. `should_tile` compares the picture's area,
rounded to the patch grid, against twice the ceiling's pixels, which for LFM2's 256-token
ceiling is 524,288. A 3:4 photograph at the same 1024 edge is 768 by 1024, 786 thousand,
over the line. So the slider that was measured to sit "just below the cliff" was below it
for screenshots and above it for photographs, and the default did the slow thing for the
commonest picture a phone takes.

Two other facts fell out of reading the preprocessor closely:

- **768 and 1024 were the same stop.** Under the line the projector resizes the picture
  down to its 262,144-pixel ceiling before encoding, so anything the app sent between
  262 thousand and 524 thousand pixels was downscaled again by libmtmd to the same 256
  tokens. The table above shows it (280 tokens at both) without saying why.
- **The tiler decides by rounded area, not by the number the app checks.** A 659 by 795
  picture, 523,905 pixels and under the line by arithmetic, rounds to 672 by 800 on the
  32-pixel grid and tiles. The app's budget has to leave that margin.

### The host sweep

Measured on the Mac through llama-server with the same GGUFs, over seven pictures chosen
for shape and content: a handwritten toga form (3:4 photo), a Starbucks receipt (tall),
a Genshin splash screen (wide, text), an Attack on Titan frame (16:9, no text), a resume
(A4 page), a slide (16:9), and the probe screenshot. Each has questions with facts that
can only come from reading it. Timings are the Mac's and are not the phone's; token
counts and what was read are exact.

| picture | arm | pixels sent | prompt tokens | read |
| --- | --- | ---: | ---: | --- |
| form | model limits, 1024 edge (today) | 849x1024 | 1819 | 3/4 |
| form | model limits, 262k px | 466x562 | 276 | 2/4 |
| form | ceiling 1024, 524k px | 659x795 | **563** | **3/4** |
| form | ceiling 1024, 1.05M px | 932x1125 | 1053 | 2/4 |
| receipt | model limits, 1024 edge | 448x1024 | 272 | 4/5 |
| receipt | model limits, 262k px | 339x774 | 272 | 1/5 |
| receipt | ceiling 1024, 524k px | 479x1095 | **542** | **5/5** |
| receipt | ceiling 1024, 1.05M px | 677x1548 | 1040 | 5/5 |
| probe | model limits, 262k px | 343x763 | 279 | 3/4 |
| probe | ceiling 1024, 524k px | 486x1079 | **559** | **4/4** |
| probe | ceiling 1024, 1.05M px | 687x1526 | 1057 | 4/4 |
| resume | model limits, 1024 edge | 724x1024 | 1817 | 2/2 |
| resume | ceiling 1024, 524k px | 609x861 | 540 | 2/2 |
| resume | ceiling 1024, 1.05M px | 861x1218 | 1015 | 1/2 |
| splash | ceiling 1024, 524k px | 1065x492 | 530 | 2/3 |
| splash | ceiling 1024, 1.05M px | 1507x696 | 1022 | 1/3 |

Three things the table says. **One view at about 512 tokens reads what the tiles read**:
the form's handwriting, the receipt's totals, the probe's 24pt line, the resume, at a
fifth of the tokens and one encode instead of seven. **A view at 1024 tokens is worse than
512 on three of the seven** (form, resume, splash), consistent with asking the encoder for
four times the patches it was trained on; there is no stop there. **The 256-token view
loses the receipt and the small print**, which is the fast stop doing what a fast stop
does.

The encode is where the time goes, measured now rather than inferred: on the Mac's CPU
the tiled form was seven encodes of 5 to 8 seconds each, 47 of a 67-second turn, and the
single 480-token view was one encode of 17.6 seconds in a 30-second prefill.

### What shipped

**The engine raises LFM2's ceiling to 512 tokens** when the projector is LFM2 and the
caller left the token budget automatic (`Session::load`, read from the mmproj metadata).
This moves the tiling line to 1,048,576 rounded pixels and makes the balanced view one
encode whatever the picture's shape. The floor stays the model's. Other projector
families are untouched.

**The setting is a token count, and the store shrinks by area.** `ModelPreferences.imageTokens`
has three stops: fast (256, one 512-pixel view), balanced (512, the default, twice the
pixels), and tiles (the picture sent at 2.5 megapixels so the projector cuts it, up to ten
pieces and a thumbnail). `AttachmentStore` shrinks to the stop's pixels, aspect kept, so the
stop names what the picture costs for every shape. The longest-edge setting is migrated:
below 1024 to fast, 1024 to balanced, above to tiles. Video frames are taken at the fast
stop's size; at the old 1024 edge a 16:9 frame was 590 thousand pixels, over the old line,
so every frame of a clip was tiled.

**An image turn no longer re-reads the conversation, and a follow-up no longer re-encodes
the picture.** The cache record keeps a placeholder per media position and a span saying
which picture, by a hash of its pixels, filled it, the way llama-server's `server_tokens`
does. A follow-up question extends the record and decodes only its own words: on the host,
22 tokens and 687 ms against 1,907 and 61 seconds. When the model's own reply re-tokenizes
differently from how it was decoded, which a hybrid cannot roll back from, the whole
conversation is re-read as text, but the projector's output for every chunk is kept in a
96 MB store keyed by picture and tile, so the re-read decodes embeddings rather than
running the vision transformer: 16 seconds against 61 on the Mac, and the same ratio of
seconds to minutes on the phone. `warm()` leaves a conversation with attachments alone,
since it can only render text and would replace the embeddings with a marker word.

**The projector gets the prefill thread count**, which is the shape of the work.

### What this does not settle

The phone was not reachable for this run, so the numbers above are the Mac's. The ratios
are the claim: one encode instead of seven or eleven, a fifth of the tokens, and no
encode at all on a follow-up. `ImageTokenBenchmark` runs the same ladder on a device and
should be run with the ceiling in place. The hybrid re-tokenization drift that forces the
occasional full text re-read is the text path's problem too and is not addressed here.

### On a device: Snapdragon 8 Gen 3, the same pictures (2026-09-05)

Run through the QDC tunnel on an SM8650 with `ImageReuseOnDeviceTest`, which is the three
claims above written as assertions. All three passed. The numbers, prefill in milliseconds,
with the projector encode time from the engine's own log:

| picture | prompt tokens | prefill | encode | read |
| --- | ---: | ---: | ---: | --- |
| form, fast (256) | 309 | 8.0 s | | 2/4 |
| form, balanced (512) | 519 | 14.0 s | 12.6 s | 2/4 |
| form, old 1024 edge | 519 | 14.9 s | | 2/4 |
| form, tiles | 2062 | **63.8 s** | 6 x 5.7 s + 12.6 s | 2/4 |
| receipt, balanced | 543 | 18.3 s | 14.1 s | 4/4 |
| probe, balanced | 560 | 18.6 s | 14.2 s | 4/4 |
| follow-up about the form | 608 (0 cached) | **4.1 s** | none | |

Read critically, four things.

**The ceiling is what fixed the reported case, not the area rule.** With the tiling line
moved to 1,048,576 pixels, the 849 by 1024 picture the old rule sent is one view too, and
costs the same 519 tokens as the balanced one, because the projector downsizes both to its
ceiling. The area rule still stops the app storing and decoding pixels the projector
discards, and it is what makes the tiles stop reach the line for every shape, but on this
photograph it did not change the tokens. Tiles on this device cost 64 seconds against 14,
which is the ratio the user saw on the Poco at 214 seconds.

**The encode is 90% of the prefill.** 12.6 of 14.0 seconds for the balanced view; each
256-token tile is 5.7 seconds. That is the number every future speed-up has to attack.

**The cache extension did not fire on the device; the embedding store did.** The follow-up
came back with zero cached tokens: the model's reply re-tokenized differently from how it
was decoded, the hybrid could not roll back, and the whole 608-token conversation was
re-read. It took 4.1 seconds instead of 14 because the picture's embeddings came out of the
store rather than the encoder. The extension path was seen working on the host; on this
device, with this model's markdown replies, the drift looks systematic, which is a text-path
problem worth its own measurement.

**Tiles did not read this photograph better.** 2 of 4 at every stop; the handwriting is the
limit, not the pixels. Tiles got the course code and lost a digit of the phone number; the
single views got the number and mangled the course. The receipt and the probe were read
fully at the balanced stop. The tiles stop stays for a page of small print and should be
sold as nothing more.

Not tested here: the app itself. The tunnel moved 80 KB/s this session, so the model was
fetched by the device's own curl and only the engine test APK was installed.

### The re-tokenization drift, and the splice that ends it (2026-09-05, later)

The follow-up on the device found zero cached tokens for a reason that has nothing to do
with pictures. The model writes a reply as tokens; the app stores the reply as text; the
next turn renders the conversation and tokenizes it, and the tokenizer does not cut the
reply where the model did. "\n\n- **" leaves the model as several tokens and comes back as
one. The cache holds the model's cut, the comparison diverges a couple of dozen tokens into
the reply, and a hybrid model, which cannot roll back, re-reads the whole conversation. On
the host this happened on every markdown reply, text or image alike, so every LFM2.5 text
chat with a list in it was paying a full re-prefill per turn.

The fix is to stop re-tokenizing the reply. `Session::remember_reply` keeps each generated
reply as text beside the tokens that produced it, with the character offset of every token,
and `Session::tokenize_prompt` splices those tokens back in wherever the rendered prompt
contains that text, tokenizing only the stretches between. The stretches were tokenized on
their own when the cache was built too: the prompt that produced a reply ended where the
reply began, and what follows a reply begins with a special token. So what the cache holds
and what the prompt says agree by construction. A reply that thought first is matched as
the answer alone as well, since a template that drops thinking from history renders only
that. On the image path libmtmd tokenizes each text stretch itself and wraps it with the
projector's image boundary tokens; those are kept as libmtmd made them and only the stretch
between is spliced.

Measured on the host, three turns each:

| conversation | turn | prompt tokens | cached | prefill |
| --- | --- | ---: | ---: | ---: |
| text, LFM2.5-1.2B, markdown lists | 2 | 19 | 84 | 110 ms |
| text, LFM2.5-1.2B, markdown lists | 3 | 22 | 145 | 122 ms |
| image, LFM2.5-VL-3B, form | 2 | 22 | 597 | 315 ms (was 619 tokens, 5.6 s) |
| image, LFM2.5-VL-3B, form | 3 | 18 | 637 | 273 ms |

The embedding store stays, for the re-reads that remain: a folded conversation, a reply
the app edits before it stores it, a replaced picture.

### A page of small print, which is what the tiles stop is for

An A4 page of 9pt text with seven planted facts (a company name, a fee, a percentage, a
liability cap, a city, a reference code with a word in it, and the code's prefix), drawn
at 300 dpi and shrunk to each stop. Host, ceiling 512:

| stop | pixels sent | prompt tokens | read |
| --- | ---: | ---: | --- |
| fast | 430x609 | 296 | 4/7 |
| balanced | 609x861 | 543 | 6/7 |
| tiles | 1361x1926 | 2086 | **7/7** |

Balanced misread the reference prefix, "HRL" as "IHRI", and got everything else. Tiles got
all seven at four times the tokens. That is the stop's whole case: a page of small print,
one more fact, several times the wait. The handwritten form, where tiles read no better,
is not that case, and the setting's footnote says which is which.

### On the phone the report came from: Poco X8 Pro Max, MediaTek MT6991 (2026-09-05, night)

Both device test classes on the phone whose screenshot started this, over wireless
debugging, with the same model files the app had already downloaded. All five tests pass.
`ImageAttachOnDeviceTest` goes through the app's own door: the attachment store shrinks
the 2.6-megapixel photograph of the form, `TurnRunner` sends it as the transcript would,
and the follow-up goes through the same runner.

| what | prompt tokens | cached | prefill | of which encode |
| --- | ---: | ---: | ---: | ---: |
| app, first turn (form, balanced) | 656 | 0 | 43.8 s | 35.4 s |
| app, follow-up | 22 | 711 | 0.28 s | none |
| engine, first turn (form, balanced) | 519 | 0 | 42.3 s | 36.8 s |
| engine, follow-up | 22 | 582 | 0.26 s | none |

The follow-up is one percent of the first turn: the reply's tokens are spliced back in
and the cache extends past them. The store's three stops land at 261,330, 523,110 and
2,618,994 pixels, each under its budget, which the emulator had shown was not true an hour
earlier.

The ladder, same pictures as the Snapdragon run:

| picture | tokens | prefill | read |
| --- | ---: | ---: | --- |
| form-fast | 309 | 20.2 s | 2/4 |
| form-balanced | 519 | 45.8 s | 2/4 |
| form-edge1024 (old default) | 519 | 49.1 s | 2/4 |
| form-tiles | 2062 | 195.7 s | 3/4 |
| receipt-balanced | 543 | 67.2 s | 4/4 |
| receipt-edge1024 | 481 | 54.7 s | 4/4 |
| probe-balanced | 560 | 64.2 s | 4/4 |
| probe-edge1024 | 498 | 47.8 s | 4/4 |
| page-fast | 297 | 20.1 s | 2/7 |
| page-balanced | 544 | 57.9 s | 6/7 |
| page-tiles | 2087 | 191.5 s | **7/7** |

The tiles verdict from the host holds on the phone: the page of small print is the one
picture where tiles read something balanced did not, at four times the wait. The form
gained one field under tiles this time (3/4) at 196 s, which nobody would take.

**What the emulator run found first, and this run confirmed fixed.** On the emulator both
app tests failed: the store wrote the photograph at its full 2,622,246 pixels at every stop,
and the send tiled it at 2,199 tokens. The store asked the content resolver for the file's
type, and for a `file:` URI, or any provider that answers with a plain octet stream, it got
nothing, filed the picture as a document, and copied it verbatim. That is the 214 s path
through a different door. The store now sniffs the header with the bitmap decoder when no
type is declared, and floors the resized edges instead of rounding them, since rounding left
the fast stop 214 pixels over its budget.

**What this phone says that the Snapdragon did not.** The encoder is slow here: 480 tokens
take 35 to 43 s on the MT6991 against 12.6 s on the 8 Gen 3, and it drifts upward over the
run (the last balanced encode was 59 s, with the battery at 26 percent and the phone warm
from twenty minutes of it). Prefill without the picture is quick; the vision tower is over
80 percent of every first turn. So on this phone the balanced stop turns the reported
214 s into about 44 s, and the follow-ups into a quarter of a second, but a first look at a
picture is still not fast. The next thing to measure is the vision tower itself on
MediaTek: thread count and the i8mm and dotprod paths in the clip graph, and whether the
GPU backend can take the encode. That is a separate investigation and is not started here.
