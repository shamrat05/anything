#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif

/* Linux PATH_MAX includes the trailing NUL. Never allocate more than 8 KiB total. */
#define FILEOPS_PATH_CAP 4096

static int copy_path(JNIEnv *env, jbyteArray encoded, char **result) {
    if (encoded == NULL) return EINVAL;
    jsize length = (*env)->GetArrayLength(env, encoded);
    if (length < 1) return EINVAL;
    if (length >= FILEOPS_PATH_CAP) return ENAMETOOLONG;
    char *path = malloc((size_t) length + 1);
    if (path == NULL) return ENOMEM;
    /* Byte arrays preserve real UTF-8, including characters outside the BMP. */
    (*env)->GetByteArrayRegion(env, encoded, 0, length, (jbyte *) path);
    if ((*env)->ExceptionCheck(env)) { free(path); return EINVAL; }
    if (path[0] != '/' || memchr(path, '\0', (size_t) length) != NULL) {
        free(path);
        return EINVAL;
    }
    path[length] = '\0';
    *result = path;
    return 0;
}

JNIEXPORT jint JNICALL
Java_dev_fastsearch_android_NativeMoves_renameNoReplace(JNIEnv *env, jclass type,
                                                       jbyteArray from, jbyteArray to) {
    (void) type;
    char *source = NULL;
    char *destination = NULL;
    int error = copy_path(env, from, &source);
    if (error == 0) error = copy_path(env, to, &destination);
    if (error == 0) {
#if defined(__NR_renameat2)
        if (syscall(__NR_renameat2, AT_FDCWD, source, AT_FDCWD, destination,
                    RENAME_NOREPLACE) != 0) error = errno;
#else
        error = ENOSYS;
#endif
    }
    free(destination);
    free(source);
    return error;
}
