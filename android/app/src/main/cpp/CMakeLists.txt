# Build native OCR library (libNative.so) using Paddle Lite + OpenCV.
# Paddle Lite header/so live directly in the repo;
# OpenCV is downloaded by Gradle task and extracted to app/opencv/opencv4.1.0/.

cmake_minimum_required(VERSION 3.10.2)
project(Native CXX)

# Paddle Lite (header committed to repo, so bundled in jniLibs)
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/paddle_lite_include")

# OpenCV 4.1 包里 cmake 依赖 5 层 PATH 上探,必须解压到 app/opencv/opencv4.1.0/<abi>/cmake
set(OpenCV_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../opencv/opencv4.1.0/${ANDROID_ABI}/cmake")
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
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libpaddle_light_api_shared.so")

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(Native
    paddle_light_api_shared
    ${OpenCV_LIBS}
    ${jnigraphics-lib}
    ${log-lib})
