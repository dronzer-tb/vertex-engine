package dev.vertex.engine.api;

/**
 * A subsystem loaded from {@code vertex/modules/} before the plugin system starts, so it can
 * register hooks that must be in place before any world loads.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}: declare the
 * implementing class in {@code META-INF/services/dev.vertex.engine.api.VertexModule}.
 */
public interface VertexModule {

    String id();

    String version();

    /** Register hooks and services. Called once, on the server thread, before worlds load. */
    void onEnable(ModuleContext context);
}
