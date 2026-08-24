# The bridge between this app and MNN, and nothing else.
#
# MNN is vendored beside this file rather than consumed as an artifact. The published
# `com.alibaba.android:mnn` was last updated in 2021 at version 0.0.8 against an upstream now
# on 3.6, and contains nothing about diffusion or speech, so there is no artifact to consume.
#
# Two flags here cost a build cycle each to find and are worth stating rather than
# rediscovering:
#
#   * MNN_SEP_BUILD must be ON. With it OFF, MNNOpenCV becomes an OBJECT library whose
#     POST_BUILD step is illegal and CMake refuses to generate at all. The price is four
#     shared prerequisites rather than one.
#   * MNN_IMGCODECS must be set explicitly. The diffusion target is guarded by
#     `MNN_BUILD_DIFFUSION AND MNN_BUILD_OPENCV AND MNN_IMGCODECS`, and turning on diffusion
#     turns on OpenCV but leaves IMGCODECS at its default of OFF, so the guard fails
#     silently: everything configures, everything builds, and libdiffusion.so is simply
#     never produced. Nothing in the output says why.
cmake_minimum_required(VERSION 3.22.1)
project(openweights_generation)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MNN_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD ON CACHE BOOL "" FORCE)
set(MNN_BUILD_DIFFUSION ON CACHE BOOL "" FORCE)
set(MNN_BUILD_OPENCV ON CACHE BOOL "" FORCE)
set(MNN_IMGCODECS ON CACHE BOOL "" FORCE)
set(MNN_OPENCL ON CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT ON CACHE BOOL "" FORCE)

# Off on purpose. Every one of these builds a host tool or a test binary that an APK has no
# use for, and MNN_BUILD_LLM is only needed by the Sana and Wan paths, which take their text
# embedding from a language model. Stable Diffusion carries its own tokenizer.
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM OFF CACHE BOOL "" FORCE)

# Vendored where it was pinned, referenced from here. Ten thousand files of somebody else's
# source do not move house because this project grew a module.
set(MNN_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../generation/src/androidMain/cpp/MNN)
# Before the subdirectory, so it reaches the diffusion sources.
#
# MNN's own diffusion CMakeLists asks for `${CMAKE_SOURCE_DIR}/tools/cv/include`, and
# CMAKE_SOURCE_DIR is the top of whatever build included it. Consumed as a subdirectory that
# is this file's folder, so the path it computes points into this module and `cv/cv.hpp` is
# not found. Adding the real one here rather than patching vendored source.
include_directories(${MNN_ROOT}/tools/cv/include)

add_subdirectory(${MNN_ROOT} ${CMAKE_BINARY_DIR}/mnn)

# Cancelling a generation means throwing out of MNN's progress callback, which is the only
# interruption point its denoising loop has. MNN builds with `-fno-exceptions`, which does
# not stop the throw but does stop the compiler emitting the cleanup that runs destructors
# while the stack unwinds: every VARP held by `unet` and `run` would leak on every stop.
#
# Only this target needs it, and only this target gets it. The callback is invoked from
# `unet`'s own loop, so the frames unwound are `stable_diffusion.cpp`'s and nothing deeper:
# libMNN and libMNN_Express are not on the stack at a step boundary and keep their own flags.
if (TARGET diffusion)
    target_compile_options(diffusion PRIVATE -fexceptions -frtti)
endif()

add_library(openweights_generation SHARED generation_jni.cpp)

target_include_directories(openweights_generation PRIVATE
    ${MNN_ROOT}/include
    ${MNN_ROOT}/transformers/diffusion/engine/include
)

find_library(log-lib log)
target_link_libraries(openweights_generation MNN MNN_Express diffusion ${log-lib})
