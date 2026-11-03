#!/bin/sh
# Build and run the native tests without CMake (works in Termux with clang/g++ installed).
set -e
cd "$(dirname "$0")"
CXX=${CXX:-g++}
$CXX -std=c++17 -O2 -g -Wall -Wextra -Werror -fsanitize=address,undefined -fno-sanitize-recover=undefined \
  -Iinclude tests/test_main.cpp -pthread -o /tmp/senzafx_tests
/tmp/senzafx_tests
