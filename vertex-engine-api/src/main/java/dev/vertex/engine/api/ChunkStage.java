package dev.vertex.engine.api;

/**
 * A stage of chunk generation a module can take over from the server.
 *
 * <p>A module declares the whole set it owns at registration rather than being asked per stage,
 * because a generator that produces surfaced, carved terrain does so in one pass -- it cannot
 * answer "surface only" later. The engine then skips the server's own version of every stage in
 * that set, but only for chunks the module actually generated: a chunk that fell back to vanilla
 * terrain must get vanilla's surface and carvers too, or it comes out as bare noise.
 *
 * <h2>Ordering, and why {@link #BIOMES} is special</h2>
 *
 * The server's pipeline runs
 * {@code EMPTY -> STRUCTURE_STARTS -> STRUCTURE_REFERENCES -> BIOMES -> NOISE -> SURFACE ->
 * CARVERS -> FEATURES}. Note that {@code BIOMES} runs <em>before</em> {@code NOISE}.
 *
 * <p>That ordering is the whole reason the module hook fires at the {@code BIOMES} status rather
 * than at {@code NOISE}: a multi-noise generator resolves the biome grid and the terrain in one
 * pass, so if the hook ran at {@code NOISE} the server would already have spent a full
 * {@code createBiomes} pass recomputing climate samples the module was about to produce anyway.
 * Generating at the earliest stage that needs the data, and having every later stage read what
 * was produced, is strictly less work than generating late and discarding the duplicate.
 */
public enum ChunkStage {

    /** Terrain shape, aquifers and ore veins. Always owned -- it is what {@link ChunkGenerationHook} generates. */
    NOISE,

    /** Surface rules: the grass, dirt, sand and bedrock banding over the noise shape. */
    SURFACE,

    /** Carvers: caves and ravines cut back out of the surfaced terrain. */
    CARVERS,

    /**
     * The biome grid: one biome per 4x4x4 quart, written through {@link ChunkTarget#setBiomes}.
     *
     * <p>Owning this stops the server's own {@code createBiomes} pass, which otherwise recomputes
     * the identical climate samples the module already took -- measured at over a third of total
     * generation time. A Bukkit {@code BiomeProvider} cannot avoid that cost at all, because
     * CraftBukkit's {@code CustomWorldChunkManager.getNoiseBiome} samples the vanilla climate
     * router before it ever consults the provider.
     *
     * <p>Only claim this if {@link ChunkTarget#setBiomes} is genuinely called for every chunk the
     * hook reports success for. A chunk left with its default biome grid is not merely slower, it
     * is wrong -- mob spawning, foliage colour and structure placement all read it.
     */
    BIOMES
}
