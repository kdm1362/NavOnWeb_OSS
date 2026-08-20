cmake_minimum_required(VERSION 3.22.1)
project(openauto_android LANGUAGES CXX)

option(OPENAUTO_RUNTIME_LINKED "AASDK/OpenAuto runtime has passed the integration gate" OFF)

add_library(
    openauto_android
    SHARED
    openauto_jni.cpp
)

target_compile_features(openauto_android PRIVATE cxx_std_20)
target_compile_definitions(
    openauto_android
    PRIVATE OPENAUTO_RUNTIME_LINKED=$<BOOL:${OPENAUTO_RUNTIME_LINKED}>
)
target_include_directories(openauto_android PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(android_log_library log REQUIRED)
target_link_libraries(openauto_android PRIVATE ${android_log_library})
