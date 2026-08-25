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

## Phase 1 — build the fork unchanged  *(done)*

`./gradlew applyAllPatches` (not `applyPatches` — ambiguous in this project) resolves Paper
`6bac3c95`, decompiles Minecraft 26.1.2 and applies Folia's patches. ~15 min cold, cached after.
Needs a git identity; there is none set globally on this machine, so it runs with
`GIT_AUTHOR_NAME`/`GIT_AUTHOR_EMAIL`/`GIT_COMMITTER_*` in the environment.

Answered: **paperweight does compile `folia-server/src/main/java/`.** The build patch adds
`../paper-server/src/main/java` as an extra source dir, it does not replace the project's own —
so the engine lives in the fork's normal source tree and only the two call sites are patches.
`implementation(project(":vertex-engine-api"))` added to `folia-server/build.gradle.kts.patch`.

`:folia-server:compileJava` succeeds with the engine in place.

## Phase 2 — brand  *(done: deliberately nothing)*

**The server stays Folia everywhere a name is written.** The jar is `folia-*.jar`, the manifest
says `Implementation-Title: Folia`, `Brand-Name: Folia`, `Brand-Id: papermc:folia`, and
`MinecraftServer#getServerModName` therefore keeps returning `Folia` to `/version`, the
server-list ping and crash reports.

That is the whole point rather than an omission. A Folia fork that renames its brand stops
looking like Folia to every plugin that detects regionised threading, and stops matching every
piece of documentation and support advice a server owner has. Vertex is an engine running inside
Folia, not a replacement for it.

Vertex appears in exactly one place: the `[VertexEngine]` lines the engine logs at boot. Nothing
else is renamed.

## Phase 3 — engine boot + module loading  *(done)*

One line patched into `DedicatedServer#initServer`, before `loadPlugins()`, because a
chunk-generation hook must exist before any world loads and the plugin system does not exist yet
at that point:

```java
dev.vertex.engine.VertexEngine.boot(this.getServerDirectory().toFile());
```

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

Not yet run on a live server — that is the next thing to do.

## Phase 4 — chunk generation hook  *(done, one gap)*

**Changed from the plan.** Not `NoiseBasedChunkGenerator#fillFromNoise` — that method's four
arguments carry no `ServerLevel`, and without one there is no dimension id and no level seed, so
a module cannot be told which generator to answer with. The hook went one frame up into
`ChunkStatusTasks#generateNoise`, which has both:

```java
ServerLevel level = context.level();
if (dev.vertex.engine.VertexChunkBridge.generateNoise(level, context.generator(), chunk)) {
    return CompletableFuture.completedFuture(chunk);
}
```

Better placement for a second reason: `generateSurface` and `generateCarvers` are the next two
methods in the same file, so the remaining stages hook the same way.

`0009-Vertex-Engine-chunk-generation-hook.patch` is 11 added lines across two files. Everything
else lives in `folia-server/src/main/java/dev/vertex/engine/`:

- `VertexChunkBridge` — the call site's whole body. Skips flat and debug generators, builds the
  `ChunkRequest`, catches everything, primes `OCEAN_FLOOR_WG` and `WORLD_SURFACE_WG` after a
  module writes, since surface rules, carvers and structure placement all read them.
- `ChunkAccessTarget` — palettes resolved once per chunk, then a flat array walk per section
  under one `acquire()`/`release()`. Biomes go in through `fillBiomesFromNoise` with a resolver
  over the module's array.

26.x renames worth remembering: `ResourceLocation` is `Identifier`, `ResourceKey#location()` is
`identifier()`, `ChunkPos` is a record so it is `x()`/`z()`.

**The gap:** this hooks the noise stage only. Surface rules and carvers still run afterwards as
separate chunk statuses, on top of what the module wrote — and Oxide's Rust side already applies
its own surface pass and carvers, so they currently double-apply. Either those two stages get
hooked as well, or the module returns noise-only terrain. Hooking them is the right answer and
the file is already open.

## Phase 5 — Oxide as the first module  *(done, untested on a server)*

Settled: **one jar, two entry points.** In `plugins/` Bukkit loads `OxidePlugin`; in
`vertex/modules/` the engine loads `OxideVertexModule` through `ServiceLoader`. Nothing is
duplicated and there is no second build.

- `GeneratorService` lost its `JavaPlugin` dependency to a four-method `GeneratorHost`. A module
  is constructed before the plugin system exists, so it has neither `config.yml` nor a plugin
  logger; it reads a plain `.properties` file out of `vertex/oxide/`.
- `OxideChunkHook` writes blocks *and* biomes from one generation call. The Bukkit path cannot:
  a `ChunkGenerator` has no way to write the biome grid, so it throws the biome array away and
  answers biomes again per position through a `BiomeProvider`.
- A jar in both places still generates once — `OxidePlugin#getDefaultWorldGenerator` returns
  `null` when `Vertex.isChunkGenerationHooked()`. That check goes through the engine API, not a
  static of Oxide's own: the two halves load under different class loaders, so only classes on
  the shared parent are the same class to both.

`dev.vertex:vertex-engine-api:0.1.0` is what Oxide compiles against, `compileOnly`. Published to
GitHub Packages by this repo's CI on pushes to `vertex`, and to `~/.m2` with
`./gradlew :vertex-engine-api:publishToMavenLocal` when working on both at once.

**Needs a one-time account action:** Oxide's CI cannot read another private repo's packages with
the default `GITHUB_TOKEN`. Add a PAT with `read:packages` as `VERTEX_PACKAGES_TOKEN` in the
Oxide repo's secrets.

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
