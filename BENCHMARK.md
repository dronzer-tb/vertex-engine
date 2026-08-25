# Measuring the three configurations

One server jar, one seed, one workload, three installs. The numbers come from
`VertexChunkTimings`, which sits at the chunk status boundary — the one point all three
configurations pass through identically, so the same figure means the same thing in each.

| Config | `plugins/` | `vertex/modules/` | What runs the terrain |
| --- | --- | --- | --- |
| **stock** | empty | empty | Folia's own generator |
| **plugin** | `OxideDebug.jar` | empty | Oxide's Rust, through a Bukkit `ChunkGenerator` |
| **module** | empty | `OxideDebug.jar` | Oxide's Rust, through the engine |

Same jar in the plugin and module rows. Which half runs is decided by which directory it is in.

## Why the boundary, and not inside the generator

A Bukkit `ChunkGenerator` is called from *inside* the server's own noise stage, so timing the
stage captures the plugin path with no instrumentation in Oxide at all. The module path returns
before that same stage body. Stock runs it unmodified. One instrument, three answers, no
per-config measurement code to disagree with itself.

## Running it

```bash
java -Dvertex.timings=true -Dvertex.timings.every=500 -Xmx6G -jar vertex-paperclip.jar --nogui
```

Every 500 chunks through the noise stage:

```
[VertexEngine] timings after 500 chunks (500 by module, 0 by the server): noise 1.842ms/chunk over 500 | surface not run | carvers not run -- total 921.00ms
```

`by module` against `by the server` is the fallback count. A module run with a non-zero
server count generated some chunks in Rust and some in Java, and its mean is a blend of the two
— check the log for what fell back before comparing anything.

`surface not run` and `carvers not run` on the module row are the point: Oxide's Rust pass
already applied both, so the engine skips vanilla's. On the stock and plugin rows all three
stages report.

## Holding the workload still

- **Same seed, fresh world every run.** Delete the world directories between configs; a
  partly-generated world measures chunk loading, not chunk generation.
- **Same chunk count.** Compare at the same report line — the 5000-chunk figure against the
  other 5000-chunk figure, not the last line each run happened to print.
- **Discard the first report.** The first few hundred chunks are JIT warmup and, for Oxide, the
  one-time datapack parse and noise router build.
- **Overworld only.** Oxide's presets cover `minecraft:overworld` and `minecraft:the_nether`;
  the End has no multi-noise biome source and falls back, which would silently mix a vanilla
  measurement into an Oxide row.

## What is not being measured

Features and structures, which vanilla generates in every configuration — Oxide has no Rust for
either. They run after the three stages here and cost the same in all three rows, so they widen
every absolute number equally without changing the comparison.

## The number that matters

The previous attempt at this project won in isolation and lost on wall clock: 12ms/chunk
measured on its own against 42–52ms/chunk live, where Paper did 2–15ms. The gap was the
marshalling boundary, not the Rust. `noise ms/chunk` on the module row against the stock row is
the direct successor to that comparison, and the plugin row shows what the Bukkit boundary costs
on top of the same Rust.
