package dev.vertex.engine;

import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Terrain generation supplied by a module, replacing vanilla's noise pass for one chunk.
 *
 * <p>Called from {@code NoiseBasedChunkGenerator#fillFromNoise} on a Folia worldgen thread.
 * An implementation must write only into {@code into} -- a write to any other chunk crosses a
 * region boundary, which Folia does not permit off the owning thread.
 */
public interface ChunkGenerationHook {

    HookResult generate(long seed, int chunkX, int chunkZ, ChunkAccess into);
}
