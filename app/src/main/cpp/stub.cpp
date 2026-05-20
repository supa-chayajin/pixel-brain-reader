// Stub native library. See CMakeLists.txt for the *why*. Short version:
// DJL's libdjl_tokenizer.so dynamically links libc++_shared.so but its AAR
// doesn't bundle the STL. Building any SHARED library here with
// ANDROID_STL=c++_shared makes AGP package libc++_shared.so alongside it,
// which resolves DJL's UnsatisfiedLinkError at process load.
//
// We touch one STL symbol (a std::string) so the linker actually records a
// libc++_shared.so dependency on this .so — an empty translation unit
// compiles down to nothing and AGP skips the STL packaging.

#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_cloud_wafflecommons_pixelbrainreader_data_ai_VectorSearchEngine_00024Companion_stlSentinel(
        JNIEnv *env, jobject /* this */) {
    std::string s = "cxxstl_bundler";
    return env->NewStringUTF(s.c_str());
}
