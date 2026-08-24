package dev.vertex.engine;

/**
 * Identity strings for the Vertex fork. Referenced by the brand patch (which redirects
 * {@code MinecraftServer#getServerModName}) and by the boot banner.
 */
public final class VertexBrand {

    private VertexBrand() {
    }

    public static final String NAME = "Vertex";
    public static final String ENGINE_VERSION = "0.1.0";

    /** Upstream lineage, printed at boot so bug reports stay diagnosable. */
    public static final String UPSTREAM_FOLIA = "26.1.2";
    public static final String UPSTREAM_PAPER_REF = "6bac3c9";

    public static String lineage() {
        return "Folia " + UPSTREAM_FOLIA + ", Paper " + UPSTREAM_PAPER_REF;
    }
}
