cmake_minimum_required(VERSION 3.22.1)
project(whispernote)
set(CMAKE_CXX_STANDARD 17)
# Android Studio's Debug configuration otherwise compiles the inference kernels at -O0.
# Apply to C and C++, including all whisper/ggml subdirectories, not just our JNI bridge.
# Keep debug symbols; do not enable unsafe floating-point transformations or host-specific ISA flags.
add_compile_options(-O3)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
add_subdirectory(whisper)
add_library(whispernote SHARED bridge.cpp)
target_link_libraries(whispernote whisper android log)
target_link_options(whispernote PRIVATE "-Wl,-z,max-page-size=16384")
