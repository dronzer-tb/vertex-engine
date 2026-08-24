package dev.vertex.engine;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * The Vertex Engine: a module and hook registry that sits between Folia and Rust-backed
 * subsystems such as Oxide.
 *
 * <p>Booted from {@code DedicatedServer#initServer} before {@code loadPlugins()}, which is the
 * last point at which a hook can be registered before worlds load. Modules are ordinary jars in
 * {@code vertex/modules/}; they are not Bukkit plugins and cannot use the Bukkit API at enable
 * time, because the plugin system does not exist yet.
 *
 * <p>Every failure path here ends in vanilla generation, never in a half-installed hook.
 */
public final class VertexEngine {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[VertexEngine] ";

    private static volatile VertexEngine instance;

    private final Path moduleDir;
    private final List<VertexModule> modules = new ArrayList<>();
    private volatile ChunkGenerationHook chunkHook;

    private VertexEngine(Path moduleDir) {
        this.moduleDir = moduleDir;
    }

    /** The booted engine, or {@code null} when Vertex did not start. */
    public static VertexEngine get() {
        return instance;
    }

    /**
     * Entry point patched into {@code DedicatedServer#initServer}. Safe to call once; a second
     * call is ignored rather than rebuilding the registry underneath a live server.
     */
    public static void boot(File serverDirectory) {
        if (instance != null) {
            return;
        }
        LOGGER.info(TAG + VertexBrand.NAME + " Engine " + VertexBrand.ENGINE_VERSION
                + " (" + VertexBrand.lineage() + ")");

        VertexEngine engine = new VertexEngine(serverDirectory.toPath().resolve("vertex").resolve("modules"));
        engine.loadModules();
        instance = engine;

        LOGGER.info(TAG + "Ready -- " + engine.modules.size() + " module(s), "
                + (engine.chunkHook != null ? "chunk-generation hook active" : "no hooks registered"));
    }

    /**
     * Registers the chunk-generation hook. One module owns terrain; a second registration is
     * refused rather than silently replacing the first, since which one won would depend on
     * module load order.
     */
    public void register(ChunkGenerationHook hook) {
        if (this.chunkHook != null) {
            throw new IllegalStateException("a chunk-generation hook is already registered");
        }
        this.chunkHook = hook;
    }

    /**
     * Dispatches one chunk to the registered hook. Returns {@code FALLBACK} -- never throws --
     * when no module is registered or the module itself fails, so the caller can run vanilla.
     */
    public HookResult generateChunk(long seed, int chunkX, int chunkZ, ChunkAccess into) {
        ChunkGenerationHook hook = this.chunkHook;
        if (hook == null) {
            return HookResult.fallback(HookResult.FallbackReason.NO_MODULE);
        }
        try {
            return hook.generate(seed, chunkX, chunkZ, into);
        } catch (Throwable t) {
            LOGGER.error(TAG + "module threw generating chunk (" + chunkX + ", " + chunkZ
                    + ") -- falling back to vanilla", t);
            return HookResult.fallback(HookResult.FallbackReason.MODULE_ERROR);
        }
    }

    private void loadModules() {
        if (!Files.isDirectory(this.moduleDir)) {
            LOGGER.info(TAG + "No module directory at " + this.moduleDir + " -- nothing to load");
            return;
        }
        LOGGER.info(TAG + "Module directory: " + this.moduleDir);

        List<URL> jars = new ArrayList<>();
        try (Stream<Path> entries = Files.list(this.moduleDir)) {
            for (Path entry : entries.filter(p -> p.toString().endsWith(".jar")).toList()) {
                jars.add(entry.toUri().toURL());
            }
        } catch (IOException e) {
            LOGGER.error(TAG + "Could not read " + this.moduleDir + " -- no modules loaded", e);
            return;
        }
        if (jars.isEmpty()) {
            LOGGER.info(TAG + "No module jars found");
            return;
        }

        ClassLoader loader = new URLClassLoader("vertex-modules", jars.toArray(new URL[0]),
                VertexEngine.class.getClassLoader());
        for (VertexModule module : ServiceLoader.load(VertexModule.class, loader)) {
            try {
                module.onEnable(this);
                this.modules.add(module);
                LOGGER.info(TAG + "  " + module.id() + " " + module.version() + " -- ACTIVE");
            } catch (Throwable t) {
                LOGGER.error(TAG + "  " + module.id() + " failed to enable -- skipped", t);
            }
        }
    }
}
