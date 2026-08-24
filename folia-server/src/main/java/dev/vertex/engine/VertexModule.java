package dev.vertex.engine;

/**
 * A subsystem loaded from {@code vertex/modules/} before the plugin system starts, so it can
 * register hooks that must be in place before any world loads.
 */
public interface VertexModule {

    String id();

    String version();

    /** Register hooks and services. Called once, on the server thread, before worlds load. */
    void onEnable(VertexEngine engine);
}
