package dev.vertex.engine.api;

import java.nio.file.Path;
import java.util.Set;

/** What a module is handed at enable time. The engine's own internals stay out of reach. */
public interface ModuleContext {

    /**
     * Registers the chunk-generation hook and the stages it takes over from the server. One
     * module owns terrain: a second registration is refused rather than silently overriding the
     * first, since which one won would otherwise depend on module load order.
     *
     * @param stages every stage the hook's output already includes. {@link ChunkStage#NOISE} is
     *               implied and may be omitted. Naming a stage the module does not actually
     *               produce leaves that stage missing from the world -- the server will not run
     *               its own.
     */
    void registerChunkGeneration(ChunkGenerationHook hook, Set<ChunkStage> stages);

    /** {@code vertex/<id>/}, created on demand, for this module's config and data. */
    Path dataDirectory();
}
