cmake_minimum_required(VERSION 3.22.1)
project(waterui_android)

if (NOT WATERUI_FFI_LIB_DIR)
    set(WATERUI_FFI_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs" CACHE PATH "Directory containing libwaterui_ffi.so")
endif()

add_library(
        waterui_android
        SHARED
        waterui_jni.cpp
)

set(FFI_IMPORT "${WATERUI_FFI_LIB_DIR}/${ANDROID_ABI}/libwaterui_ffi.so")

add_library(waterui_ffi SHARED IMPORTED)
set_target_properties(
        waterui_ffi PROPERTIES
        IMPORTED_LOCATION "${FFI_IMPORT}"
        IMPORTED_NO_SONAME TRUE
)

target_include_directories(
        waterui_android
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../ffi
)

find_library(LOG_LIB log)

target_link_libraries(
        waterui_android
        PRIVATE
        waterui_ffi
        ${LOG_LIB}
)
