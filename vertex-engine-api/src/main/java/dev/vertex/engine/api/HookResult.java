package dev.vertex.engine.api;

/**
 * Outcome of a {@link ChunkGenerationHook} call. {@code FALLBACK} means the server must run
 * vanilla generation for this chunk; the reason is for logging only and never changes dispatch.
 */
public record HookResult(Status status, FallbackReason reason) {

    public enum Status {
        SUCCESS,
        FALLBACK
    }

    public enum FallbackReason {
        NONE,
        ENGINE_DISABLED,
        NO_MODULE,
        CHUNK_BLACKLISTED,
        MODULE_ERROR,
        REGION_NOT_OWNED
    }

    private static final HookResult SUCCESS = new HookResult(Status.SUCCESS, FallbackReason.NONE);

    public static HookResult success() {
        return SUCCESS;
    }

    public static HookResult fallback(FallbackReason reason) {
        return new HookResult(Status.FALLBACK, reason);
    }
}
