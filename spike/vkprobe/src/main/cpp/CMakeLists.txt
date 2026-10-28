cmake_minimum_required(VERSION 3.22.1)

project(vkprobe LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ADRENOTOOLS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libadrenotools")

add_subdirectory("${ADRENOTOOLS_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/adrenotools_build")

add_library(vkprobe SHARED vkprobe.cpp)

target_include_directories(vkprobe PRIVATE
    "${ADRENOTOOLS_DIR}/include"
)

target_link_options(vkprobe PRIVATE
    "-Wl,-z,max-page-size=16384"
)

target_link_libraries(vkprobe PRIVATE
    adrenotools
    android
    log
)
