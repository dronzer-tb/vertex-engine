package dev.vertex.engine;

import com.mojang.logging.LogUtils;
import dev.vertex.engine.api.ChunkRequest;
import dev.vertex.engine.api.ChunkStage;
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
     * <p>Called from the {@code BIOMES} status, not {@code NOISE}. That is deliberate and is the
     * point of the whole arrangement: the server runs {@code BIOMES} before {@code NOISE}, and a
     * multi-noise module resolves biomes and terrain in a single pass. Hooking the later stage
     * would mean the server had already paid for a full {@code createBiomes} pass -- recomputing
     * exactly the climate samples the module was about to produce -- before the module ran at
     * all. Generating at the earliest stage that needs the data, and letting every later stage
     * read what was produced, removes that duplicate outright.
     *
     * @return {@code true} when the module generated it and the server must skip its own biome
     *         pass; {@code false} in every other case, including every failure
     */
    public static boolean generateChunk(final ServerLevel level, final ChunkGenerator generator,
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

        chunk.vertexGeneratedTerrain = true;
        Heightmap.primeHeightmaps(chunk, WORLDGEN_HEIGHTMAPS);
        return true;
    }

    /**
     * Whether the server must skip its own noise pass for this chunk.
     *
     * <p>Always true for a chunk the module generated: producing terrain is what the hook is for,
     * and {@link ChunkStage#NOISE} is owned unconditionally. The chunk-level check still matters,
     * because a chunk that fell back must still get vanilla noise.
     */
    public static boolean skipNoise(final ChunkAccess chunk) {
        return ownsStageFor(chunk, ChunkStage.NOISE);
    }

    /**
     * Whether the server must skip its own surface pass for this chunk.
     *
     * <p>Both conditions matter. The module has to have said it produces surfaces at all, and
     * this particular chunk has to be one it generated -- a chunk that fell back is vanilla noise
     * and needs vanilla's surface rules, or it stays bare stone to the sky.
     */
    public static boolean skipSurface(final ChunkAccess chunk) {
        return ownsStageFor(chunk, ChunkStage.SURFACE);
    }

    /** Whether the server must skip its own carvers for this chunk. See {@link #skipSurface}. */
    public static boolean skipCarvers(final ChunkAccess chunk) {
        return ownsStageFor(chunk, ChunkStage.CARVERS);
    }

    /**
     * Whether the server must skip its own {@code createBiomes} pass for this chunk.
     *
     * <p>Same two conditions as {@link #skipSurface}: the module must own the stage, and this
     * chunk must be one it actually generated. A chunk that fell back to vanilla terrain has
     * only its default biome grid, so vanilla has to fill it or the chunk comes out all plains.
     */
    public static boolean skipBiomes(final ChunkAccess chunk) {
        return ownsStageFor(chunk, ChunkStage.BIOMES);
    }

    private static boolean ownsStageFor(final ChunkAccess chunk, final ChunkStage stage) {
        if (!chunk.vertexGeneratedTerrain) {
            return false;
        }
        VertexEngine engine = VertexEngine.get();
        return engine != null && engine.ownsStage(stage);
    }
}
