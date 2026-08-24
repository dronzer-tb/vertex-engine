package dev.vertex.engine.api;

/**
 * Terrain generation supplied by a module, replacing the server's noise pass for one chunk.
 *
 * <p>Called on a worldgen thread. An implementation must write only through the supplied
 * {@link ChunkTarget} -- reaching for any other chunk crosses a region boundary, which the
 * server does not permit off the owning thread.
 */
public interface ChunkGenerationHook {

    HookResult generate(long seed, int chunkX, int chunkZ, ChunkTarget target);
}
