# Speculative decoding on a phone

**Measured 2026-08-30 on a Poco X8 Pro Max (MediaTek MT6991, 12 GB), llama.cpp
`b2e5e9b`, CPU backend with `GGML_CPU_ALL_VARIANTS` + KleidiAI, 6 threads,
greedy sampling.** Every number below came off that device; the raw logs are
reproducible with the script at the end.

## The result

Speculative decoding with LiquidAI's official DSpark draft is **1.5× to 3×
slower** than plain decode, against both the canonical target and the QAD one.

Target `LFM2.5-1.2B-Instruct-Q4_K_M` — the draft's own declared base model:

| configuration | generation t/s |
| --- | ---: |
| **baseline, no draft** | **32.8** |
| `--spec-draft-n-max 3 -fa on` | 22.4 |
| `--spec-draft-n-max 7 -fa off` | 14.2 |
| `--spec-draft-n-max 7 -fa on` | 10.8 |

Target `LFM2.5-1.2B-Instruct-QAD-Q4_0`:

| configuration | generation t/s |
| --- | ---: |
| **baseline, no draft** | **36.9** |
| `--spec-draft-n-max 3 -fa on` | 24.7 |
| `--spec-draft-n-max 7 -fa on` | 18.6 |
| `--spec-draft-n-max 9 -fa on` | 13.7 |

Drafting more makes it monotonically worse, which is the shape that gives the
reason away: every extra drafted token costs more to verify than it returns.

## Why

Two measurements, and both have to be true for speculation to pay.

**The draft is rarely right.** llama.cpp's own accounting for the 128-token run:

```
draft acceptance = 0.22419 (76 accepted / 339 generated), mean len = 2.52
#acc rate/pos = (0.620, 0.480, 0.220, 0.140, 0.060)
dur(b,g,a) = 0.002, 2748.497, 0.032 ms
```

Position one is accepted 62% of the time and position five 6%: past about two
tokens the draft is guessing. And generating those drafts took **2.75 s of an
8.27 s reply — a third of the wall clock** — before the target verified
anything.

**A wider batch is not nearly free on this chip.** This is the part that
matters beyond speculation, so it was measured directly:

| batch | aggregate t/s | per-sequence t/s |
| ---: | ---: | ---: |
| 1 | 29.6 | 29.6 |
| 2 | 35.6 | 17.8 |
| 4 | 70.6 | 17.7 |
| 8 | 76.7 | 9.6 |

Eight sequences buy **2.6× the aggregate throughput, not 8×**. A decode that
were purely memory-bandwidth-bound would approach 8×, because the weights
stream once however many tokens ride along. This one does not, so it is
substantially compute-bound, and a batch-*k* verify pass costs real time
proportional to *k*.

Put together: verifying seven drafted tokens costs roughly two to three
single-token steps and returns 2.52 accepted tokens, which is close to
break-even — and then the draft's own third of the clock turns it into a loss.

## What this rules out, and what it does not

It rules out **DSpark and DFlash drafts for LFM2.5 at 1.2B on this class of
device**, which is the question that was asked. Do not re-propose it as a fix
for on-device decode speed without new measurements.

It does not rule out speculative decoding in general. The two measured causes
suggest where the boundary is: a *larger* target (where one target step costs
far more than one draft step) or a *more accurate* draft would both move the
arithmetic. Neither applies to a 1.2 B model on a phone.

It says nothing about batching across users, which works exactly as the table
above shows and is why servers do it. It is simply not available to an app
where one person waits for one reply: the app already batches everything that
can be batched — prompt ingestion runs at `n_batch = n_ubatch = 512`, which is
why prefill is 113 t/s against decode's 29.6 — and a reply's own tokens cannot
be computed in parallel, because each one is the input to the next.

## Reproducing

Build the CLI for the device against the vendored llama.cpp, with the same
flags the app's own native build uses (this matters: a plain `arm64-v8a` build
without `GGML_CPU_ALL_VARIANTS` has no dotprod or i8mm kernels and is roughly
an order of magnitude slower, which would make any result here meaningless):

```sh
cmake -S core/engine/src/main/cpp/llama.cpp -B build-spec -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
  -DGGML_CPU_KLEIDIAI=ON -DLLAMA_CURL=OFF -DLLAMA_BUILD_TOOLS=ON
ninja -C build-spec llama-app
```

Push `build-spec/bin/*.so`, `build-spec/bin/llama` and the NDK's
`libc++_shared.so` to `/data/local/tmp/spec`. **Put the models on
`/data/local/tmp` as well** — read from `/sdcard` as the shell user they go
through the FUSE emulation layer, which is far slower than the app's own access
to the same file and will dominate the measurement.

```sh
./llama cli -m TARGET.gguf -p PROMPT -n 128 -st --temp 0 -t 6 \
  -md DRAFT.gguf --spec-type draft-dspark --spec-draft-n-max 7 -fa on -v
./llama batched-bench -m TARGET.gguf -c 2048 -npp 128 -ntg 64 -npl 1,2,4,8 -t 6
```

`-st` matters: without it `llama cli` stays interactive, and with no stdin it
spins on EOF at 100% CPU, which reads exactly like very slow inference.
