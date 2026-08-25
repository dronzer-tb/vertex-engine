package dev.vertex.engine.api;

/**
 * The chunk a module writes its generated terrain into.
 *
 * <p>Deliberately free of Minecraft types: a module names blocks and biomes as strings and hands
 * over bulk index arrays, and the engine performs the actual write. That keeps modules
 * independent of the server's internals, and keeps the one performance-critical conversion in a
 * single place where it can be optimised for every module at once.
 */
public interface ChunkTarget {

    /** World-absolute Y of the bottom of the chunk. */
    int minY();

    /** Total chunk height in blocks. */
    int height();

    /**
     * Writes every block in the chunk.
     *
     * @param palette block state strings, e.g. {@code "minecraft:stone"}
     * @param indices one index into {@code palette} per block, ordered {@code (y*16+z)*16+x}
     *                within each 16-block section, sections running bottom-up from {@link #minY()}
     */
    void setBlocks(String[] palette, short[] indices);

    /**
     * Writes every biome in the chunk.
     *
     * @param palette biome ids, e.g. {@code "minecraft:plains"}
     * @param indices one index into {@code palette} per 4x4x4 quart, same section ordering
     */
    void setBiomes(String[] palette, short[] indices);
}
