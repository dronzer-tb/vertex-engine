package dev.vertex.engine;

import com.mojang.logging.LogUtils;
import dev.vertex.engine.api.ChunkGenerationHook;
import dev.vertex.engine.api.ChunkRequest;
import dev.vertex.engine.api.ChunkStage;
import dev.vertex.engine.api.ChunkTarget;
import dev.vertex.engine.api.HookResult;
import dev.vertex.engine.api.ModuleContext;
import dev.vertex.engine.api.Vertex;
import dev.vertex.engine.api.VertexModule;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The Vertex Engine: a module and hook registry sitting between the server and subsystems such
 * as Oxide.
 *
 * <p>Booted from {@code DedicatedServer#initServer} before {@code loadPlugins()} -- the last
 * point at which a hook can be registered before worlds load. Modules are ordinary jars in
 * {@code vertex/modules/}; they are not Bukkit plugins and cannot use the Bukkit API at enable
 * time, because the plugin system does not exist yet.
 *
 * <p>With no modules installed the engine registers nothing and every dispatch returns
 * {@code FALLBACK}, so the server generates exactly as unmodified Folia does. That is the
 * intended default, not a degraded mode.
 *
 * <p>This class holds no Minecraft types. The conversion between the server's chunk and
 * {@link ChunkTarget} happens at the patched call site, keeping the fork's contact with upstream
 * code to a single method.
 */
public final class VertexEngine {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[VertexEngine] ";

    private static volatile VertexEngine instance;

    private final Path moduleDir;
    private final List<VertexModule> modules = new ArrayList<>();
    private volatile ChunkGenerationHook chunkHook;
    private volatile Set<ChunkStage> ownedStages = EnumSet.noneOf(ChunkStage.class);

    private VertexEngine(Path moduleDir) {
        this.moduleDir = moduleDir;
    }

    /** The booted engine. Never null once {@link #boot} has run. */
    public static VertexEngine get() {
        return instance;
    }

    /** Whether any module has taken over chunk generation. */
    public boolean hasChunkGeneration() {
        return this.chunkHook != null;
    }

    /**
     * Whether the registered module's output already includes {@code stage}, so the server must
     * not run its own. Only meaningful for a chunk the module actually generated -- see
     * {@link dev.vertex.engine.api.ChunkStage}.
     */
    public boolean ownsStage(ChunkStage stage) {
        return this.ownedStages.contains(stage);
    }

    /**
     * Entry point patched into {@code DedicatedServer#initServer}. A second call is ignored
     * rather than rebuilding the registry underneath a running server.
     */
    public static void boot(File serverDirectory) {
        if (instance != null) {
            return;
        }
        if (VertexChunkTimings.enabled()) {
            LOGGER.info(TAG + "chunk generation timings are on (-Dvertex.timings). Numbers are"
                    + " measured at the chunk status boundary, so they are comparable across a"
                    + " stock server, a Bukkit generator and a module.");
        }
        VertexEngine engine = new VertexEngine(serverDirectory.toPath().resolve("vertex").resolve("modules"));
        engine.loadModules();
        instance = engine;

        if (engine.modules.isEmpty()) {
            // Nothing installed: stay quiet and behave as stock Folia. One line, so an operator
            // can still tell the engine is present and looking in the right place.
            LOGGER.info(TAG + VertexBrand.NAME + " Engine " + VertexBrand.ENGINE_VERSION
                    + " -- no modules in " + engine.moduleDir + ", running stock generation");
            return;
        }
        LOGGER.info(TAG + "Ready -- " + engine.modules.size() + " module(s), "
                + (engine.hasChunkGeneration()
                        ? "generating " + engine.ownedStages
                        : "no chunk generation") + " hooked");
    }

    /**
     * Dispatches one chunk to the registered hook. Returns {@code FALLBACK} -- never throws --
     * when no module is registered or the module itself fails, so the caller runs vanilla.
     */
    public HookResult generateChunk(ChunkRequest request, ChunkTarget target) {
        ChunkGenerationHook hook = this.chunkHook;
        if (hook == null) {
            return HookResult.fallback(HookResult.FallbackReason.NO_MODULE);
        }
        try {
            return hook.generate(request, target);
        } catch (Throwable t) {
            LOGGER.error(TAG + "module threw generating chunk (" + request.chunkX() + ", "
                    + request.chunkZ() + ") in " + request.dimensionId()
                    + " -- falling back to vanilla", t);
            return HookResult.fallback(HookResult.FallbackReason.MODULE_ERROR);
        }
    }

    private void loadModules() {
        if (!Files.isDirectory(this.moduleDir)) {
            return;
        }
        List<URL> jars = new ArrayList<>();
        try (Stream<Path> entries = Files.list(this.moduleDir)) {
            for (Path entry : entries.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                jars.add(entry.toUri().toURL());
            }
        } catch (IOException e) {
            LOGGER.error(TAG + "Could not read " + this.moduleDir + " -- no modules loaded", e);
            return;
        }
        if (jars.isEmpty()) {
            return;
        }
        LOGGER.info(TAG + VertexBrand.NAME + " Engine " + VertexBrand.ENGINE_VERSION
                + " (" + VertexBrand.lineage() + ")");
        LOGGER.info(TAG + "Module directory: " + this.moduleDir);

        ClassLoader loader = new URLClassLoader("vertex-modules", jars.toArray(new URL[0]),
                VertexEngine.class.getClassLoader());
        for (VertexModule module : ServiceLoader.load(VertexModule.class, loader)) {
            try {
                module.onEnable(new Context(module));
                this.modules.add(module);
                LOGGER.info(TAG + "  " + module.id() + " " + module.version() + " -- ACTIVE");
            } catch (Throwable t) {
                LOGGER.error(TAG + "  " + module.id() + " failed to enable -- skipped", t);
            }
        }
    }

    /** Per-module view of the engine, so a module never holds the registry itself. */
    private final class Context implements ModuleContext {

        private final VertexModule module;

        Context(VertexModule module) {
            this.module = module;
        }

        @Override
        public void registerChunkGeneration(ChunkGenerationHook hook, Set<ChunkStage> stages) {
            if (VertexEngine.this.chunkHook != null) {
                throw new IllegalStateException("chunk generation is already registered by another module");
            }
            EnumSet<ChunkStage> owned = EnumSet.of(ChunkStage.NOISE);
            owned.addAll(stages);
            VertexEngine.this.ownedStages = owned;
            VertexEngine.this.chunkHook = hook;
            // Visible to Bukkit plugins too, which load later under a different class loader
            // and need to know the server's own terrain generation is no longer running.
            Vertex.markChunkGenerationHooked();
        }

        @Override
        public Path dataDirectory() {
            // vertex/<id>/, a sibling of vertex/modules/ -- module state does not belong in
            // the directory an operator drops jars into and clears out.
            Path dir = VertexEngine.this.moduleDir.getParent().resolve(this.module.id());
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IllegalStateException("could not create data directory " + dir, e);
            }
            return dir;
        }
    }
}
