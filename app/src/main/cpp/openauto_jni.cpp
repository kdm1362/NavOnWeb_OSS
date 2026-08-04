/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <jni.h>

#include <string>

#include "port/ProjectionTypes.hpp"

namespace {

constexpr bool runtime_linked = OPENAUTO_RUNTIME_LINKED != 0;

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_pebble_tecomheadunit_openauto_NativeOpenAutoBridge_nativePortStatus(
    JNIEnv* environment,
    jobject /* receiver */) {
    const std::string status = runtime_linked
        ? "OpenAuto native runtime linked"
        : "JNI boundary ready; AASDK runtime intentionally not linked";
    return environment->NewStringUTF(status.c_str());
}
