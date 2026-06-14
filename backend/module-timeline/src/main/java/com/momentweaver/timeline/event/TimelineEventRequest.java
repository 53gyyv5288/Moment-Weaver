package com.momentweaver.timeline.event;

/**
 * Marker package-info for M3 timeline event listeners.
 * The event class lives in {@code com.momentweaver.common.event} (module-common)
 * so producers (module-memory) and consumers (module-timeline) can reference
 * it without a circular module dependency.
 */
final class PackageMarker {
    private PackageMarker() {}
}