# Vertex Engine — build plan

Fork of Folia carrying the Vertex Engine: a module and hook registry that Rust-backed subsystems
plug into. The engine ships no generation of its own.

- Fork base: Folia `de00a94`, `mcVersion=26.1.2`, `paperRef=6bac3c95`
- Branch: `vertex`, upstream remote `PaperMC/Folia`
- Toolchain: Java 25, paperweight-patcher `2.0.0-beta.21`

## The guarantee

**A Vertex server with no modules installed behaves exactly like stock Folia.**

The engine registers nothing, every dispatch returns `FALLBACK`, and vanilla generation runs
untouched. Boot logs a single line naming the module directory it checked. Install
`vertex/modules/oxide.jar` and it activates; delete it and the server is stock again on the next
restart. No config flag, no migration, nothing to undo.

That is the default path, not a degraded one, and every failure route inside the engine leads
back to it: no module, module threw, module refused the chunk.

## Repository split

| Repo | Holds |
| ---- | ----- |
| `dronzer-tb/vertex` | the Folia fork, the engine, and `vertex-engine-api` |
| `dronzer-tb/oxide` | Oxide, depending only on the published `vertex-engine-api` artifact |

Oxide never compiles against the fork or against Minecraft. `vertex-engine-api` is a plain Java
subproject containing no Minecraft types:

```
vertex-engine-api/src/main/java/dev/vertex/engine/api/
  VertexModule.java          module contract, discovered via ServiceLoader
  ModuleContext.java         what a module gets at enable time
  ChunkGenerationHook.java   the SPI Oxide implements
  ChunkTarget.java           neutral write surface: string palettes + bulk index arrays
  HookResult.java            SUCCESS / FALLBACK + reasons
```

`ChunkTarget` is why this works. A module names blocks as strings and hands over bulk index
arrays; the engine performs the actual chunk write. Modules stay independent of server
internals, and the one performance-critical conversion lives in a single place where it can be
optimised for every module at once — which is precisely where the previous attempt lost its
time.

## Decisions taken

**Module names stay `folia-server` / `folia-api`.** Renaming means rewriting both
`build.gradle.kts.patch` files and every path in a 20k-line patch stack, for no functional gain.
Branding happens at `getServerModName()`, which is where users actually see it.

**Rebase model is git, not paperweight.** Vertex forks Folia's *repository* and leaves Folia's
`upstreams.paper` block untouched. Our patches append to Folia's stack as `0009+`. A new Folia
release is `git merge upstream/main`.

**Build-time patch first, runtime ASM second.** The original intent was to install every NMS hook
at runtime. With a fork in hand the first working version is cheaper as a build-time patch — one
call site, no descriptor verification, no transformer. Phase 6 converts it once there is
something working to convert. Phases 1-5 do not depend on it.

**`VertexEngine` holds no Minecraft types.** The chunk conversion happens at the patched call
site, so the fork's contact with upstream code is one method, and the engine itself is plain
Java that compiles and tests without the server.

## Phase 1 — build the fork unchanged  *(blocking, network required)*

1. `./gradlew applyPatches` — resolves Paper `6bac3c95`, applies Folia's 16 patches.
2. Add `implementation(project(":vertex-engine-api"))` to `folia-server/build.gradle.kts.patch`.
3. Confirm paperweight compiles `folia-server/src/main/java/` in a patcher project. If it does
   not, the engine moves into `0009-Vertex-Engine.patch` — same Java either way. Record the
   answer here.
4. `./gradlew createMojmapPaperclipJar` — on GitHub Actions, not locally.

Exit: unmodified Folia jar boots.

## Phase 2 — brand

Patch `MinecraftServer#getServerModName` to return `Vertex`. Reaches `/version`, the server-list
ping mod string, and crash report headers from one place.

```
[ServerMain/INFO]: [bootstrap] Loading Vertex 26.1.2-R0.1-SNAPSHOT (Folia 26.1.2, Paper 6bac3c9) for Minecraft 26.1.2
```

## Phase 3 — engine boot + module loading

One line patched into `DedicatedServer#initServer`, before `loadPlugins()`:

```java
dev.vertex.engine.VertexEngine.boot(this.getServerDirectory().toFile());
```

Before `loadPlugins()` because a chunk-generation hook must exist before any world loads, and the
plugin system does not exist yet at that point.

Empty server:

```
[Server thread/INFO]: [VertexEngine] Vertex Engine 0.1.0 -- no modules in /srv/mc/vertex/modules, running stock generation
```

With Oxide installed:

```
[Server thread/INFO]: [VertexEngine] Vertex Engine 0.1.0 (Folia 26.1.2, Paper 6bac3c9)
[Server thread/INFO]: [VertexEngine] Module directory: /srv/mc/vertex/modules
[Server thread/INFO]: [VertexEngine]   oxide 0.1.0 -- ACTIVE
[Server thread/INFO]: [VertexEngine] Ready -- 1 module(s), chunk generation hooked
```

Exit: a no-op test module loads and appears in the log; removing it restores the quiet line.

## Phase 4 — chunk generation hook

Patch `NoiseBasedChunkGenerator#fillFromNoise`, the same single call site the previous
Paper-based attempt used. Verified: `NoiseBasedChunkGenerator` appears in **none** of Folia's 16
patches, so there is no upstream conflict.

```java
HookResult result = VertexEngine.get().generateChunk(seed, chunkPos.x, chunkPos.z, target);
if (result.status() == HookResult.Status.SUCCESS) {
    return;  // skip vanilla terrain entirely
}
// FALLBACK falls through to this.doFill(...)
```

Also written here: the `ChunkAccess` implementation of `ChunkTarget`. Deferred to this phase
deliberately — it needs the real 26.1.2 API, which does not exist on disk until Phase 1 runs.
Bulk section writes, not per-block `setBlockState`.

Exit: a module filling a chunk with stone visibly overrides vanilla terrain; removing it returns
vanilla terrain with no restart-time errors.

## Phase 5 — Oxide as the first module

In the Oxide repo: implement `ChunkGenerationHook`, declare it in
`META-INF/services/dev.vertex.engine.api.VertexModule`, ship `vertex/modules/oxide.jar`. Oxide's
existing Bukkit `ChunkGenerator` path stays for unmodified Folia. Both consume the same
`oxide-ffi` cdylib.

## Phase 6 — runtime ASM  *(optional)*

Convert the Phase 4 call site to a `ClassFileTransformer`. Buys jar-swap iteration instead of a
10-minute `applyPatches` rebuild; costs a descriptor-verification layer that must refuse to
install on mismatch rather than rewrite a shape it did not expect.

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

On Folia this is worse, not better: the cost lands on the owning region's tick loop, stalling the
players in it. `ChunkTarget`'s bulk shape exists to make the fast path the only path. Measure the
boundary separately from generation from the first working chunk.
