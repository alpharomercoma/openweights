cmake_minimum_required(VERSION 3.22)

project(openweights_sandbox C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# QuickJS from the pinned submodule.
#
# quickjs-ng builds several things. Exactly one of them is linked here, and which one is
# the whole security argument at the language level: `qjs` is the interpreter, and
# `qjs-libc` is the separate library that supplies readFile, open, exec and the rest. It is
# not disabled by a flag or hidden behind a check, it is simply never linked, so there is no
# path from a script to a file or a socket because the functions do not exist in the binary.
#
# EXCLUDE_FROM_ALL keeps quickjs's own executables and its test suite out of the build.
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/quickjs
    ${CMAKE_CURRENT_BINARY_DIR}/quickjs
    EXCLUDE_FROM_ALL
)

add_library(openweights_sandbox SHARED quickjs_jni.cpp)

find_library(log-lib log)

target_link_libraries(openweights_sandbox PRIVATE qjs ${log-lib})
