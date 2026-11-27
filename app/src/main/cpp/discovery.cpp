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

// Automatic Root refresh only: pin every directory component without following symbolic links.
#include <fcntl.h>
#include <limits.h>
#include <unistd.h>

static bool utf8_path(JNIEnv* env, jbyteArray bytes, char (&value)[4096]) {
    if (!bytes) return false;
    const jsize n = env->GetArrayLength(bytes);
    if (n >= static_cast<jsize>(sizeof(value))) return false;
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte*>(value));
    if (env->ExceptionCheck() || std::memchr(value, 0, n)) return false;
    value[n] = 0;
    return true;
}

static bool direct_name(const char* value) {
    const size_t size = std::strlen(value);
    return size && size <= NAME_MAX && std::strcmp(value, ".") && std::strcmp(value, "..") && !std::strchr(value, '/');
}

static int pin_from(int root, char* path) {
    if ((root < 0 && path[0] != '/') || (root >= 0 && path[0] == '/')) return -EINVAL;
    int fd = root < 0 ? open("/", O_PATH | O_DIRECTORY | O_CLOEXEC) : fcntl(root, F_DUPFD_CLOEXEC, 0);
    if (fd < 0) return -errno;
    if (root < 0) ++path;
    while (*path) {
        char* slash = std::strchr(path, '/');
        if (slash) *slash = 0;
        if (!direct_name(path) || (slash && !slash[1])) { close(fd); return -EINVAL; }
        int child = openat(fd, path, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        const int saved = errno;
        close(fd);
        if (child < 0) return -saved;
        fd = child;
        if (!slash) break;
        path = slash + 1;
    }
    return fd;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_fastsearch_android_NativeDiscovery_pinDirectory(JNIEnv* env, jclass, jint root, jbyteArray path) {
    char value[4096];
    return utf8_path(env, path, value) ? pin_from(root, value) : -EINVAL;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_fastsearch_android_NativeDiscovery_statRelative(JNIEnv* env, jclass, jint root, jbyteArray path) {
    char value[4096];
    jlong result[4] = {-EINVAL, 0, 0, 0}; // status, mode, size, mtime ms
    if (root >= 0 && utf8_path(env, path, value) && value[0] != '/') {
        struct stat info;
        if (!value[0]) {
            result[0] = fstat(root, &info) ? -errno : 0;
        } else {
            char* last = std::strrchr(value, '/');
            const char* name = value;
            int parent = root;
            if (last) {
                *last = 0;
                name = last + 1;
                parent = pin_from(root, value);
            }
            // If an ancestor vanished or became a link, retain prior entries; it is not
            // evidence that the leaf itself disappeared from its original directory.
            if (parent < 0) result[0] = -EACCES;
            else if (direct_name(name)) result[0] = fstatat(parent, name, &info, AT_SYMLINK_NOFOLLOW) ? -errno : 0;
            if (last && parent >= 0) close(parent);
        }
        if (!result[0]) {
            result[1] = info.st_mode;
            result[2] = info.st_size;
            result[3] = static_cast<jlong>(info.st_mtim.tv_sec) * 1000 + info.st_mtim.tv_nsec / 1000000;
        }
    }
    jlongArray out = env->NewLongArray(4);
    if (out) env->SetLongArrayRegion(out, 0, 4, result);
    return out;
}
