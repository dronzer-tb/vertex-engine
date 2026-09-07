# Vertex Engine

> High-performance regionised multithreaded Minecraft server platform based on Folia, featuring zero-copy native module SPI for sub-millisecond tick times and accelerated world generation.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Folia Fork](https://img.shields.io/badge/Fork-Folia%2026.2-blueviolet.svg)]()
[![Java](https://img.shields.io/badge/Java-22%2B%20Panama%20FFI-orange.svg)]()
[![Powered by Oxide](https://img.shields.io/badge/Powered%20by-Oxide%20Rust-red.svg)](https://github.com/dronzer-tb/oxide)

---

## Overview

Vertex Engine is an engine-level fork of [Folia](https://github.com/PaperMC/Folia) engineered for extreme performance, high player counts, and native FFI integration. While standard Bukkit and Paper plugins operate across high-level wrapper APIs, Vertex Engine exposes a zero-overhead native module SPI (`ChunkTarget`) that allows native libraries like [Oxide](https://github.com/dronzer-tb/oxide) to write directly into the server's internal chunk section buffers.

---

## Key Features

- **Direct Memory Pointer Pipeline:** Bypasses intermediate Bukkit `ChunkDataImpl` copying. Rust engines write block states and 3D biomes simultaneously into `LevelChunkSection` palette buffers.
- **Elimination of GC Churn:** Completely avoids millions of JVM object allocations during high-speed world generation and region ticking.
- **Seamless Dual-Mode Compatibility:** Standard Folia and Paper plugins run untouched; native performance modules load transparently via `ServiceLoader`.
- **Integrated Benchmark Performance:** Powers chunk pre-generation speeds exceeding **1,550+ Chunks Per Second (CPS)** on modern multi-core processors.

---

## Architecture & Native Module Hook

```
                                +-----------------------------------+
                                |        Folia Core Pipeline        |
                                |     (ChunkStatusTasks#NOISE)      |
                                +-----------------+-----------------+
                                                  |
                                       [VertexChunkBridge]
                                                  |
                                                  v
                               +-------------------------------------+
                               |     dev.vertex.engine.api           |
                               |  - ChunkTarget (direct pointers)    |
                               |  - ChunkGenerationHook (SPI)        |
                               +------------------+------------------+
                                                  |
                                       [Java Panama FFI]
                                                  |
                                                  v
                               +-------------------------------------+
                               |          Oxide Native (Rust)        |
                               |   - SIMD Noise Density Graphs       |
                               |   - 6-Axis Multi-Noise Biomes       |
                               |   - Aquifers & Cave Carvers         |
                               +-------------------------------------+
```

---

## Building from Source

### Prerequisites
- JDK 21 or JDK 25 (`openjdk-25-jdk-headless`)
- Git

### Build Steps
```bash
# Clone the repository
git clone https://github.com/dronzer-tb/vertex-engine.git
cd vertex-engine

# Apply all patches to the upstream base
./gradlew applyAllPatches

# Compile and package the Paperclip server JAR
./gradlew :folia-server:createPaperclipJar
```

The resulting server artifact will be generated at:
`folia-server/build/libs/folia-paperclip-*-reobf.jar`

---

## Running Native Modules

1. Place your server JAR into a directory and run standard startup parameters:
   ```bash
   java -Xms12G -Xmx12G --enable-native-access=ALL-UNNAMED -jar folia-paperclip.jar nogui
   ```
2. Place compatible engine modules (such as `Oxide.jar`) into the `vertex/modules/` directory.
3. Configure module properties in `vertex/<module>/<module>.properties`.

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for details.

Built by **x00f8** (Dronzer Studios).
