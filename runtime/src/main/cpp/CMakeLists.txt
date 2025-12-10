cmake_minimum_required(VERSION 3.22.1)
project(waterui_android)

# Build the JNI library
add_library(waterui_android SHARED waterui_jni.cpp)

target_include_directories(
    waterui_android
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../ffi
)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

target_link_options(
    waterui_android
    PRIVATE
    "-Wl,--allow-shlib-undefined"
    "-Wl,--unresolved-symbols=ignore-all"
)

target_link_libraries(
    waterui_android
    PRIVATE
    ${LOG_LIB}
    ${ANDROID_LIB}
)

# Note: SWIG integration is available but disabled for now.
# To enable SWIG-generated bindings, uncomment the following:
#
# find_package(SWIG REQUIRED)
# include(UseSWIG)
# set(CMAKE_SWIG_FLAGS -package dev.waterui.android.runtime)
# set_property(SOURCE waterui.i PROPERTY CPLUSPLUS ON)
# swig_add_library(waterui_swig TYPE SHARED LANGUAGE java
#     OUTPUT_DIR ${CMAKE_CURRENT_BINARY_DIR}/java
#     SOURCES waterui.i)
