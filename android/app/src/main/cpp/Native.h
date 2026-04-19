// JNI bridge for OCR pipeline. Exports are prefixed with
// Java_com_autoclicker_app_OcrNative_*, so the Kotlin side declares the
// matching 'external' functions inside object OcrNative.

#ifndef AUTOCLICKER_NATIVE_H
#define AUTOCLICKER_NATIVE_H

#include <jni.h>
#include <string>
#include "utils.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL
Java_com_autoclicker_app_OcrNative_nativeInit(
    JNIEnv *env, jclass thiz,
    jstring jDetModelPath, jstring jClsModelPath, jstring jRecModelPath,
    jstring jConfigPath, jstring jLabelPath, jint cpuThreadNum, jstring jCPUPowerMode);

JNIEXPORT jboolean JNICALL
Java_com_autoclicker_app_OcrNative_nativeRelease(JNIEnv *env, jclass thiz, jlong ctx);

JNIEXPORT jobjectArray JNICALL
Java_com_autoclicker_app_OcrNative_nativeRunBitmap(
    JNIEnv *env, jclass thiz, jlong ctx, jobject bitmap);

#ifdef __cplusplus
}
#endif
#endif
