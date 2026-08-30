# GPU backends on Android

**Measured 2026-08-30 on a Poco X8 Pro Max — MediaTek MT6991 (Dimensity 9400),
Mali-G925-Immortalis MC11, 12 GB — against `LFM2.5-1.2B-Instruct-Q4_K_M`,
llama.cpp `b2e5e9b`, 6 threads.**

## The app runs on the CPU, and on this phone it has no choice

Asked what it can use, the engine answers with one device:

```
using CPU backend libggml-cpu-android_armv9.0_1.so (score 55)
ggml_opencl: unsupported GPU 'Mali-G925-Immortalis MC11 r0p1'.
ggml_opencl: drop unsupported device 'Mali-G925-Immortalis MC11 r0p1'.
device id=CPU kind=CPU mem=11816562688 :: CPU
```

The OpenCL driver is present (`/vendor/lib64/libOpenCL.so`) and works. What
refuses is ggml: `ggml-opencl.cpp` assigns a GPU family by string match on
Adreno, Qualcomm or Intel and warns "unsupported GPU" for everything else. The
backend is Adreno-tuned by design, so on a Mali handset it registers and then
drops the only GPU there is.

That is not dead code — it is simply not for this phone. On a Snapdragon
(Adreno 830) the same build offloads and prefill runs 4.8–5.5× the tuned CPU
path.

## OpenCL is not the only door, so Vulkan was built and measured

`ggml-vulkan` is vendored and works on Mali. Same tool, same model, same
arguments:

| backend | prefill `pp128` t/s | decode `tg64` t/s |
| --- | ---: | ---: |
| **CPU** (KleidiAI, armv9.0) | **131.12 ± 0.97** | 36.31 ± 0.42 |
| Vulkan (Mali-G925, `-ngl 99`) | 15.38 ± 0.19 | **39.22 ± 0.68** |

Decode is 8% faster on the GPU. Prefill is **8.5× slower**. Prefill is not a
detail — it is every token of the prompt, the conversation so far and any tool
output, on every turn. For a 500-token prompt and a 200-token reply:

- CPU: 3.8 s prefill + 5.5 s decode = **9.3 s**
- Vulkan: 32.5 s prefill + 5.1 s decode = **37.6 s**

So Vulkan stays off, and the reason is now a number rather than a recollection:
it is four times worse per turn, and the 8% it wins back on decode cannot pay
for it. It was also not clean — the run logs
`Could not open module param file '/sys/module/mali_kbase/parameters/large_page_conf'`
and a `FORTIFY: pthread_mutex_lock called on a destroyed mutex` at teardown.

Note the shape is the opposite of Adreno's, where prefill is the win and decode
the loss. "GPU is faster" is not a property of GPUs; it is a property of a
particular driver, kernel set and memory architecture, and it has to be
measured per family.

## What is not available at all

MediaTek's APU/NeuroPilot NPU has no ggml backend, and `ggml-hexagon` is
Qualcomm-only. On a Dimensity the choice is CPU or Vulkan, and the measurement
above settles it.

## Reproducing

The Vulkan build needs three things the NDK does not supply on its own, which
is most of why this had not been measured before:

- **SPIRV-Headers**, including `spirv/unified1/` — clone
  `KhronosGroup/SPIRV-Headers` and put its `include` on the compiler's path.
  Installing it is not enough: the install tree drops `unified1`, which is the
  one `ggml-vulkan.cpp` includes.
- **Vulkan-Hpp** — the NDK ships `vulkan/vulkan.h` but not `vulkan/vulkan.hpp`.
  Clone `KhronosGroup/Vulkan-Headers`.
- **glslc on `PATH`** — it lives at `$NDK/shader-tools/<host>/glslc`. The
  `vulkan-shaders-gen` host tool is built through ExternalProject and does not
  inherit `CMAKE_MAKE_PROGRAM`, so ninja has to be on `PATH` as well.

```sh
cmake -S core/engine/src/main/cpp/llama.cpp -B vk-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_NATIVE=OFF \
  -DGGML_CPU_KLEIDIAI=ON -DGGML_VULKAN=ON \
  -DVulkan_GLSLC_EXECUTABLE=$NDK/shader-tools/darwin-x86_64/glslc \
  -DCMAKE_CXX_FLAGS="-I$PWD/vulkan-headers/include -I$PWD/spirv-headers/include"
```

Measure each backend from its **own** build directory. With
`libggml-vulkan.so` alongside it, `-ngl 0` does not give a clean CPU reading —
it still reports the Vulkan backend and returned 2.96 t/s decode, which is
neither backend's real number.
