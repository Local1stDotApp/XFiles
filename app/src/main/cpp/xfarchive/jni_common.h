/* Helpers shared by the JNI entry points. */
#ifndef XF_JNI_COMMON_H
#define XF_JNI_COMMON_H

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* JNI names for app.local1st.files.core.archive.NativeArchive. */
#define XF_JNI(name) JNICALL Java_app_local1st_files_core_archive_NativeArchive_##name

/* Turns UTF-8 bytes into a Java String, replacing malformed sequences rather than aborting. */
static inline jstring xf_utf8_string(JNIEnv *env, const char *s) {
    jsize n = (jsize)strlen(s);
    jbyteArray bytes;
    jclass string_class;
    jmethodID ctor;
    jstring charset, out;
    bytes = (*env)->NewByteArray(env, n);
    if (bytes == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, bytes, 0, n, (const jbyte *)s);
    string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL) return NULL;
    ctor = (*env)->GetMethodID(env, string_class, "<init>", "([BLjava/lang/String;)V");
    if (ctor == NULL) return NULL;
    charset = (*env)->NewStringUTF(env, "UTF-8");
    if (charset == NULL) return NULL;
    out = (jstring)(*env)->NewObject(env, string_class, ctor, bytes, charset);
    (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, charset);
    (*env)->DeleteLocalRef(env, string_class);
    return out;
}

static inline void xf_throw_io(JNIEnv *env, const char *message) {
    jclass io_class;
    jmethodID ctor;
    jstring text;
    jobject error;
    if ((*env)->ExceptionCheck(env)) return;
    io_class = (*env)->FindClass(env, "java/io/IOException");
    if (io_class == NULL) return;
    text = xf_utf8_string(env, message != NULL && message[0] != '\0' ? message : "Archive error");
    if (text == NULL) return;
    ctor = (*env)->GetMethodID(env, io_class, "<init>", "(Ljava/lang/String;)V");
    if (ctor == NULL) return;
    error = (*env)->NewObject(env, io_class, ctor, text);
    if (error != NULL) (*env)->Throw(env, (jthrowable)error);
}

/* A NUL-terminated copy of a Java byte[] (a UTF-8 path), or NULL with an exception pending. */
static inline char *xf_dup_bytes(JNIEnv *env, jbyteArray bytes) {
    jsize n;
    char *s;
    if (bytes == NULL) {
        xf_throw_io(env, "Missing path");
        return NULL;
    }
    n = (*env)->GetArrayLength(env, bytes);
    s = malloc((size_t)n + 1);
    if (s == NULL) {
        xf_throw_io(env, "Out of memory");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *)s);
    s[n] = '\0';
    return s;
}

static inline jbyteArray xf_bytes(JNIEnv *env, const void *data, size_t len) {
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out != NULL && len > 0) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)data);
    }
    return out;
}

/* Checks a Java (off, len) window of a byte[]; throws and returns 0 when it is out of range. */
static inline int xf_check_range(JNIEnv *env, jbyteArray buf, jint off, jint len) {
    jsize size = (*env)->GetArrayLength(env, buf);
    if (off < 0 || len < 0 || len > size - off) {
        jclass c = (*env)->FindClass(env, "java/lang/IndexOutOfBoundsException");
        if (c != NULL) (*env)->ThrowNew(env, c, "Buffer range");
        return 0;
    }
    return 1;
}

#endif /* XF_JNI_COMMON_H */
