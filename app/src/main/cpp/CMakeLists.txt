# MeetingTranscriber llama.cpp JNI build
#
# 编译前准备:
#   cd app/src/main/cpp
#   git clone https://github.com/ggerganov/llama.cpp.git
#   cd llama.cpp && git checkout <latest-release-tag>
#
cmake_minimum_required(VERSION 3.22.1)
project("llama_jni")

# === 添加 llama.cpp 库 ===
# 独立构建模式：不编译示例和测试，只编译核心库
set(LLAMA_BUILD_COMMON ON)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_CURL OFF)

add_subdirectory(llama.cpp)

# === 查找 Android log 库 ===
find_library(log-lib log)

# === 构建 JNI 共享库 ===
add_library(llama_jni SHARED llama_jni.cpp)

# 链接 llama 库 + Android log
target_link_libraries(llama_jni
    llama
    common
    ${log-lib}
)
