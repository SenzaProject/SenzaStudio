cmake_minimum_required(VERSION 3.16)
project(senzafx LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Header-only core, consumed by the HAL effect library (Android.bp / CMake) and by the tests.
add_library(senzafx INTERFACE)
target_include_directories(senzafx INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/include)

enable_testing()
add_executable(senzafx_tests tests/test_main.cpp)
target_link_libraries(senzafx_tests PRIVATE senzafx)
target_compile_options(senzafx_tests PRIVATE -Wall -Wextra -Werror)
find_package(Threads REQUIRED)
target_link_libraries(senzafx_tests PRIVATE Threads::Threads)
add_test(NAME senzafx_tests COMMAND senzafx_tests)
