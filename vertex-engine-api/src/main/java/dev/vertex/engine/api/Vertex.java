package dev.vertex.engine.api;

/**
 * What is running, for code that has to work both on Vertex and off it.
 *
 * <p>This class rather than a static on the engine itself because the engine lives in the
 * server jar while modules are loaded through their own class loader -- only classes on the
 * shared parent, which this API is, are the same class to both. A caller that may be running on
 * stock Paper or Folia should treat {@link NoClassDefFoundError} from these methods as "not
 * Vertex" instead of testing for the class first.
 */
public final class Vertex {

    private static volatile boolean chunkGenerationHooked;

    private Vertex() {
    }

    /** Whether a module has taken over terrain generation, so the server's own is not running. */
    public static boolean isChunkGenerationHooked() {
        return chunkGenerationHooked;
    }

    /** Called by the engine when a module registers. Not for modules to call. */
    public static void markChunkGenerationHooked() {
        chunkGenerationHooked = true;
    }
}
