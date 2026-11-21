#include <jni.h>
#include <sys/stat.h>
#include <cerrno>
#include <cstring>

// One bounded JNI batch, no heap allocation or C++ runtime. Real UTF-8 paths.
extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_fastsearch_android_NativeDiscovery_stamps(JNIEnv* env, jclass, jobjectArray paths) {
    const jsize count = env->GetArrayLength(paths);
    if (count > 128) return nullptr;
    jlong values[128];
    for (jsize i = 0; i < count; ++i) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(paths, i));
        if (!bytes) return nullptr;
        const jsize length = env->GetArrayLength(bytes);
        char path[4096];
        values[i] = -EINVAL;
        if (length > 0 && length < static_cast<jsize>(sizeof(path))) {
            env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(path));
            if (env->ExceptionCheck()) { env->DeleteLocalRef(bytes); return nullptr; }
            path[length] = 0;
            if (path[0] == '/' && !std::memchr(path, 0, length)) {
                struct stat info;
                if (lstat(path, &info) != 0) values[i] = -errno;
                else if (S_ISDIR(info.st_mode))
                    values[i] = static_cast<jlong>(info.st_mtim.tv_sec) * 1000 + info.st_mtim.tv_nsec / 1000000;
                else values[i] = -ENOTDIR;
            }
        }
        env->DeleteLocalRef(bytes);
    }
    jlongArray result = env->NewLongArray(count);
    if (result) env->SetLongArrayRegion(result, 0, count, values);
    return result;
}
