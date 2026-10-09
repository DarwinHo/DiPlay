#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>

static void fail(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, message);
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_transport_PioneerMemoryCopy_nativeCopy(
    JNIEnv *env, jobject self, jint fd, jint heap_size, jint offset, jint count) {
    (void) self;
    if (fd < 0 || heap_size <= 0 || heap_size > 1048576 || offset < 0 ||
        count <= 0 || count > 65536 || offset > heap_size - count) {
        fail(env, "Invalid Pioneer memory bounds"); return NULL;
    }
    int owned = dup(fd);
    if (owned < 0) { fail(env, "Could not duplicate Pioneer memory fd"); return NULL; }
    // Validate the actual backing size before copying, to reject malformed Binder payloads.
    // Android 7 ashmem fstat does not expose region size; ASHMEM_GET_SIZE does.
    int ashmem_size = ioctl(owned, _IO('a', 4));
    struct stat statbuf;
    if (ashmem_size < heap_size &&
        (fstat(owned, &statbuf) != 0 || !S_ISREG(statbuf.st_mode) || statbuf.st_size < heap_size)) {
        close(owned); fail(env, "Pioneer memory fd has an invalid backing size"); return NULL;
    }
    void *base = mmap(NULL, (size_t) heap_size, PROT_READ, MAP_SHARED, owned, 0);
    close(owned);
    if (base == MAP_FAILED) { fail(env, "Could not map Pioneer shared memory"); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, count);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, count, (const jbyte *)base + offset);
    munmap(base, (size_t) heap_size);
    return result;
}
