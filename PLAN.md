# Vertex Engine — build plan

Fork of Folia that carries the Vertex Engine: a module/hook registry Rust-backed subsystems
(starting with Oxide chunk generation) plug into.

- Fork base: Folia `de00a94`, `mcVersion=26.1.2`, `paperRef=6bac3c95`
- Branch: `vertex`, upstream remote `PaperMC/Folia`
- Working copy: `~/Assets (ds)/VERTEX/vertex`
- Toolchain: Java 25, paperweight-patcher `2.0.0-beta.21`

## Decisions taken

**Module names stay `folia-server` / `folia-api`.** Renaming means rewriting both
`build.gradle.kts.patch` files and every path in a 20k-line patch stack, for no functional gain.
Branding happens at `getServerModName()`, which is where users actually see it. Revisit only if
the published Maven coordinates need it.

**Rebase model is git, not paperweight.** Vertex forks Folia's *repository* and keeps Folia's
`upstreams.paper` block untouched. Our patches append to Folia's existing stack as `0009+`.
Pulling a new Folia release is `git merge upstream/main`. Paperweight's generic-upstream support
is not used, so there is nothing extra to verify in the build config.

**Build-time patch first, runtime ASM second.** The original intent was to install every NMS hook
at runtime. With a fork already in hand, the first working version is cheaper as a build-time
patch — one call site, no descriptor verification, no transformer. Phase 4 converts it to ASM
once there is something working to convert; that buys jar-swap iteration instead of a 10-minute
`applyPatches` rebuild, at the cost of a verification layer. Keep or drop that phase on its own
merits; phases 1-3 do not depend on it.

**Engine sources live in `folia-server/src/main/java/dev/vertex/engine/`** as real files, not
patch content — new classes touch no upstream code, so a patch would only add merge friction.
Placement must be confirmed against paperweight on the first `applyPatches`; see Phase 1.

## Phase 1 — build the fork unchanged  *(blocking, network required)*

Prove the toolchain before adding anything.

1. `./gradlew applyPatches` — resolves Paper `6bac3c95`, applies Folia's 16 patches.
2. Confirm `folia-server/src/main/java/dev/vertex/engine/*.java` is compiled into the server
   jar. If paperweight does not pick up that source set in a patcher project, move the engine
   into a new patch `0009-Vertex-Engine.patch` and record it here.
3. `./gradlew createMojmapPaperclipJar` — runs on GitHub Actions, not locally.

Exit: unmodified Folia jar boots.

## Phase 2 — brand

Patch `MinecraftServer#getServerModName` to return `Vertex`. Reaches `/version`, the server-list
ping mod string, and crash report headers from one place.

Boot line becomes:

```
[ServerMain/INFO]: [bootstrap] Loading Vertex 26.1.2-R0.1-SNAPSHOT (Folia 26.1.2, Paper 6bac3c9) for Minecraft 26.1.2
```

Exit: `/version` reports Vertex; a crash report names Vertex and its Folia/Paper lineage.

## Phase 3 — engine boot + module loading

Patch one line into `DedicatedServer#initServer`, before `loadPlugins()`:

```java
dev.vertex.engine.VertexEngine.boot(this.getServerDirectory().toFile());
```

Before `loadPlugins()` because a chunk-generation hook must be registered before any world
loads, and the plugin system does not exist yet at that point.

`VertexEngine` (written, 215 lines across 5 files) loads `vertex/modules/*.jar` through
`ServiceLoader` on a child classloader and logs:

```
[Server thread/INFO]: [VertexEngine] Vertex Engine 0.1.0 (Folia 26.1.2, Paper 6bac3c9)
[Server thread/INFO]: [VertexEngine] Module directory: /srv/mc/vertex/modules
[Server thread/INFO]: [VertexEngine]   oxide 0.1.0 -- ACTIVE
[Server thread/INFO]: [VertexEngine] Ready -- 1 module(s), chunk-generation hook active
```

Exit: a no-op test module loads and appears in the log.

## Phase 4 — chunk generation hook

Patch `NoiseBasedChunkGenerator#fillFromNoise`, the same single call site the previous Paper-based
attempt used. Verified this session: `NoiseBasedChunkGenerator` appears in **none** of Folia's 16
patches, so there is no upstream conflict.

```java
HookResult result = VertexEngine.get().generateChunk(seed, chunkPos.x, chunkPos.z, chunk);
if (result.status() == HookResult.Status.SUCCESS) {
    return;  // skip vanilla terrain entirely
}
// FALLBACK falls through to this.doFill(...)
```

Exit: a module that fills a chunk with stone visibly overrides vanilla terrain; disabling the
module returns vanilla terrain with no restart-time errors.

## Phase 5 — Oxide as the first module

Oxide's `nms` branch implements `ChunkGenerationHook` and ships `vertex/modules/oxide.jar`.
Oxide's `plugin` branch stays a plain Bukkit `ChunkGenerator` for unmodified Folia. Both consume
the same `oxide-ffi` cdylib.

Open before starting: does one Oxide jar detect the engine at runtime and serve both, or do the
two branches ship separately? This decides the FFI/service boundary and should be settled first.

## Carried forward from the previous attempt

Ported from `vertex_local` (Paper 1.21.11) into the engine, not into modules — every module gets
them for free:

- `ChunkBlacklist` — per-chunk opt-out, persisted
- `FailureTracker` + `EngineHealthMonitor` — kill switch after repeated module failure
- `VPregenCommand` — needs retargeting onto Folia's region scheduler

Discarded: `vertex-scheduler` (Folia owns chunk scheduling), `vertex-terrain` (Oxide's stack is
parity-verified and ahead), and the byte-per-block transport.

## The constraint that killed v1

The previous attempt reached `Active ✔` on a live server and still lost to vanilla on wall clock:
`rust=42-52ms` per chunk against Paper's own 2-15ms, with `12ms` measured in isolation. The gap
was the boundary — batching, FFI, and per-block `ChunkDataMapper.apply` of 98,304 bytes.

On Folia this is worse, not better: that cost lands on the owning region's tick loop, stalling
the players in it. **Settle the transport design before Phase 5**, and measure the boundary
separately from generation from the first working chunk.
