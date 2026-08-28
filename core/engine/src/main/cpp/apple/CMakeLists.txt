cmake_minimum_required(VERSION 3.22)

project(openweights_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The Apple counterpart to ../CMakeLists.txt, the Android build. Same source
# (engine_session.cpp), same llama.cpp submodule, different backend story: Android dlopen's
# whichever prebuilt CPU-variant .so matches the device and probes for an OpenCL driver at
# runtime, because nothing is ever extracted from the APK to scan a directory for. Apple has
# neither problem -- Metal and one CPU backend link straight into the binary -- which is
# exactly what engine_session.cpp's own `#ifdef __ANDROID__` guard around that dance now
# assumes.
#
# The options below are build-xcframework.sh's own COMMON_CMAKE_ARGS, with one deliberate
# difference: LLAMA_BUILD_COMMON is ON here, not OFF. That script builds the stock
# llama.xcframework, which upstream's own llama.swiftui example links against and which
# never needed chat.h -- its LibLlama.swift starts every prompt from zero and does no
# template rendering of its own. engine_session.cpp is not that: it renders each model's own
# chat template, parses tool calls back out of the reply, and builds tool-call grammars, all
# of which live in common/chat.h. Building without llama-common here would compile, then
# fail to link the moment engine_session.cpp is added to this target.
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_METAL ON CACHE BOOL "" FORCE)
set(GGML_METAL_EMBED_LIBRARY ON CACHE BOOL "" FORCE)
set(GGML_BLAS_DEFAULT ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp
    EXCLUDE_FROM_ALL
)

add_library(openweights_engine STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/../engine_session.cpp
)

target_include_directories(openweights_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/ggml/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/common
    ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/tools/mtmd
    ${CMAKE_CURRENT_SOURCE_DIR}/../llama.cpp/vendor
)

# See ../CMakeLists.txt's own comment on llama-common: same reasoning, same dependency.
target_link_libraries(openweights_engine
    PUBLIC
    llama
    ggml
    llama-common
    mtmd
)
