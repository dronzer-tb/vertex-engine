# Vertex Engine

> A Folia fork that adds a module layer to chunk generation. With no modules installed it is
> stock Folia, and says so.

This is [Folia](https://github.com/PaperMC/Folia) with one thing added. Folia's own README is
kept as [README-Folia.md](README-Folia.md) and everything in it still applies — regionised
threading, region logic, plugin compatibility, all unchanged.

## What it does

Minecraft's chunk generation is hardcoded into the server. Vertex Engine puts a seam there: a
module can take over terrain generation for a chunk, and the server falls back to its own
generation whenever the module declines or fails.

The design constraint is that **a server with no modules must behave exactly like the Folia it
was built from**. Not "close enough" — the engine registers nothing, every dispatch returns
`FALLBACK`, and generation runs untouched. That is the default, not a degraded mode.

Nothing is renamed. The jar is `folia-*.jar`, the manifest brand is Folia, and
`/version` reports Folia, because a Folia fork that renames its brand stops looking like Folia to
every plugin that detects regionised threading. Vertex appears in exactly one place: the lines
the engine logs at boot.

```
[VertexEngine] Vertex Engine 0.1.0 -- no modules in vertex/modules, running stock generation
```

With a module installed:

```
[VertexEngine] Vertex Engine 0.1.0 (Folia 26.2, Paper 37dc545)
[VertexEngine] Module directory: /srv/mc/vertex/modules
[VertexEngine]   oxide 0.1.0 -- ACTIVE
[VertexEngine] Ready -- 1 module(s), generating [NOISE, SURFACE, CARVERS] hooked
```

## Quick start

Drop module jars into `vertex/modules/` and restart. Remove them and restart to go back to stock
generation. There is no config file and nothing to undo.

```
server/
  folia-paperclip-26.2.jar
  vertex/
    modules/
      oxide.jar          <- a module
    oxide/               <- that module's own data, created on demand
```

## Writing a module

Depend on `dev.vertex:vertex-engine-api` — it holds no Minecraft types, so a module never
compiles against the server or against Minecraft:

```kotlin
compileOnly("dev.vertex:vertex-engine-api:0.1.0-SNAPSHOT")
```

Implement `VertexModule`, declare it in
`META-INF/services/dev.vertex.engine.api.VertexModule`, and register a hook plus the stages your
output already covers:

```java
public final class ExampleModule implements VertexModule {
    @Override public String id()      { return "example"; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public void onEnable(ModuleContext context) {
        context.registerChunkGeneration(
            (request, target) -> {
                target.setBlocks(blockPalette, blockIndices);
                target.setBiomes(biomePalette, biomeIndices);
                return HookResult.success();
            },
            EnumSet.of(ChunkStage.SURFACE, ChunkStage.CARVERS));
    }
}
```

`ChunkTarget` takes string palettes and bulk index arrays rather than block objects. That is
deliberate: it keeps modules free of server internals, and it keeps the one performance-critical
conversion in a single place where it can be optimised for every module at once. A previous
attempt at this project marshalled per block and lost every millisecond it had won.

The stage set matters. A generator that produces finished terrain applies surface rules and
carvers itself, so the server must skip its own — otherwise both run and the result is
double-applied. Stages are skipped per chunk, not per server: a chunk that fell back is plain
noise and still needs the server's surface and carvers.

Modules load *before* the plugin system, because a chunk-generation hook has to exist before any
world loads. They are not Bukkit plugins and cannot use the Bukkit API at enable time.

## Measuring

```bash
java -Dvertex.timings=true -Dvertex.timings.every=250 -jar folia-paperclip-26.2.jar --nogui
```

```
[VertexEngine] timings after 250 chunks (250 by module, 0 by the server): noise 1.842ms/chunk over 250 | surface not run | carvers not run -- total 460.50ms
```

Timings sit at the chunk status boundary, which is the one point every configuration passes
through identically — stock generation, a Bukkit `ChunkGenerator` (which the server calls from
inside its own noise stage), and a module. The same number therefore means the same thing in all
three. `BENCHMARK.md` has the comparison protocol.

## Building

```bash
./gradlew applyAllPatches          # decompiles Minecraft, applies Folia's patches then ours
./gradlew :folia-server:createPaperclipJar
```

`applyAllPatches` needs a git identity. If you have none set globally, pass one in the
environment rather than configuring it globally:

```bash
GIT_AUTHOR_NAME=you GIT_AUTHOR_EMAIL=you@example.com \
GIT_COMMITTER_NAME=you GIT_COMMITTER_EMAIL=you@example.com \
./gradlew applyAllPatches
```

## How it is put together

The engine is ordinary source in `folia-server/src/main/java/dev/vertex/engine/`, not a patch.
Only the call sites are patched, and
`folia-server/minecraft-patches/features/0012-Vertex-Engine-chunk-generation-hook.patch` is
**38 added lines across three files**:

| file | what |
| --- | --- |
| `DedicatedServer` | one line, booting the engine before `loadPlugins()` |
| `ChunkStatusTasks` | three early returns, in the noise, surface and carver stages |
| `ChunkAccess` | one field, recording that a module generated this chunk's terrain |

Everything else — the module registry, the `ChunkAccess` conversion, the timings — lives in the
fork's own tree, so a Minecraft update rebases those 38 lines rather than a bridge. The engine
transplanted from Folia 26.1.2 to 26.2 with no code changes at all; only the patch number moved.

## Status

Chunk generation is the only hooked subsystem. The API is `0.1.0-SNAPSHOT` and will change.
[Oxide](https://github.com/dronzer-tb/oxide) is the first module.

## Licence

Patches are GPL-3.0, inherited from Folia — see [PATCHES-LICENSE](PATCHES-LICENSE). Folia is a
fork of [Paper](https://github.com/PaperMC/Paper); upstream licensing applies to upstream code.
