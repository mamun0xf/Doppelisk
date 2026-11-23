# Alternative build for contributors who have the Android NDK.
#
# Gradle does not use this file. The app packages the prebuilt library from
# app/src/main/jniLibs/arm64-v8a, which Makefile in this directory produces on machines
# without an NDK; this exists for externalNativeBuild elsewhere.
cmake_minimum_required(VERSION 3.22)
project(doppelisk_shim C)

add_library(doppelisk_shim SHARED doppelisk_shim.c)

set_target_properties(doppelisk_shim PROPERTIES
    OUTPUT_NAME doppelisk_shim
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    C_VISIBILITY_PRESET hidden)

# Same reason as the Makefile: bionic does not export the outlined atomics
# helpers, and this source uses __atomic on plain ints.
target_compile_options(doppelisk_shim PRIVATE -mno-outline-atomics)

target_link_libraries(doppelisk_shim log dl)
