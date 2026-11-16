package dev.vertex.engine;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vertex.engine.api.ChunkTarget;
import io.netty.buffer.Unpooled;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.core.RegistryAccess;

/**
 * Writes a module's output into a real chunk.
 *
 * <p>This is the only place where a module's strings and index arrays meet Minecraft's types,
 * which is deliberate: the previous attempt at this project marshalled per block across the FFI
 * boundary and lost every millisecond it had won in Rust. Palettes are resolved once per chunk
 * -- a few dozen lookups -- and the indices are then a flat array walk.
 *
 * <p>Not thread-safe and not meant to be: one instance serves one chunk on the worldgen thread
 * that owns it.
 */
final class ChunkAccessTarget implements ChunkTarget {

    /**
     * Parsed block states by the module's string. A module's palette only grows and its strings
     * mean the same thing for the server's lifetime, so parsing each one once is enough; before
     * this every chunk re-parsed the whole palette.
     */
    private static final ConcurrentHashMap<String, BlockState> PARSED_STATES = new ConcurrentHashMap<>();

    /** Largest network-format section: bits byte, 256 varint palette ids, 512 longs. */
    private static final int SECTION_BUFFER_BYTES = 1 + 5 + 256 * 5 + 512 * Long.BYTES;

    private final ChunkAccess chunk;
    private final RegistryAccess registries;

    ChunkAccessTarget(ChunkAccess chunk, RegistryAccess registries) {
        this.chunk = chunk;
        this.registries = registries;
    }

    @Override
    public int minY() {
        return this.chunk.getMinY();
    }

    @Override
    public int height() {
        return this.chunk.getHeight();
    }

    @Override
    public void setBlocks(String[] palette, short[] indices) {
        int expected = 256 * height();
        if (indices.length != expected) {
            throw new IllegalArgumentException("expected " + expected + " block indices for a "
                    + height() + "-block chunk, got " + indices.length);
        }
        BlockState[] states = resolveBlocks(palette);

        int minSectionY = this.chunk.getMinSectionY();
        int sectionCount = height() / 16;
        for (int section = 0; section < sectionCount; section++) {
            LevelChunkSection target = this.chunk.getSection(
                    this.chunk.getSectionIndexFromSectionY(minSectionY + section));
            writeSection(target, states, indices, section * 4096);
        }
    }

    /**
     * Installs each packed section whole through {@code PalettedContainer.read}, the public
     * network-format reader, which swaps in the palette and the bit storage in one step. Its
     * format is the module's layout with global registry ids in the palette, so the section is
     * only re-framed here, never unpacked.
     */
    @Override
    public void setSections(String[] palette, short[][] sectionPalettes, long[][] packed, short[][] raw) {
        int sectionCount = height() / 16;
        if (sectionPalettes.length != sectionCount || packed.length != sectionCount || raw.length != sectionCount) {
            throw new IllegalArgumentException("expected " + sectionCount + " sections, got "
                    + sectionPalettes.length + "/" + packed.length + "/" + raw.length);
        }
        BlockState[] states = resolveBlocks(palette);
        int[] globalIds = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            globalIds[i] = Block.BLOCK_STATE_REGISTRY.getId(states[i]);
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer(SECTION_BUFFER_BYTES));
        int minSectionY = this.chunk.getMinSectionY();
        for (int section = 0; section < sectionCount; section++) {
            LevelChunkSection target = this.chunk.getSection(
                    this.chunk.getSectionIndexFromSectionY(minSectionY + section));
            if (raw[section] != null) {
                writeSection(target, states, raw[section], 0);
                continue;
            }
            short[] local = sectionPalettes[section];
            int n = local.length;
            if (n < 1 || n > 256) {
                throw new IllegalArgumentException("section " + section + " palette has " + n + " entries");
            }
            if (n == 1 && states[local[0] & 0xFFFF].isAir()) {
                // Sections start empty.
                continue;
            }
            int bits = n == 1 ? 0 : Math.max(4, 32 - Integer.numberOfLeadingZeros(n - 1));
            int longs = bits == 0 ? 0 : (4096 + 64 / bits - 1) / (64 / bits);
            long[] words = packed[section];
            if (words.length != longs) {
                throw new IllegalArgumentException("section " + section + " has " + words.length
                        + " packed longs, " + n + " palette entries need " + longs);
            }

            buffer.clear();
            buffer.writeByte(bits);
            if (bits == 0) {
                buffer.writeVarInt(globalIds[local[0] & 0xFFFF]);
            } else {
                buffer.writeVarInt(n);
                for (short entry : local) {
                    buffer.writeVarInt(globalIds[entry & 0xFFFF]);
                }
                for (long word : words) {
                    buffer.writeLong(word);
                }
            }
            target.getStates().read(buffer);
            target.recalcBlockCounts();
        }
    }

    /**
     * The per-block path: {@link #setBlocks}, and a packed section with more distinct states than
     * a local palette holds. acquire()/release() rather than the per-call threading check, since
     * the whole section is written in one go.
     */
    private static void writeSection(LevelChunkSection target, BlockState[] states, short[] indices, int base) {
        target.acquire();
        try {
            for (int i = 0; i < 4096; i++) {
                BlockState state = states[indices[base + i] & 0xFFFF];
                if (state.isAir()) {
                    // Sections start empty; writing air back costs a palette lookup for no change.
                    continue;
                }
                target.setBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15, state, false);
            }
        } finally {
            target.release();
        }
    }

    @Override
    public void setBiomes(String[] palette, short[] indices) {
        int expected = 64 * (height() / 16);
        if (indices.length != expected) {
            throw new IllegalArgumentException("expected " + expected + " biome indices for a "
                    + height() + "-block chunk, got " + indices.length);
        }
        Holder<Biome>[] biomes = resolveBiomes(palette);

        int quartMinX = QuartPos.fromBlock(this.chunk.getPos().getMinBlockX());
        int quartMinZ = QuartPos.fromBlock(this.chunk.getPos().getMinBlockZ());
        int quartMinY = QuartPos.fromBlock(minY());
        // The chunk's own filler walks every section for us; the resolver just has to turn the
        // absolute quart position it is handed back into an index into the module's array.
        BiomeResolver resolver = (quartX, quartY, quartZ, sampler) -> {
            int localX = quartX - quartMinX;
            int localY = quartY - quartMinY;
            int localZ = quartZ - quartMinZ;
            int index = (localY * 4 + localZ) * 4 + localX;
            if (localX < 0 || localX > 3 || localZ < 0 || localZ > 3 || index < 0 || index >= indices.length) {
                // fillBiomesFromNoise clamps to the chunk, so this is unreachable unless the
                // chunk's height stopped matching what the module was told. Nothing to fall back
                // to at this point, so say so rather than writing the wrong biome.
                throw new IllegalStateException("biome quart (" + quartX + ", " + quartY + ", "
                        + quartZ + ") is outside the chunk this target was built for");
            }
            return biomes[indices[index] & 0xFFFF];
        };
        this.chunk.fillBiomesFromNoise(resolver, null);
    }

    private BlockState[] resolveBlocks(String[] palette) {
        HolderLookup<Block> blocks = this.registries.lookupOrThrow(Registries.BLOCK);
        BlockState[] states = new BlockState[palette.length];
        for (int i = 0; i < palette.length; i++) {
            states[i] = PARSED_STATES.computeIfAbsent(palette[i], name -> {
                try {
                    return BlockStateParser.parseForBlock(blocks, name, false).blockState();
                } catch (CommandSyntaxException e) {
                    throw new IllegalArgumentException(
                            "module returned an unparseable block state: " + name, e);
                }
            });
        }
        return states;
    }

    @SuppressWarnings("unchecked")
    private Holder<Biome>[] resolveBiomes(String[] palette) {
        HolderLookup<Biome> lookup = this.registries.lookupOrThrow(Registries.BIOME);
        Holder<Biome>[] biomes = new Holder[palette.length];
        for (int i = 0; i < palette.length; i++) {
            String name = palette[i];
            Identifier id = Identifier.parse(name);
            biomes[i] = lookup.get(ResourceKey.create(Registries.BIOME, id))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "module returned an unknown biome: " + name));
        }
        return biomes;
    }
}
