package dev.vertex.engine.api;

/**
 * A stage of chunk generation a module can take over from the server.
 *
 * <p>A module declares the whole set it owns at registration rather than being asked per stage,
 * because a generator that produces surfaced, carved terrain does so in one pass -- it cannot
 * answer "surface only" later. The engine then skips the server's own version of every stage in
 * that set, but only for chunks the module actually generated: a chunk that fell back to vanilla
 * terrain must get vanilla's surface and carvers too, or it comes out as bare noise.
 */
public enum ChunkStage {

    /** Terrain shape, aquifers and ore veins. Always owned -- it is what {@link ChunkGenerationHook} generates. */
    NOISE,

    /** Surface rules: the grass, dirt, sand and bedrock banding over the noise shape. */
    SURFACE,

    /** Carvers: caves and ravines cut back out of the surfaced terrain. */
    CARVERS
}
