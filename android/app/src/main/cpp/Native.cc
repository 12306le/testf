#include "Native.h"
#include "pipeline.h"
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#define LOG_TAG "OcrNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::string jstring_to_cpp_string(JNIEnv *env, jstring s) {
  if (s == nullptr) return "";
  const char *c = env->GetStringUTFChars(s, nullptr);
  std::string r(c);
  env->ReleaseStringUTFChars(s, c);
  return r;
}

JNIEXPORT jlong JNICALL
Java_com_autoclicker_app_OcrNative_nativeInit(
    JNIEnv *env, jclass thiz,
    jstring jDetModelPath, jstring jClsModelPath, jstring jRecModelPath,
    jstring jConfigPath, jstring jLabelPath, jint cpuThreadNum, jstring jCPUPowerMode) {
  std::string det = jstring_to_cpp_string(env, jDetModelPath);
  std::string cls = jstring_to_cpp_string(env, jClsModelPath);
  std::string rec = jstring_to_cpp_string(env, jRecModelPath);
  std::string cfg = jstring_to_cpp_string(env, jConfigPath);
  std::string lbl = jstring_to_cpp_string(env, jLabelPath);
  std::string pwr = jstring_to_cpp_string(env, jCPUPowerMode);
  return reinterpret_cast<jlong>(
      new Pipeline(det, cls, rec, pwr, cpuThreadNum, cfg, lbl));
}

JNIEXPORT jboolean JNICALL
Java_com_autoclicker_app_OcrNative_nativeRelease(JNIEnv *env, jclass thiz, jlong ctx) {
  if (ctx == 0) return JNI_FALSE;
  delete reinterpret_cast<Pipeline *>(ctx);
  return JNI_TRUE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_autoclicker_app_OcrNative_nativeRunBitmap(
    JNIEnv *env, jclass thiz, jlong ctx, jobject bitmap) {
  if (ctx == 0 || bitmap == nullptr) return nullptr;
  Pipeline *pipe = reinterpret_cast<Pipeline *>(ctx);

  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
    LOGE("getInfo failed");
    return nullptr;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    LOGE("bitmap not RGBA_8888: %d", info.format);
    return nullptr;
  }
  void *pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
    LOGE("lockPixels failed");
    return nullptr;
  }
  cv::Mat rgba(info.height, info.width, CV_8UC4, pixels, info.stride);
  cv::Mat rgbaCopy = rgba.clone();
  AndroidBitmap_unlockPixels(env, bitmap);

  auto lines = pipe->ProcessMat(rgbaCopy);

  // Java 端类:com.autoclicker.app.OcrNative$Result(String text, float score, int[] box)
  jclass cls = env->FindClass("com/autoclicker/app/OcrNative$Result");
  if (cls == nullptr) { LOGE("Result class not found"); return nullptr; }
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;F[I)V");

  jobjectArray arr = env->NewObjectArray((jsize)lines.size(), cls, nullptr);
  for (size_t i = 0; i < lines.size(); i++) {
    const auto &l = lines[i];
    jstring jtext = env->NewStringUTF(l.text.c_str());
    jintArray jbox = env->NewIntArray(8);
    jint tmp[8];
    for (int k = 0; k < 4 && k < (int)l.box.size(); k++) {
      tmp[k * 2] = l.box[k].size() > 0 ? l.box[k][0] : 0;
      tmp[k * 2 + 1] = l.box[k].size() > 1 ? l.box[k][1] : 0;
    }
    env->SetIntArrayRegion(jbox, 0, 8, tmp);
    jobject item = env->NewObject(cls, ctor, jtext, (jfloat)l.score, jbox);
    env->SetObjectArrayElement(arr, (jsize)i, item);
    env->DeleteLocalRef(item);
    env->DeleteLocalRef(jtext);
    env->DeleteLocalRef(jbox);
  }
  env->DeleteLocalRef(cls);
  return arr;
}
