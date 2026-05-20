# Stub native library — exists ONLY to trigger AGP to package
# libc++_shared.so for every configured ABI.
#
# Background: DJL's libdjl_tokenizer.so (Phase 3 RAG embeddings) is
# dynamically linked against the C++ STL. Its AAR ships the .so but
# not the STL. TFLite and MediaPipe both statically link, so the STL
# is not present in the APK by default. Without it, dlopen() of
# libdjl_tokenizer.so fails with:
#   library "libc++_shared.so" not found
#
# The mere existence of a CMake-built SHARED library here causes AGP
# to bundle libc++_shared.so alongside it for each ABI — at which
# point libdjl_tokenizer.so resolves cleanly.

cmake_minimum_required(VERSION 3.22)
project(cxxstl_bundler LANGUAGES CXX)
add_library(cxxstl_bundler SHARED stub.cpp)
