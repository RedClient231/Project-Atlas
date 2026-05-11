package com.atlas.virtualspace.core.engine

/**
 * Represents the lifecycle state of a virtual app process.
 */
enum class ProcessState {
    /** Process is being forked / initialized. */
    STARTING,
    /** Process is running and reachable via IPC. */
    RUNNING,
    /** Process is in the process of stopping. */
    STOPPING,
    /** Process was stopped gracefully (user-initiated or system). */
    STOPPED,
    /** Process crashed or became unreachable (death notification received). */
    CRASHED,
}
