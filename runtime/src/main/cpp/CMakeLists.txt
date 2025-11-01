cmake_minimum_required(VERSION 3.22.1)
project(waterui_android)

add_library(
        waterui_android
        SHARED
        waterui_jni.cpp
)

target_include_directories(
        waterui_android
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../ffi
)

find_library(LOG_LIB log)

target_link_options(
        waterui_android
        PRIVATE
        "-Wl,--allow-shlib-undefined"
)

target_link_libraries(
        waterui_android
        PRIVATE
        ${LOG_LIB}
)
