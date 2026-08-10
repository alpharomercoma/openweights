cmake_minimum_required(VERSION 3.22)

project(openweights_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# OpenCL for the Adreno backend.
#
# The NDK ships no OpenCL, so the headers come from Khronos and the link target is
# Khronos's ICD loader. Only the link target: the loader is deliberately kept out of the
# APK by an exclude in build.gradle.kts, because Qualcomm lists libOpenCL.so in
# /vendor/etc/public.libraries.txt and the driver already on the phone is the one that
# should answer. Packaging our own would shadow it and find no platforms at all, which is
# exactly the failure this comment exists to prevent someone rediscovering.
if (GGML_OPENCL)
    set(OPENCL_ICD_LOADER_HEADERS_DIR
        ${CMAKE_CURRENT_SOURCE_DIR}/OpenCL-Headers CACHE PATH "" FORCE)
    add_subdirectory(
        ${CMAKE_CURRENT_SOURCE_DIR}/OpenCL-ICD-Loader
        ${CMAKE_CURRENT_BINARY_DIR}/OpenCL-ICD-Loader
        EXCLUDE_FROM_ALL
    )
    set(OpenCL_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/OpenCL-Headers CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY $<TARGET_FILE:OpenCL> CACHE FILEPATH "" FORCE)
endif()

# Build llama.cpp from the pinned submodule. Its own CMake handles ggml, backend
# selection, and the runtime CPU-feature dispatch (dotprod / i8mm / SVE / SME via
# KleidiAI) that makes one arm64 binary optimal across devices.
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp
    EXCLUDE_FROM_ALL
)

add_library(openweights_llama SHARED
    llama_jni.cpp
    engine_session.cpp
)

target_include_directories(openweights_llama PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/common
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/tools/mtmd
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/vendor
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(openweights_llama
    PRIVATE
    llama
    ggml
    # llama-common carries llama.cpp's chat-template engine: it renders tool definitions
    # into each model's own tool syntax and parses the calls back out again, which is a
    # large amount of per-model knowledge not worth reimplementing.
    llama-common
    # Multimodal projector runtime: image, audio and video inputs.
    mtmd
    ${ANDROID_LOG_LIB}
)
