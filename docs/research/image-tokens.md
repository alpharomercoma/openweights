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
