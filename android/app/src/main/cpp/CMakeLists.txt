# Build native OCR library (libNative.so) using Paddle Lite + OpenCV.
# third-party libs live under app/third_party/{paddle_lite,opencv4.1.0},
# downloaded at build time by the fetchNativeDeps Gradle task.

cmake_minimum_required(VERSION 3.10.2)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party")
set(PaddleLite_DIR "${THIRD_PARTY}/paddle_lite")
set(OpenCV_DIR "${THIRD_PARTY}/opencv4.1.0/${ANDROID_ABI}/cmake")

include_directories(${PaddleLite_DIR}/cxx/include)
find_package(OpenCV REQUIRED)
include_directories(${OpenCV_INCLUDE_DIRS})

set(CMAKE_CXX_FLAGS
    "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -fvisibility=hidden -fdata-sections -ffunction-sections -fexceptions -frtti")
set(CMAKE_SHARED_LINKER_FLAGS
    "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections")

add_library(Native SHARED
    Native.cc pipeline.cc utils.cc cls_process.cc rec_process.cc
    det_process.cc db_post_process.cc clipper.cpp)

add_library(paddle_light_api_shared SHARED IMPORTED)
set_target_properties(paddle_light_api_shared PROPERTIES
    IMPORTED_LOCATION
    ${PaddleLite_DIR}/cxx/libs/${ANDROID_ABI}/libpaddle_light_api_shared.so)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(Native
    paddle_light_api_shared
    ${OpenCV_LIBS}
    ${jnigraphics-lib}
    ${log-lib})
