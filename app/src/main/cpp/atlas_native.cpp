/**
 * Project Atlas - Android Virtual Space
 *
 * atlas_native.cpp
 * Native bridge for GameGuardian compatibility and memory operations.
 *
 * This file implements JNI functions declared in NativeHookBridge.kt.
 * It provides low-level hooks for mmap, open/openat, and fork system calls
 * using PLT hooking (via bhook/xhook), as well as utilities for reading
 * /proc filesystem data for virtual process management.
 *
 * Build note: The bhook library should be built separately and linked
 * as a prebuilt static library. See CMakeLists.txt for configuration.
 */

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <dirent.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>
#include <mutex>
#include <string>
#include <vector>
#include <fstream>
#include <sstream>
#include <algorithm>

// ──────────────────────────────────────────────────────────────
//  Logging macros
// ──────────────────────────────────────────────────────────────

#define LOG_TAG "Atlas:Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ──────────────────────────────────────────────────────────────
//  bhook / xhook integration
//
//  NOTE: bhook (by bytedance) should be built as a separate static
//  library and linked here. For now we use stub implementations
//  that log and return success, so the rest of the native bridge
//  can be developed and tested without the hooking library present.
//
//  To integrate bhook:
//    1. Add bhook as a subdirectory or prebuilt in CMakeLists.txt
//    2. #include "bhook.h"
//    3. Replace stub implementations with actual PLT hook calls
// ──────────────────────────────────────────────────────────────

// ──────────────────────────────────────────────────────────────
//  Internal state
// ──────────────────────────────────────────────────────────────

/** Mutex protecting all mutable global state. */
static std::mutex g_mutex;

/** Virtual filesystem root path, set by initNativeBridge. */
static std::string g_virtual_root_path;

/** Whether the native bridge has been initialized. */
static bool g_initialized = false;

/**
 * Marker string written to /proc/{pid}/cmdline for virtual processes.
 * This allows isVirtualProcess() to identify Atlas-managed processes.
 */
static const char* ATLAS_PROC_MARKER = "com.atlas.virtual";

// ──────────────────────────────────────────────────────────────
//  JNI_OnLoad / JNI_OnUnload
// ──────────────────────────────────────────────────────────────

/**
 * Called when the native library is loaded via System.loadLibrary().
 * Registers native methods and performs one-time initialization.
 */
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad: atlas_native library loaded");
    JNIEnv* env = nullptr;

    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: Failed to get JNIEnv");
        return JNI_ERR;
    }

    // Native methods are resolved by name convention (extern "C"),
    // so no explicit RegisterNatives call is needed.

    LOGI("JNI_OnLoad: initialization complete (JNI_VERSION_1_6)");
    return JNI_VERSION_1_6;
}

/**
 * Called when the native library is unloaded (rare on Android,
 * but included for correctness).
 */
JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnUnload: atlas_native library unloading");

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_initialized) {
        g_virtual_root_path.clear();
        g_initialized = false;
        LOGI("JNI_OnUnload: cleaned up native bridge state");
    }
}

// ──────────────────────────────────────────────────────────────
//  Helper: Read entire file to string
// ──────────────────────────────────────────────────────────────

/**
 * Reads the entire contents of a file into a std::string.
 *
 * @param path  Filesystem path to read.
 * @param out   [out] Receives the file contents on success.
 * @return true on success, false on any I/O error.
 */
static bool readFileToString(const std::string& path, std::string& out) {
    std::ifstream ifs(path, std::ios::in | std::ios::binary);
    if (!ifs.is_open()) {
        return false;
    }

    ifs.seekg(0, std::ios::end);
    auto size = ifs.tellg();
    if (size < 0) {
        return false;
    }
    ifs.seekg(0, std::ios::beg);

    out.resize(static_cast<size_t>(size));
    if (!ifs.read(&out[0], size)) {
        return false;
    }

    return true;
}

/**
 * Reads the entire contents of a file into a byte vector.
 *
 * @param path  Filesystem path to read.
 * @param out   [out] Receives the file bytes on success.
 * @return true on success, false on any I/O error.
 */
static bool readFileToBytes(const std::string& path, std::vector<uint8_t>& out) {
    std::ifstream ifs(path, std::ios::in | std::ios::binary);
    if (!ifs.is_open()) {
        return false;
    }

    ifs.seekg(0, std::ios::end);
    auto size = ifs.tellg();
    if (size < 0) {
        return false;
    }
    ifs.seekg(0, std::ios::beg);

    out.resize(static_cast<size_t>(size));
    if (!ifs.read(reinterpret_cast<char*>(out.data()), size)) {
        return false;
    }

    return true;
}

// ──────────────────────────────────────────────────────────────
//  Helper: Check if a PID directory in /proc represents a numeric PID
// ──────────────────────────────────────────────────────────────

/**
 * Returns true if the directory name consists entirely of digits,
 * indicating it is a /proc/{pid} entry.
 */
static bool isNumericDirectory(const char* name) {
    if (name == nullptr || name[0] == '\0') {
        return false;
    }
    for (const char* p = name; *p != '\0'; p++) {
        if (*p < '0' || *p > '9') {
            return false;
        }
    }
    return true;
}

// ──────────────────────────────────────────────────────────────
//  JNI: hookMmap
// ──────────────────────────────────────────────────────────────

/**
 * Hooks the mmap system call via PLT hooking to expose virtual
 * process memory maps.
 *
 * When a virtual app calls mmap, this hook records the mapping
 * so that GameGuardian can discover the memory region through
 * /proc/{pid}/maps.
 *
 * Current implementation: stub that logs and returns 0 (success).
 * Actual PLT hooking requires the bhook library to be linked.
 *
 * @return 0 on success, negative errno on failure.
 */
extern "C" JNIEXPORT jint
Java_com_atlas_virtualspace_core_hook_NativeHookBridge_hookMmap(
        JNIEnv* env, jclass clazz) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_initialized) {
        LOGE("hookMmap: native bridge not initialized");
        return -EPERM;
    }

    LOGI("hookMmap: installing mmap PLT hook (stub)");

    // ── Stub implementation ──
    // In a production build, this would call:
    //   bh_hook_plt("mmap", (void*)hooked_mmap, (void**)&orig_mmap, nullptr);
    //
    // where hooked_mmap records the mapping in a shared data structure
    // that getMemoryMap() can read, and orig_mmap is the original mmap.

    LOGI("hookMmap: mmap hook installed (stub — returns success)");
    return 0;
}

// ──────────────────────────────────────────────────────────────
//  JNI: hookOpen
// ──────────────────────────────────────────────────────────────

/**
 * Hooks the open and openat system calls to redirect /proc/self/
 * reads to a virtualized /proc/ tree that reflects virtual process
 * state.
 *
 * When a virtual app opens /proc/self/maps, /proc/self/status, etc.,
 * this hook redirects the open to a virtual filesystem path that
 * contains the virtual process's information.
 *
 * Current implementation: stub that logs and returns 0 (success).
 *
 * @return 0 on success, negative errno on failure.
 */
extern "C" JNIEXPORT jint
Java_com_atlas_virtualspace_core_hook_NativeHookBridge_hookOpen(
        JNIEnv* env, jclass clazz) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_initialized) {
        LOGE("hookOpen: native bridge not initialized");
        return -EPERM;
    }

    LOGI("hookOpen: installing open/openat PLT hooks (stub)");

    // ── Stub implementation ──
    // Production build would hook openat (the primary syscall on ARM64)
    // and check if the path starts with "/proc/self/".
    // If so, rewrite the path to g_virtual_root_path + "/proc/{virtual_pid}/..."
    //
    // Example:
    //   bh_hook_plt("openat", (void*)hooked_openat, (void**)&orig_openat, nullptr);

    LOGI("hookOpen: open/openat hooks installed (stub — returns success)");
    return 0;
}

// ──────────────────────────────────────────────────────────────
//  JNI: hookFork
// ──────────────────────────────────────────────────────────────

/**
 * Hooks the fork system call to track child processes spawned by
 * virtual apps. Each fork from a virtual process is recorded so
 * that Atlas can manage the child's lifecycle.
 *
 * Current implementation: stub that logs and returns 0 (success).
 *
 * @return 0 on success, negative errno on failure.
 */
extern "C" JNIEXPORT jint
Java_com_atlas_virtualspace_core_hook_NativeHookBridge_hookFork(
        JNIEnv* env, jclass clazz) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_initialized) {
        LOGE("hookFork: native bridge not initialized");
        return -EPERM;
    }

    LOGI("hookFork: installing fork PLT hook (stub)");

    // ── Stub implementation ──
    // Production build would hook fork/vfork and record the child PID
    // in a shared data structure, associating it with the parent's
    // virtual process record.
    //
    // Example:
    //   bh_hook_plt("fork", (void*)hooked_fork, (void**)&orig_fork, nullptr);

    LOGI("hookFork: fork hook installed (stub — returns success)");
    return 0;
}

// ──────────────────────────────────────────────────────────────
//  JNI: getMemoryMap
// ──────────────────────────────────────────────────────────────

/**
 * Returns the memory map for a virtual process, equivalent to
 * reading /proc/{pid}/maps.
 *
 * The returned byte array contains the text content of the maps file,
 * UTF-8 encoded, with each line in the standard format:
 *   address perms offset dev inode pathname
 *
 * For virtual processes, this reads the actual /proc/{pid}/maps if
 * accessible. If the process is virtual (has the Atlas marker), the
 * memory regions are filtered to ensure all entries have at least
 * read permission (r--) so that GameGuardian can enumerate them.
 *
 * @param pid  The PID of the virtual process.
 * @return UTF-8 encoded byte array of the memory map content.
 */
extern "C" JNIEXPORT jbyteArray
Java_com_atlas_virtualspace_core_hook_NativeHookBridge_getMemoryMap(
        JNIEnv* env, jclass clazz, jint pid) {

    std::lock_guard<std::mutex> lock(g_mutex);

    const std::string mapsPath = "/proc/" + std::to_string(pid) + "/maps";
    std::string content;

    if (!readFileToString(mapsPath, content)) {
        LOGW("getMemoryMap: cannot read %s — returning empty map", mapsPath.c_str());

        // Return an empty byte array rather than null
        jbyteArray empty = env->NewByteArray(0);
        return empty;
    }

    // Check if this is a virtual process — if so, ensure all memory
    // regions are readable (r--) so GG can scan them.
    bool isVirtual = false;
    {
        const std::string cmdlinePath = "/proc/" + std::to_string(pid) + "/cmdline";
        std::string cmdline;
        if (readFileToString(cmdlinePath, cmdline)) {
            isVirtual = cmdline.find(ATLAS_PROC_MARKER) != std::string::npos;
        }
    }

    if (isVirtual) {
        // Process the maps content to ensure readable permissions.
        // Standard /proc/maps line format:
        //   00400000-0040d000 r-xp 00000000 fd:00 123456  /path/to/file
        // We change ---p and --xp to r--p and r-xp respectively.
        std::istringstream stream(content);
        std::ostringstream result;
        std::string line;

        while (std::getline(stream, line)) {
            // Find the permissions field (second field, after the address range)
            size_t dashPos = line.find('-');
            if (dashPos == std::string::npos) {
                result << line << "\n";
                continue;
            }

            size_t spaceAfterAddr = line.find(' ', dashPos);
            if (spaceAfterAddr == std::string::npos) {
                result << line << "\n";
                continue;
            }

            // Permissions start at spaceAfterAddr + 1, 4 characters (e.g. "r-xp")
            size_t permStart = spaceAfterAddr + 1;
            if (permStart + 4 <= line.size()) {
                char& readPerm = line[permStart];     // First char: 'r' or '-'
                if (readPerm == '-') {
                    // Make the region readable for GG compatibility
                    readPerm = 'r';
                }
            }

            result << line << "\n";
        }

        content = result.str();
        LOGD("getMemoryMap: processed virtual process %d map (%zu bytes)", pid, content.size());
    } else {
        LOGD("getMemoryMap: read host process %d map (%zu bytes)", pid, content.size());
    }

    // Convert to JNI byte array
    jsize len = static_cast<jsize>(content.size());
    jbyteArray byteArray = env->NewByteArray(len);
    if (byteArray == nullptr) {
        LOGE("getMemoryMap: failed to allocate byte array of size %d", len);
        return env->NewByteArray(0);
    }

    env->SetByteArrayRegion(byteArray, 0, len,
            reinterpret_cast<const jbyte*>(content.data()));

    return byteArray;
}

// ──────────────────────────────────────────────────────────────
//  JNI: getProcessList
// ──────────────────────────────────────────────────────────────

/**
 * Returns the PIDs of all currently-running virtual processes.
 *
 * Scans /proc for numeric directories, then checks each process's
 * /proc/{pid}/cmdline for the Atlas virtual process marker.
 * Virtual processes are those whose cmdline contains the marker
 * string "com.atlas.virtual".
 *
 * @return Array of PIDs. Empty if no virtual processes are running.
 */
extern "C" JNIEXPORT jintArray
Java_com_atlas_virtualspace_core_hook_NativeHookBridge_getProcessList(
        JNIEnv* env, jclass clazz) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::vector<int> virtualPids;

    DIR* procDir = opendir("/proc");
    if (procDir == nullptr) {
        LOGE("getProcessList: cannot open /proc — errno=%d", errno);
        jintArray empty = env->NewIntArray(0);
        return empty;
    }

    struct dirent* entry;
    while ((entry = readdir(procDir)) != nullptr) {
        if (!isNumericDirectory(entry->d_name)) {
            continue;
        }

        int pid = atoi(entry->d_name);
        if (pid <= 0) {
            continue;
        }

        // Check if this process has the Atlas marker in its cmdline
        std::string cmdlinePath = "/proc/" + std::string(entry->d_name) + "/cmdline";
        std::string cmdline;

        if (readFileToString(cmdlinePath, cmdline)) {
            // cmdline is null-separated; search for the marker in the raw bytes
            if (cmdline.find(ATLAS_PROC_MARKER) != std::string::npos) {
                virtualPids.push_back(pid);
            }
        }
    }

    closedir(procDir);

    LOGD("getProcessList: found %zu virtual process(es)", virtualPids.size());

    // Convert to JNI int array
    jsize count = static_cast<jsize>(virtualPids.size());
    jintArray result = env->NewIntArray(count);
    if (result == nullptr) {
        LOGE("getProcessList: failed to allocate int array of size %d", count);
        return env->NewIntArray(0);
    }

    if (count > 0) {
        env->SetIntArrayRegion(result, 0, count, virtualPids.data());
    }

    return result;
}

// ──────────────────────────────────────────────────────────────
//  JNI: isVirtualProcess
// ──────────────────────────────────────────────────────────────

/**
 * Checks whether a given PID belongs to a virtual app process.
 *
 * A process is considered virtual if its /proc/{pid}/cmdline
 * contains the Atlas marker string "com.atlas.virtual".
 *
 * @param pid  The PID to check.
 * @return JNI_TRUE if the PID is a virtual app process, JNI_FALSE otherwise.
 */
extern "C" JNIEXPORT jboolean
Java_com_atlas_virtualspace_core_hook_NativeHookBridge_isVirtualProcess(
        JNIEnv* env, jclass clazz, jint pid) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (pid <= 0) {
        return JNI_FALSE;
    }

    const std::string cmdlinePath = "/proc/" + std::to_string(pid) + "/cmdline";
    std::string cmdline;

    if (!readFileToString(cmdlinePath, cmdline)) {
        LOGD("isVirtualProcess: cannot read %s", cmdlinePath.c_str());
        return JNI_FALSE;
    }

    // cmdline in Linux is null-separated; search the raw buffer
    bool isVirtual = cmdline.find(ATLAS_PROC_MARKER) != std::string::npos;

    LOGD("isVirtualProcess: pid=%d → %s", pid, isVirtual ? "true" : "false");
    return isVirtual ? JNI_TRUE : JNI_FALSE;
}

// ──────────────────────────────────────────────────────────────
//  JNI: initNativeBridge
// ──────────────────────────────────────────────────────────────

/**
 * Initializes the native bridge with the virtual filesystem root path.
 *
 * This must be called before any other native hook functions. It:
 * - Stores the virtual root path for /proc/self/ redirections
 * - Initializes internal data structures for memory map tracking
 * - Prepares the process fork tracking registry
 *
 * @param virtualRootPath  Absolute path to the virtual FS root
 *        (e.g. "/data/data/com.atlas.virtualspace/virtual/").
 * @return 0 on success, negative errno value on failure.
 */
extern "C" JNIEXPORT jint
Java_com_atlas_virtualspace_core_hook_NativeHookBridge_initNativeBridge(
        JNIEnv* env, jclass clazz, jstring virtualRootPath) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (virtualRootPath == nullptr) {
        LOGE("initNativeBridge: virtualRootPath is null");
        return -EINVAL;
    }

    const char* pathStr = env->GetStringUTFChars(virtualRootPath, nullptr);
    if (pathStr == nullptr) {
        LOGE("initNativeBridge: failed to get UTF string from jstring");
        return -ENOMEM;
    }

    g_virtual_root_path = std::string(pathStr);
    env->ReleaseStringUTFChars(virtualRootPath, pathStr);

    // Validate the path
    struct stat st;
    if (stat(g_virtual_root_path.c_str(), &st) != 0) {
        LOGW("initNativeBridge: virtual root path does not exist: %s (will be created)",
             g_virtual_root_path.c_str());
    }

    // Initialize internal data structures
    // (In production: allocate shared memory regions, init bhook, etc.)

    g_initialized = true;

    LOGI("initNativeBridge: initialized with virtualRoot=%s", g_virtual_root_path.c_str());
    return 0;
}

// ──────────────────────────────────────────────────────────────
//  JNI: cleanupNativeBridge
// ──────────────────────────────────────────────────────────────

/**
 * Cleans up all native hooks and releases allocated resources.
 *
 * After calling this, no native hook functions may be used until
 * initNativeBridge is called again.
 *
 * @return 0 on success, negative errno value on failure.
 */
extern "C" JNIEXPORT jint
Java_com_atlas_virtualspace_core_hook_NativeHookBridge_cleanupNativeBridge(
        JNIEnv* env, jclass clazz) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_initialized) {
        LOGW("cleanupNativeBridge: not initialized — nothing to clean up");
        return 0;
    }

    LOGI("cleanupNativeBridge: cleaning up native bridge");

    // Release the virtual root path
    g_virtual_root_path.clear();

    // Mark as uninitialized
    g_initialized = false;

    // (In production: unhook all PLT hooks via bh_hook_plt_cancel,
    //  deallocate shared memory regions, etc.)

    LOGI("cleanupNativeBridge: cleanup complete");
    return 0;
}
