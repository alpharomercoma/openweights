cmake_minimum_required(VERSION 3.22)

project(openweights_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

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
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(openweights_llama
    PRIVATE
    llama
    ggml
    ${ANDROID_LOG_LIB}
)
