package dev.vertex.engine.api;

import java.nio.file.Path;

/** What a module is handed at enable time. The engine's own internals stay out of reach. */
public interface ModuleContext {

    /**
     * Registers the chunk-generation hook. One module owns terrain: a second registration is
     * refused rather than silently overriding the first, since which one won would otherwise
     * depend on module load order.
     */
    void registerChunkGeneration(ChunkGenerationHook hook);

    /** {@code vertex/modules/<id>/}, created on demand, for this module's config and data. */
    Path dataDirectory();
}
