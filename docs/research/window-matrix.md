# Exported window matrix

Each cell is one export of one model at one window on one phone, over the same 90 prompts (30 GSM8K, 30 IFEval, 30 BFCL), greedy, thinking off, loaded at the file's own window. Prefill ms is the runtime's prefill time for the whole prompt (the engine-side part of time to first token, not a first-token timestamp); ms/token is decode time over generated tokens, which differ per cell because the replies differ, so it is a per-cell figure and not a paired speed comparison. Capped is how many completed replies ran to the token cap (640, or 384 for BFCL) and so never finished; a set whose replies are mostly capped is cap-censored and its grade says little. RSS is the test process's resident set right after the model loaded and at the end of the run.

## LFM2.5-1.2B-Instruct-8da4w

| Window | File | Phone | GSM8K | IFEval | BFCL | Capped | Prefill ms | ms/token | Prefill tok/s | Decode tok/s | Ran at | RSS after load MB | RSS at end MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2k | 796 MB | D9400 | 17/30 | 15/30 | 26/30 | 10/90 | 360 | 25.9 | 254 | 38.7 | 2048 | - | 1186 |
| 4k | 798 MB | D9400 | 16/30 | 17/30 | 26/30 | 12/90 | 363 | 26.7 | 253 | 37.5 | 4096 | 1099 | 1218 |
| 4k | 798 MB | 8 Elite | 13/30 | 18/30 | 28/30 | 11/90 | 374 | 17.7 | 227 | 56.4 | 4096 | 1104 | 1207 |
| 4k | 798 MB | Tensor G5 | 13/30 | 18/30 | 27/30 | 14/90 | 817 | 64.2 | 129 | 15.6 | 4096 | 1113 | 1225 |
| 4k | 798 MB | Exynos 2400 | 13/30 | 17/30 | 26/30 | 11/90 | 460 | 34.0 | 195 | 29.4 | 4096 | 1126 | 1213 |
| 8k | 802 MB | D9400 | 17/30 | 18/30 | 26/30 | 11/90 | 353 | 26.1 | 259 | 38.3 | 8192 | 1226 | 1333 |
| 16k | 810 MB | D9400 | 13/30 | 20/30 | 27/30 | 12/90 | 354 | 25.9 | 262 | 38.6 | 16384 | - | 993 |
| 16k | 810 MB | D9400 repeat | 14/30 | 16/30 | 28/30 | 12/90 | 368 | 26.2 | 259 | 38.2 | 16384 | 1416 | 1528 |
| 32k | 827 MB | D9400 | 12/30 | 18/30 | 27/30 | 16/90 | 366 | 26.3 | 257 | 38.0 | 32768 | - | 1903 |
| 32k | 827 MB | D9400 repeat | 12/30 | 15/30 | 27/30 | 12/90 | 392 | 28.8 | 237 | 34.8 | 32768 | 1798 | 1901 |
| 32k | 827 MB | 8 Elite | 16/30 | 13/30 | 26/30 | 11/90 | 324 | 16.3 | 261 | 61.2 | 32768 | - | 1880 |
| 32k | 827 MB | Tensor G5 | 16/30 | 18/30 | 27/30 | 13/90 | 862 | 79.5 | 98 | 12.6 | 32768 | - | 1896 |
| 32k | 827 MB | Exynos 2400 | 14/30 | 20/30 | 25/30 | 11/90 | 462 | 37.1 | 184 | 27.0 | 32768 | - | 1883 |

Run-to-run control: the same file run twice on the D9400, replies byte-identical:

| Window | identical replies |
|---|---|
| 16k | raw 13/90, calls 23/30, content 20/90 |
| 32k | raw 15/90, calls 25/30, content 18/90 |

Replies identical to the 32k export, per window (raw stream and shown content over prompts both completed; parsed tool calls over the BFCL prompts):

| Phone | 2k | 4k | 8k | 16k |
|---|---|---|---|---|
| D9400 | raw 12/90, calls 25/30, content 17/90 | raw 17/90, calls 26/30, content 18/90 | raw 18/90, calls 28/30, content 19/90 | raw 12/90, calls 24/30, content 16/90 |
| D9400 repeat | - | - | - | raw 14/90, calls 25/30, content 17/90 |
| 8 Elite | - | raw 13/90, calls 26/30, content 15/90 | - | - |
| Tensor G5 | - | raw 10/90, calls 24/30, content 14/90 | - | - |
| Exynos 2400 | - | raw 16/90, calls 28/30, content 16/90 | - | - |

## LFM2.5-2.6B-8da4w

| Window | File | Phone | GSM8K | IFEval | BFCL | Capped | Prefill ms | ms/token | Prefill tok/s | Decode tok/s | Ran at | RSS after load MB | RSS at end MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2k | 1783 MB | D9400 | 15/30 | 4/30 | 24/30 | 42/90 | 882 | 60.3 | 108 | 16.6 | 2048 | 1979 | 1958 |
| 4k | 1786 MB | 8 Elite | 13/30 | 5/30 | 22/30 | 44/90 | 1197 | 55.4 | 72 | 18.0 | 4096 | 2058 | 2194 |
| 4k | 1786 MB | Tensor G5 | 17/30 | 6/30 | 24/30 | 38/90 | 1066 | 92.2 | 88 | 10.8 | 4096 | 2054 | 2221 |
| 4k | 1786 MB | Exynos 2400 | 10/30 | 5/30 | 23/30 | 46/90 | 1142 | 83.5 | 81 | 12.0 | 4096 | 2136 | 2189 |
| 32k | 1815 MB | D9400 | 13/30 | 5/30 | 24/30 | 45/90 | 882 | 59.7 | 109 | 16.8 | 32768 | 2937 | 2185 |
| 32k | 1815 MB | 8 Elite | 13/30 | 5/30 | 25/30 | 42/90 | 1096 | 49.7 | 81 | 20.1 | 32768 | 2950 | 3082 |
| 32k | 1815 MB | Tensor G5 | 19/30 | 4/30 | 25/30 | 37/90 | 968 | 81.1 | 101 | 12.3 | 32768 | 2962 | 3120 |
| 32k | 1815 MB | Exynos 2400 | 18/30 | 3/30 | 25/30 | 39/90 | 1085 | 84.5 | 80 | 11.8 | 32768 | 3032 | 3084 |

Replies identical to the 32k export, per window (raw stream and shown content over prompts both completed; parsed tool calls over the BFCL prompts):

| Phone | 2k | 4k |
|---|---|---|
| D9400 | raw 0/90, calls 24/30, content 29/90 | - |
| 8 Elite | - | raw 0/90, calls 25/30, content 29/90 |
| Tensor G5 | - | raw 0/90, calls 27/30, content 30/90 |
| Exynos 2400 | - | raw 0/90, calls 25/30, content 30/90 |

## Qwen3-1.7B-8da4w

| Window | File | Phone | GSM8K | IFEval | BFCL | Capped | Prefill ms | ms/token | Prefill tok/s | Decode tok/s | Ran at | RSS after load MB | RSS at end MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2k | 1285 MB | D9400 | 20/30 | 15/30 | 24/30 | 8/90 | 532 | 45.1 | 174 | 22.2 | 2048 | 1758 | 1833 |
| 2k | 1285 MB | D9400 repeat | 17/30 | 18/30 | 24/30 | 7/90 | 532 | 45.7 | 174 | 21.9 | 2048 | 1766 | 1900 |
| 2k | 1285 MB | 8 Elite | 18/30 | 13/30 | 25/30 | 12/90 | 673 | 41.9 | 141 | 23.9 | 2048 | 1771 | 1887 |
| 2k | 1285 MB | Tensor G5 | 23/30 | 13/30 | 24/30 | 8/90 | 814 | 89.3 | 121 | 11.2 | 2048 | 1772 | 1892 |
| 2k | 1285 MB | Exynos 2400 | 17/30 | 12/30 | 23/30 | 10/90 | 734 | 65.9 | 125 | 15.2 | 2048 | 1838 | 1886 |
| 32k | 1348 MB | D9400 | INCOMPLETE: process ended after 6/90 prompts | | | | | | | | 32768 | 6188 | 5426 (last observed) |
| 32k | 1348 MB | 8 Elite | INCOMPLETE: process ended after 12/90 prompts | | | | | | | | 32768 | 6127 | 4836 (last observed) |
| 32k | 1348 MB | Tensor G5 | 16/30 | 14/30 | 25/30 | 8/90 | 844 | 94.0 | 139 | 10.6 | 32768 | 8499 | 8602 |
| 32k | 1348 MB | Exynos 2400 | INCOMPLETE: process ended after 7/90 prompts | | | | | | | | 32768 | 6396 | 6380 (last observed) |

Run-to-run control: the same file run twice on the D9400, replies byte-identical:

| Window | identical replies |
|---|---|
| 2k | raw 31/90, calls 30/30, content 31/90 |

Replies identical to the 32k export, per window (raw stream and shown content over prompts both completed; parsed tool calls over the BFCL prompts):

| Phone | 2k |
|---|---|
| D9400 | raw 0/6, calls 0/0, content 0/6 |
| 8 Elite | raw 0/12, calls 0/0, content 0/12 |
| Tensor G5 | raw 29/90, calls 27/30, content 31/90 |
| Exynos 2400 | raw 0/7, calls 0/0, content 0/7 |

## Fixed-prompt speed probe (Poco, Dimensity 9400)

ExecuTorchOnDeviceTest#throughputReport through matrix/run.sh: 929-token prompt, 160-token reply cap, three turns from a cold cache per load, phone cooled below 42 C and awake before each load, twelve files in two interleaved passes. The second table pairs each turn with the same turn of the model's smallest window in the same pass.

| File | Turns | Prefill tok/s (median, min to max) | Decode tok/s (median, min to max) | Prefill ms, 929 tokens | Resident after load MB |
|---|---|---|---|---|---|
| LFM2.5-1.2B-Instruct-8da4w-2k | 6 | 332 (288 to 344) | 40.2 (33.7 to 41.0) | 2804 | 1050 |
| LFM2.5-1.2B-Instruct-8da4w-4k | 6 | 340 (330 to 344) | 40.4 (36.9 to 41.0) | 2738 | 1098 |
| LFM2.5-1.2B-Instruct-8da4w-8k | 6 | 343 (327 to 345) | 40.8 (40.0 to 41.2) | 2712 | 1194 |
| LFM2.5-1.2B-Instruct-8da4w-16k | 6 | 342 (326 to 345) | 40.5 (39.8 to 41.2) | 2718 | 1386 |
| LFM2.5-1.2B-Instruct-8da4w-32k | 6 | 343 (329 to 346) | 40.5 (39.9 to 40.9) | 2712 | 1768 |
| LFM2.5-2.6B-8da4w-2k | 6 | 149 (142 to 153) | 19.0 (16.7 to 19.5) | 6254 | 1984 |
| LFM2.5-2.6B-8da4w-4k | 6 | 148 (135 to 153) | 18.1 (16.5 to 19.5) | 6264 | 2046 |
| LFM2.5-2.6B-8da4w-8k | 6 | 149 (140 to 153) | 18.2 (16.7 to 19.5) | 6234 | 2172 |
| LFM2.5-2.6B-8da4w-16k | 6 | 149 (138 to 153) | 18.4 (16.6 to 19.5) | 6226 | 2427 |
| LFM2.5-2.6B-8da4w-32k | 6 | 149 (139 to 152) | 18.1 (16.2 to 19.5) | 6240 | 2936 |
| Qwen3-1.7B-8da4w-2k | 6 | 200 (193 to 206) | 18.0 (16.1 to 18.2) | 4680 | 1758 |
| Qwen3-1.7B-8da4w-32k | 6 | 184 (162 to 203) | 16.6 (15.1 to 17.6) | 5064 | 6153 |

| File | Prefill vs smallest window (median ratio, min to max) | Decode vs smallest window | Paired turns |
|---|---|---|---|
| LFM2.5-1.2B-Instruct-8da4w-2k | 1.000 (1.000 to 1.000) | 1.000 (1.000 to 1.000) | 6 |
| LFM2.5-1.2B-Instruct-8da4w-4k | 1.007 (0.987 to 1.181) | 0.999 (0.927 to 1.190) | 6 |
| LFM2.5-1.2B-Instruct-8da4w-8k | 1.003 (0.995 to 1.198) | 1.011 (0.995 to 1.187) | 6 |
| LFM2.5-1.2B-Instruct-8da4w-16k | 1.008 (0.992 to 1.190) | 1.009 (0.988 to 1.187) | 6 |
| LFM2.5-1.2B-Instruct-8da4w-32k | 1.013 (0.999 to 1.202) | 1.006 (0.993 to 1.184) | 6 |
| LFM2.5-2.6B-8da4w-2k | 1.000 (1.000 to 1.000) | 1.000 (1.000 to 1.000) | 6 |
| LFM2.5-2.6B-8da4w-4k | 0.995 (0.947 to 1.002) | 0.985 (0.946 to 1.005) | 6 |
| LFM2.5-2.6B-8da4w-8k | 0.997 (0.983 to 1.005) | 0.991 (0.943 to 1.000) | 6 |
| LFM2.5-2.6B-8da4w-16k | 1.003 (0.971 to 1.005) | 0.997 (0.938 to 1.005) | 6 |
| LFM2.5-2.6B-8da4w-32k | 0.997 (0.975 to 1.003) | 0.976 (0.943 to 1.005) | 6 |
| Qwen3-1.7B-8da4w-2k | 1.000 (1.000 to 1.000) | 1.000 (1.000 to 1.000) | 6 |
| Qwen3-1.7B-8da4w-32k | 0.941 (0.793 to 0.982) | 0.953 (0.835 to 1.012) | 6 |
