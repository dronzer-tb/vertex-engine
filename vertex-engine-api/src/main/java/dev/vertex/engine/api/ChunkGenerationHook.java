package dev.vertex.engine.api;

/**
 * Terrain generation supplied by a module, replacing the server's noise pass for one chunk.
 *
 * <p>This is the noise stage only. Surface rules, carvers and features still run afterwards as
 * separate chunk statuses, on top of whatever the module wrote -- a module that already applies
 * its own surface pass will double-apply until those stages are hookable too.
 *
 * <p>Called on a worldgen thread. An implementation must write only through the supplied
 * {@link ChunkTarget} -- reaching for any other chunk crosses a region boundary, which the
 * server does not permit off the owning thread.
 */
public interface ChunkGenerationHook {

    HookResult generate(ChunkRequest request, ChunkTarget target);
}
