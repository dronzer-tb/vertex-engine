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
     * Writes every block in the chunk, already packed per section the way the server stores it,
     * so the engine installs each section whole instead of writing 4096 blocks one at a time.
     *
     * <p>For section {@code s} (bottom-up from {@link #minY()}) exactly one form is given:
     * <ul>
     *   <li>{@code sectionPalettes[s]} and {@code packed[s]}: the section's own palette as indices
     *       into {@code palette} (1 to 256 entries), and its entries bit-packed in vanilla's
     *       {@code SimpleBitStorage} layout -- {@code bits = 0} for one entry, otherwise
     *       {@code max(4, ceil(log2(n)))}, {@code 64 / bits} values per long, none spanning two
     *       longs, entry {@code (y*16+z)*16+x}. {@code packed[s]} is empty when {@code bits = 0}.</li>
     *   <li>{@code raw[s]}: 4096 indices into {@code palette}, for a section with more than 256
     *       distinct states.</li>
     * </ul>
     *
     * <p>The default unpacks into {@link #setBlocks}, so an engine without a fast path still
     * accepts it.
     *
     * @since 0.2.0
     */
    default void setSections(String[] palette, short[][] sectionPalettes, long[][] packed, short[][] raw) {
        int sections = height() / 16;
        short[] indices = new short[sections * 4096];
        for (int s = 0; s < sections; s++) {
            int base = s * 4096;
            if (raw[s] != null) {
                System.arraycopy(raw[s], 0, indices, base, 4096);
                continue;
            }
            short[] local = sectionPalettes[s];
            int n = local.length;
            if (n == 1) {
                java.util.Arrays.fill(indices, base, base + 4096, local[0]);
                continue;
            }
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(n - 1));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1;
            long[] words = packed[s];
            for (int i = 0; i < 4096; i++) {
                int entry = (int) ((words[i / perLong] >>> ((i % perLong) * bits)) & mask);
                indices[base + i] = local[entry];
            }
        }
        setBlocks(palette, indices);
    }

    /**
     * Writes every biome in the chunk.
     *
     * @param palette biome ids, e.g. {@code "minecraft:plains"}
     * @param indices one index into {@code palette} per 4x4x4 quart, same section ordering
     */
    void setBiomes(String[] palette, short[] indices);
}
