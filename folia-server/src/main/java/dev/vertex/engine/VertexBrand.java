package dev.vertex.engine;

/**
 * Identity strings for the engine's own log lines, and nothing else.
 *
 * The server does not rename itself. The jar, the manifest brand and getServerModName all stay
 * Folia, because a Folia fork that renames its brand stops looking like Folia to every plugin
 * that detects regionised threading. Vertex is an engine running inside Folia.
 *
 * Referenced only by the engine's boot banner. There is no brand patch.
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
