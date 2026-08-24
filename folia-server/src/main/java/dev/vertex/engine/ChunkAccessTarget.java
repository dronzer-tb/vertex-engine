package dev.vertex.engine;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vertex.engine.api.ChunkTarget;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
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
            int base = section * 4096;
            // acquire()/release() rather than the per-call threading check: the whole section is
            // written here in one go, and the check is per setBlockState.
            target.acquire();
            try {
                for (int i = 0; i < 4096; i++) {
                    BlockState state = states[indices[base + i] & 0xFFFF];
                    if (state.isAir()) {
                        // Sections start empty; writing air back costs a palette lookup per block
                        // for no change.
                        continue;
                    }
                    target.setBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15, state, false);
                }
            } finally {
                target.release();
            }
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
            try {
                states[i] = BlockStateParser.parseForBlock(blocks, palette[i], false).blockState();
            } catch (CommandSyntaxException e) {
                throw new IllegalArgumentException(
                        "module returned an unparseable block state: " + palette[i], e);
            }
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
