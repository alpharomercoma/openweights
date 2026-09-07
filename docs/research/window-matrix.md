# Exported window matrix

Each cell is one export of one model at one window on one phone, over the same 90 prompts (30 GSM8K, 30 IFEval, 30 BFCL), greedy, thinking off, loaded at the file's own window. Prefill ms is the runtime's prefill time for the whole prompt (the engine-side part of time to first token, not a first-token timestamp); ms/token is decode time over generated tokens, which differ per cell because the replies differ, so it is a per-cell figure and not a paired speed comparison.

## LFM2.5-1.2B-Instruct-8da4w

| Window | File | Phone | GSM8K | IFEval | BFCL | Prefill ms | ms/token | Prefill tok/s | Decode tok/s | Ran at | RSS MB |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2k | 796 MB | D9400 | 17/30 | 15/30 | 26/30 | 360 | 25.9 | 254 | 38.7 | 2048 | 1186 |
| 4k | 798 MB | D9400 | 16/30 | 17/30 | 26/30 | 363 | 26.7 | 253 | 37.5 | 4096 | 1218 |
| 4k | 798 MB | 8 Elite | 13/30 | 18/30 | 28/30 | 374 | 17.7 | 227 | 56.4 | 4096 | 1207 |
| 4k | 798 MB | Tensor G5 | 13/30 | 18/30 | 27/30 | 817 | 64.2 | 129 | 15.6 | 4096 | 1225 |
| 4k | 798 MB | Exynos 2400 | 13/30 | 17/30 | 26/30 | 460 | 34.0 | 195 | 29.4 | 4096 | 1213 |
| 8k | 802 MB | D9400 | 17/30 | 18/30 | 26/30 | 353 | 26.1 | 259 | 38.3 | 8192 | 1333 |
| 16k | 810 MB | D9400 | 13/30 | 20/30 | 27/30 | 354 | 25.9 | 262 | 38.6 | 16384 | 993 |
| 16k | 810 MB | D9400 repeat | 14/30 | 16/30 | 28/30 | 368 | 26.2 | 259 | 38.2 | 16384 | 1528 |
| 32k | 827 MB | D9400 | 12/30 | 18/30 | 27/30 | 366 | 26.3 | 257 | 38.0 | 32768 | 1903 |
| 32k | 827 MB | D9400 repeat | 12/30 | 15/30 | 27/30 | 392 | 28.8 | 237 | 34.8 | 32768 | 1901 |
| 32k | 827 MB | 8 Elite | 16/30 | 13/30 | 26/30 | 324 | 16.3 | 261 | 61.2 | 32768 | 1880 |
| 32k | 827 MB | Tensor G5 | 16/30 | 18/30 | 27/30 | 862 | 79.5 | 98 | 12.6 | 32768 | 1896 |
| 32k | 827 MB | Exynos 2400 | 14/30 | 20/30 | 25/30 | 462 | 37.1 | 184 | 27.0 | 32768 | 1883 |

Run-to-run control: the same file run twice on the D9400, replies byte-identical:

| Window | raw / calls / content identical, of shared |
|---|---|
| 16k | 13 / 83 / 20 of 90 |
| 32k | 15 / 85 / 18 of 90 |

Replies identical to the 32k export, per window (raw stream / parsed tool calls / shown content, of prompts both completed):

| Phone | 2k | 4k | 8k | 16k |
|---|---|---|---|---|
| D9400 | 12 / 85 / 17 of 90 | 17 / 86 / 18 of 90 | 18 / 88 / 19 of 90 | 12 / 84 / 16 of 90 |
| D9400 repeat | - | - | - | 14 / 85 / 17 of 90 |
| 8 Elite | - | 13 / 86 / 15 of 90 | - | - |
| Tensor G5 | - | 10 / 84 / 14 of 90 | - | - |
| Exynos 2400 | - | 16 / 88 / 16 of 90 | - | - |

## LFM2.5-2.6B-8da4w

| Window | File | Phone | GSM8K | IFEval | BFCL | Prefill ms | ms/token | Prefill tok/s | Decode tok/s | Ran at | RSS MB |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2k | 1783 MB | D9400 | 15/30 | 4/30 | 24/30 | 882 | 60.3 | 108 | 16.6 | 2048 | 1958 |
| 4k | 1786 MB | 8 Elite | 13/30 | 5/30 | 22/30 | 1197 | 55.4 | 72 | 18.0 | 4096 | 2194 |
| 4k | 1786 MB | Tensor G5 | 17/30 | 6/30 | 24/30 | 1066 | 92.2 | 88 | 10.8 | 4096 | 2221 |
| 4k | 1786 MB | Exynos 2400 | 10/30 | 5/30 | 23/30 | 1142 | 83.5 | 81 | 12.0 | 4096 | 2189 |
| 32k | 1815 MB | D9400 | 13/30 | 5/30 | 24/30 | 882 | 59.7 | 109 | 16.8 | 32768 | 2185 |
| 32k | 1815 MB | 8 Elite | 13/30 | 5/30 | 25/30 | 1096 | 49.7 | 81 | 20.1 | 32768 | 3082 |
| 32k | 1815 MB | Tensor G5 | 19/30 | 4/30 | 25/30 | 968 | 81.1 | 101 | 12.3 | 32768 | 3120 |
| 32k | 1815 MB | Exynos 2400 | 18/30 | 3/30 | 25/30 | 1085 | 84.5 | 80 | 11.8 | 32768 | 3084 |

Replies identical to the 32k export, per window (raw stream / parsed tool calls / shown content, of prompts both completed):

| Phone | 2k | 4k |
|---|---|---|
| D9400 | 0 / 84 / 29 of 90 | - |
| 8 Elite | - | 0 / 85 / 29 of 90 |
| Tensor G5 | - | 0 / 87 / 30 of 90 |
| Exynos 2400 | - | 0 / 85 / 30 of 90 |

## Qwen3-1.7B-8da4w

| Window | File | Phone | GSM8K | IFEval | BFCL | Prefill ms | ms/token | Prefill tok/s | Decode tok/s | Ran at | RSS MB |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2k | 1285 MB | D9400 | 20/30 | 15/30 | 24/30 | 532 | 45.1 | 174 | 22.2 | 2048 | 1833 |
| 2k | 1285 MB | D9400 repeat | 17/30 | 18/30 | 24/30 | 532 | 45.7 | 174 | 21.9 | 2048 | 1900 |
| 2k | 1285 MB | 8 Elite | 18/30 | 13/30 | 25/30 | 673 | 41.9 | 141 | 23.9 | 2048 | 1887 |
| 2k | 1285 MB | Tensor G5 | 23/30 | 13/30 | 24/30 | 814 | 89.3 | 121 | 11.2 | 2048 | 1892 |
| 2k | 1285 MB | Exynos 2400 | 17/30 | 12/30 | 23/30 | 734 | 65.9 | 125 | 15.2 | 2048 | 1886 |
| 32k | 1348 MB | D9400 | 4/6 | - | - | 539 | 46.3 | 177 | 21.6 | 32768 | 5426 |
| 32k | 1348 MB | 8 Elite | 3/6 | 3/6 | - | 440 | 37.4 | 173 | 26.7 | 32768 | 4836 |
| 32k | 1348 MB | Tensor G5 | 16/30 | 14/30 | 25/30 | 844 | 94.0 | 139 | 10.6 | 32768 | 8602 |
| 32k | 1348 MB | Exynos 2400 | 1/3 | 2/4 | - | 458 | 50.6 | 139 | 19.8 | 32768 | 6380 |

Run-to-run control: the same file run twice on the D9400, replies byte-identical:

| Window | raw / calls / content identical, of shared |
|---|---|
| 2k | 31 / 90 / 31 of 90 |

Replies identical to the 32k export, per window (raw stream / parsed tool calls / shown content, of prompts both completed):

| Phone | 2k |
|---|---|
| D9400 | 0 / 6 / 0 of 6 |
| 8 Elite | 0 / 12 / 0 of 12 |
| Tensor G5 | 29 / 87 / 31 of 90 |
| Exynos 2400 | 0 / 7 / 0 of 7 |
