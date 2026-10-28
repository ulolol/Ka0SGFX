cmake_minimum_required(VERSION 3.22.1)
project(kaosgfx_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ADRENOTOOLS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libadrenotools")
add_subdirectory("${ADRENOTOOLS_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/adrenotools_build")

find_package(bytehook REQUIRED CONFIG)
find_package(shadowhook REQUIRED CONFIG)

add_library(kaosgfx_bridge SHARED
    bridge.cpp
    hooks.cpp
    plt_hook.cpp
)

target_include_directories(kaosgfx_bridge PRIVATE
    "${ADRENOTOOLS_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

target_link_options(kaosgfx_bridge PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(kaosgfx_bridge PRIVATE
    adrenotools
    bytehook::bytehook
    shadowhook::shadowhook
    android
    log
    dl
)
