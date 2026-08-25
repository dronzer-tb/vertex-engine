package dev.vertex.engine;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-stage chunk generation timings, for comparing terrain generators on one server.
 *
 * <p>Measured at the chunk status boundary rather than inside any generator, because that is the
 * one place all three configurations pass through identically: stock generation, Oxide as a
 * Bukkit plugin -- whose {@code ChunkGenerator} the server calls from inside its own noise stage
 * -- and Oxide as a Vertex module, which returns before it. The same numbers therefore mean the
 * same thing in all three, which is the whole point of measuring here.
 *
 * <p>Off unless {@code -Dvertex.timings=true}. When off, {@link #begin()} returns 0 and the
 * record methods return immediately, so an unmeasured server pays a predictable branch and
 * nothing else.
 *
 * <p>The counts are deliberately not a histogram. Mean nanoseconds per chunk over a fixed
 * workload is what distinguishes these generators; tail latency needs a real profiler, and this
 * class is not pretending to be one.
 */
public final class VertexChunkTimings {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("vertex.timings");

    /** Summary interval, in chunks through the noise stage. */
    private static final long REPORT_EVERY = Long.getLong("vertex.timings.every", 500L);

    private static final Stage NOISE = new Stage("noise");
    private static final Stage SURFACE = new Stage("surface");
    private static final Stage CARVERS = new Stage("carvers");

    /** Chunks the module generated, against chunks that fell back to the server's own terrain. */
    private static final AtomicLong MODULE_CHUNKS = new AtomicLong();
    private static final AtomicLong FALLBACK_CHUNKS = new AtomicLong();

    private VertexChunkTimings() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Start of a stage. The return value is opaque; hand it back to a record method. */
    public static long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void recordNoise(final long begunAt, final boolean byModule) {
        if (!ENABLED) {
            return;
        }
        NOISE.add(System.nanoTime() - begunAt);
        (byModule ? MODULE_CHUNKS : FALLBACK_CHUNKS).incrementAndGet();
        if (NOISE.count.get() % REPORT_EVERY == 0) {
            report();
        }
    }

    public static void recordSurface(final long begunAt) {
        if (ENABLED) {
            SURFACE.add(System.nanoTime() - begunAt);
        }
    }

    public static void recordCarvers(final long begunAt) {
        if (ENABLED) {
            CARVERS.add(System.nanoTime() - begunAt);
        }
    }

    /** Writes one summary to the log. Safe to call at any time, including from a shutdown hook. */
    public static void report() {
        if (!ENABLED) {
            return;
        }
        long module = MODULE_CHUNKS.get();
        long fallback = FALLBACK_CHUNKS.get();
        LOGGER.info("[VertexEngine] timings after {} chunks ({} by module, {} by the server): {} | {} | {} -- total {}",
                module + fallback, module, fallback,
                NOISE.summary(), SURFACE.summary(), CARVERS.summary(),
                millis(NOISE.nanos.get() + SURFACE.nanos.get() + CARVERS.nanos.get()));
    }

    private static String millis(final long nanos) {
        return String.format("%.2fms", nanos / 1_000_000.0);
    }

    private static final class Stage {

        private final String name;
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong nanos = new AtomicLong();

        Stage(String name) {
            this.name = name;
        }

        void add(long elapsed) {
            this.count.incrementAndGet();
            this.nanos.addAndGet(elapsed);
        }

        String summary() {
            long chunks = this.count.get();
            if (chunks == 0) {
                return this.name + " not run";
            }
            double meanMs = this.nanos.get() / 1_000_000.0 / chunks;
            return String.format("%s %.3fms/chunk over %d", this.name, meanMs, chunks);
        }
    }
}
