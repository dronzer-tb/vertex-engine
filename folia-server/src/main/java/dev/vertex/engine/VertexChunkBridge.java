package dev.vertex.engine;

import com.mojang.logging.LogUtils;
import dev.vertex.engine.api.ChunkRequest;
import dev.vertex.engine.api.HookResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.slf4j.Logger;

import java.util.EnumSet;

/**
 * The single call site the fork patches into upstream worldgen.
 *
 * <p>Kept out of {@code ChunkStatusTasks} so the patch against Minecraft's own source stays a
 * three-line early return: everything that could need changing lives here, in the fork's own
 * source tree, where it does not have to be rebased against Mojang.
 */
public final class VertexChunkBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Primed after a module writes terrain. These two are what the noise stage normally leaves
     * behind, and surface rules, carvers and structure placement all read them -- a chunk whose
     * blocks came from a module but whose heightmaps did not would put structures at whatever
     * height vanilla last believed in.
     */
    private static final EnumSet<Heightmap.Types> WORLDGEN_HEIGHTMAPS =
            EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG);

    private VertexChunkBridge() {
    }

    /**
     * Offers one chunk to the registered module.
     *
     * @return {@code true} when the module generated it and the server must skip its own noise
     *         pass; {@code false} in every other case, including every failure
     */
    public static boolean generateNoise(final ServerLevel level, final ChunkGenerator generator,
                                        final ChunkAccess chunk) {
        VertexEngine engine = VertexEngine.get();
        if (engine == null || !engine.hasChunkGeneration()) {
            return false;
        }
        // Flat and debug worlds are not noise terrain and a module has nothing to say about
        // them; superflat in particular would silently become whatever the module generates.
        if (!(generator instanceof NoiseBasedChunkGenerator)) {
            return false;
        }

        ChunkRequest request = new ChunkRequest(
                level.dimension().identifier().toString(),
                level.getSeed(),
                chunk.getPos().x(),
                chunk.getPos().z());

        HookResult result;
        try {
            result = engine.generateChunk(request, new ChunkAccessTarget(chunk, level.registryAccess()));
        } catch (Throwable t) {
            // engine.generateChunk already catches what the module throws; this catches a
            // failure in the write itself, which would otherwise take the worldgen thread -- and
            // on Folia the whole region -- down with it.
            LOGGER.error("[VertexEngine] writing chunk (" + request.chunkX() + ", " + request.chunkZ()
                    + ") failed -- falling back to vanilla", t);
            return false;
        }
        if (result.status() != HookResult.Status.SUCCESS) {
            return false;
        }

        Heightmap.primeHeightmaps(chunk, WORLDGEN_HEIGHTMAPS);
        return true;
    }
}
