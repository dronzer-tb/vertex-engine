package dev.vertex.engine.api;

/**
 * Identifies the chunk a hook has been asked to generate.
 *
 * <p>A record rather than four parameters so later stages -- surface, carvers -- can carry more
 * context without breaking every module that already compiles against this API.
 *
 * @param dimensionId the level's dimension, e.g. {@code "minecraft:overworld"}. A generator is
 *                    per-dimension: overworld and nether differ in height, noise settings and
 *                    default blocks, so a module cannot serve one from the other's state.
 * @param seed        the level seed
 */
public record ChunkRequest(String dimensionId, long seed, int chunkX, int chunkZ) {
}
